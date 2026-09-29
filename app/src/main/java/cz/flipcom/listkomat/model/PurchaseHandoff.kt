package cz.flipcom.listkomat.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * What we handed to the SMS app — everything needed to start the countdown,
 * so a purchase restored after process death doesn't depend on the catalog
 * still containing the ticket.
 */
@Serializable
data class PendingTicket(
    val cityKey: String,
    val cityName: String,
    val ticketCode: String,
    val durationMinutes: Int,
    val priceKc: Int,
    val armedAt: Long,
)

sealed interface Purchase {
    /** Handed off; waiting for the SMS app to cover us ([sawPause]) and for
     *  the user to come back. [pausedAt] feeds the rotation guard.
     *  [timedOut]: no pause within the timeout, so we say the SMS app didn't
     *  open — but keep the purchase, because a pause may still come (e.g.
     *  Messages already open in the other split-screen pane never pauses us). */
    data class Armed(
        val pending: PendingTicket,
        val sawPause: Boolean = false,
        val pausedAt: Long? = null,
        val timedOut: Boolean = false,
    ) : Purchase

    /** Back from the SMS app — ask "did you send it?". [sentAtMs] anchors the
     *  countdown if the answer is yes. */
    data class AwaitingAnswer(val pending: PendingTicket, val sentAtMs: Long) : Purchase

    /** No SMS app could be started at all. */
    data object Failed : Purchase
}

/**
 * The Android stand-in for iOS's MFMessageComposeViewController result.
 * ACTION_SENDTO reports nothing back, so the "did you send it?" question may
 * only appear once the SMS app has covered us and gone away again — i.e.
 * after a pause → resume — never on the frame after the hand-off. The
 * pending purchase is persisted the moment it is armed, so a process death
 * while the user is in the SMS app still asks on return.
 *
 * Pure logic: lifecycle events and the clock come in from the caller.
 */
class PurchaseHandoff(
    private val store: Store,
    private val now: () -> Long,
) {
    interface Store {
        fun loadPending(): PendingTicket?
        fun savePending(pending: PendingTicket)
        fun clearPending()
    }

    private val _state = MutableStateFlow<Purchase?>(null)
    val state: StateFlow<Purchase?> = _state

    init {
        // A persisted purchase means the process died between hand-off and
        // answer. Recent enough → the user is most likely just back from the
        // SMS app, so ask now; stale → they have long moved on, drop it.
        // We can't know when they came back, so a yes anchors the countdown
        // at the hand-off: never show more validity than the operator grants.
        store.loadPending()?.let { pending ->
            if (now() - pending.armedAt < RESTORE_WINDOW_MS) {
                _state.value = Purchase.AwaitingAnswer(pending, sentAtMs = pending.armedAt)
            } else {
                store.clearPending()
            }
        }
    }

    /** Called just before starting the SMS activity. Returns false — and the
     *  caller must not hand off — while a hand-off is already in flight: a
     *  second tap landing before the SMS app covers us must neither start a
     *  second SMS nor reset the first purchase's pause tracking. */
    fun armed(pending: PendingTicket): Boolean {
        if (_state.value != null) return false
        store.savePending(pending)
        _state.value = Purchase.Armed(pending)
        return true
    }

    /** ON_PAUSE — the SMS app is taking over. */
    fun left() {
        val armed = _state.value as? Purchase.Armed ?: return
        _state.value = armed.copy(sawPause = true, pausedAt = now())
    }

    /** ON_RESUME — back from the SMS app, unless it was a mere rotation
     *  (a pause/resume pair too quick to have been a trip elsewhere). */
    fun returned() {
        val armed = _state.value as? Purchase.Armed ?: return
        if (!armed.sawPause) return
        val pausedAt = armed.pausedAt ?: return
        _state.value = if (now() - pausedAt < ROTATION_GUARD_MS) {
            armed.copy(sawPause = false, pausedAt = null)
        } else {
            Purchase.AwaitingAnswer(armed.pending, sentAtMs = now())
        }
    }

    /** Milliseconds until [timedOut] should be checked, or null when not armed. */
    fun handOffDeadlineIn(): Long? {
        val armed = _state.value as? Purchase.Armed ?: return null
        if (armed.timedOut) return null
        return (armed.pending.armedAt + HANDOFF_TIMEOUT_MS - now()).coerceAtLeast(0)
    }

    /** The fallback: armed but never paused within [HANDOFF_TIMEOUT_MS] means
     *  the SMS app most likely didn't take over. Non-destructive — see
     *  [Purchase.Armed.timedOut]; the user's OK on the alert clears it. */
    fun timedOut() {
        val armed = _state.value as? Purchase.Armed ?: return
        if (armed.sawPause || now() - armed.pending.armedAt < HANDOFF_TIMEOUT_MS) return
        _state.value = armed.copy(timedOut = true)
    }

    /** No SMS app could be started at all. */
    fun failed() {
        store.clearPending()
        _state.value = Purchase.Failed
    }

    /** The user answered (yes or no) or acknowledged a failure. Returns the
     *  question that was being asked, if any. */
    fun resolved(): Purchase.AwaitingAnswer? {
        val asked = _state.value as? Purchase.AwaitingAnswer
        store.clearPending()
        _state.value = null
        return asked
    }

    companion object {
        /** Cold-starting Messages on a Galaxy A14 is slow; 2 s leaves margin. */
        const val HANDOFF_TIMEOUT_MS: Long = 2_000
        const val ROTATION_GUARD_MS: Long = 300
        const val RESTORE_WINDOW_MS: Long = 10 * 60_000
    }
}
