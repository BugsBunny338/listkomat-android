package cz.flipcom.listkomat.model

import android.content.Context
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
    /** Localized at purchase; only the fallback for a city the catalog no longer has. */
    val cityName: String,
    val priceKc: Int,
    val timeline: TicketTimeline,
    /** The user swiped the ongoing notification away; don't repost it. */
    val ongoingDismissed: Boolean = false,
)

/**
 * "Praha · 30 minut" in the current app language. Resolved on every render,
 * never stored: a label formatted at purchase stays in the old language after a
 * runtime language switch (android#31).
 */
fun ActiveTicket.title(context: Context, catalog: TicketCatalog): String {
    val language = context.resources.configuration.locales[0].language
    val city = catalog.cities.firstOrNull { it.key == cityKey }?.name(language) ?: cityName
    val minutes = (timeline.durationMs / 60_000).toInt()
    return "$city · ${DurationFormat.format(context, minutes)}"
}
