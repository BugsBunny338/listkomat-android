package cz.flipcom.listkomat.model

import kotlinx.serialization.Serializable

/**
 * The one ticket currently counting down, persisted so it survives process
 * death (the validity window outlives the app by design).
 *
 * The countdown starts when the user comes back from the SMS app and confirms
 * they sent the message — Android's ACTION_SENDTO gives no sent/cancelled
 * result at all (iOS at least reports "handed off"), so the user must also be
 * able to end it manually if the purchase failed.
 */
@Serializable
data class ActiveTicket(
    val cityKey: String,
    val cityName: String,
    val ticketLabel: String,     // e.g. "90 min"
    val priceKc: Int,
    val timeline: TicketTimeline,
)
