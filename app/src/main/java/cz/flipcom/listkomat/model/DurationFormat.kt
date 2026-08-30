package cz.flipcom.listkomat.model

import android.content.Context
import cz.flipcom.listkomat.R

/**
 * The cross-client duration contract (see listkomat-catalog README):
 * durationMinutes is authoritative — the catalog's `duration` string is legacy
 * and must not be displayed. Hours iff minutes >= 120 and divisible by 60,
 * else minutes (1440 → "24 hodin", but 60 → "60 minut" — operators sell sixty
 * minutes, not one hour). Czech plural forms are ACCUSATIVE ("na 1 hodinu"),
 * which is why this goes through plurals.xml and never a generic formatter.
 */
object DurationFormat {

    /** Unit selection, kept pure for unit tests. */
    data class Parts(val value: Int, val isHours: Boolean)

    fun parts(minutes: Int): Parts =
        if (minutes >= 120 && minutes % 60 == 0) Parts(minutes / 60, isHours = true)
        else Parts(minutes, isHours = false)

    fun format(context: Context, minutes: Int): String {
        val p = parts(minutes)
        val res = if (p.isHours) R.plurals.duration_hours else R.plurals.duration_minutes
        return context.resources.getQuantityString(res, p.value, p.value)
    }
}
