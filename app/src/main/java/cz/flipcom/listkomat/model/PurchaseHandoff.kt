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
     *  the user to come back. [pausedAt] feeds the rotation guard. */
    data class Armed(
        val pending: PendingTicket,
        val sawPause: Boolean = false,
        val pausedAt: Long? = null,
    ) : Purchase

    /** Back from the SMS app — ask "did you send it?". */
    data class AwaitingAnswer(val pending: PendingTicket) : Purchase

    /** The SMS app never opened (none installed, or it didn't take over). */
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
        store.loadPending()?.let { pending ->
            if (now() - pending.armedAt < RESTORE_WINDOW_MS) {
                _state.value = Purchase.AwaitingAnswer(pending)
            } else {
                store.clearPending()
            }
        }
    }

    /** Called just before starting the SMS activity. */
    fun armed(pending: PendingTicket) {
        store.savePending(pending)
        _state.value = Purchase.Armed(pending)
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
            Purchase.AwaitingAnswer(armed.pending)
        }
    }

    /** Milliseconds until [timedOut] should be checked, or null when not armed. */
    fun handOffDeadlineIn(): Long? {
        val armed = _state.value as? Purchase.Armed ?: return null
        return (armed.pending.armedAt + HANDOFF_TIMEOUT_MS - now()).coerceAtLeast(0)
    }

    /** The fallback: armed but never paused within [HANDOFF_TIMEOUT_MS] means
     *  the SMS app didn't take over. */
    fun timedOut() {
        val armed = _state.value as? Purchase.Armed ?: return
        if (armed.sawPause || now() - armed.pending.armedAt < HANDOFF_TIMEOUT_MS) return
        failed()
    }

    /** No SMS app could be started at all. */
    fun failed() {
        store.clearPending()
        _state.value = Purchase.Failed
    }

    /** The user answered (yes or no) or acknowledged a failure. Returns the
     *  pending ticket that was being asked about, if any. */
    fun resolved(): PendingTicket? {
        val pending = (_state.value as? Purchase.AwaitingAnswer)?.pending
        store.clearPending()
        _state.value = null
        return pending
    }

    companion object {
        /** Cold-starting Messages on a Galaxy A14 is slow; 2 s leaves margin. */
        const val HANDOFF_TIMEOUT_MS: Long = 2_000
        const val ROTATION_GUARD_MS: Long = 300
        const val RESTORE_WINDOW_MS: Long = 10 * 60_000
    }
}
