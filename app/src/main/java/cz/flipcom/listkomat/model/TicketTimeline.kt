package cz.flipcom.listkomat.model

import kotlinx.serialization.Serializable

/**
 * All ticket-validity date math, Android-free so it's unit-testable.
 * The ticket isn't valid until the operator's confirmation SMS (~2 min after
 * send), so validity is anchored to [validFromMs], not send. Mirrors the iOS
 * TicketTimeline exactly (same 120 s buffer).
 */
@Serializable
data class TicketTimeline(
    val sentAtMs: Long,
    val validFromMs: Long,
    val endMs: Long,
) {
    /** Length of the valid window (invariant: endMs − validFromMs). */
    val durationMs: Long get() = endMs - validFromMs

    /** Before this, the ticket shows as "waiting for confirmation", not counting down. */
    fun isPending(nowMs: Long): Boolean = nowMs < validFromMs

    fun isExpired(nowMs: Long): Boolean = nowMs >= endMs

    /** Re-anchor to now (confirmation arrived), keeping the same duration. */
    fun confirmed(nowMs: Long): TicketTimeline =
        TicketTimeline(sentAtMs = sentAtMs, validFromMs = nowMs, endMs = nowMs + durationMs)

    companion object {
        /** Estimated gap between SMS send and the confirmation SMS. */
        const val BUFFER_MS: Long = 120_000

        fun make(sentAtMs: Long, durationMinutes: Int, bufferMs: Long = BUFFER_MS): TicketTimeline {
            val validFrom = sentAtMs + bufferMs
            return TicketTimeline(
                sentAtMs = sentAtMs,
                validFromMs = validFrom,
                endMs = validFrom + durationMinutes * 60_000L,
            )
        }
    }
}
