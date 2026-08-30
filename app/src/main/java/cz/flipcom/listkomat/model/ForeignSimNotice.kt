package cz.flipcom.listkomat.model

/**
 * Decides whether to show the "you need a Czech SIM" note on the purchase
 * screen (iOS issue #15, ported).
 *
 * Premium-SMS tickets only work from a Czech operator's SIM and the failure is
 * invisible: the SMS "sends" fine from a roaming SIM, the ticket never
 * arrives, and the app doesn't read incoming SMS. The only defence is telling
 * people up front.
 *
 * Unlike iOS (where the SIM country is unreadable and the UI language +
 * storefront stand in), Android exposes TelephonyManager.simCountryIso with no
 * permission — so the real signal wins when present:
 *  - Czech SIM detected → never show (warning them is noise);
 *  - foreign SIM detected → always show, even in Czech;
 *  - no SIM signal → the broad iOS-style language heuristic.
 * It informs, it never blocks.
 */
object ForeignSimNotice {

    fun shouldShow(uiLanguage: String?, simCountryIso: String?, dismissed: Boolean): Boolean {
        if (dismissed) return false
        val sim = simCountryIso?.trim()?.lowercase().orEmpty()
        if (sim == "cz") return false
        if (sim.isNotEmpty()) return true
        val base = uiLanguage?.split('-')?.firstOrNull().orEmpty()
        return base.isNotEmpty() && !base.equals("cs", ignoreCase = true)
    }
}
