package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.CitySelection
import cz.flipcom.listkomat.model.CitySelection.MANUAL_GRACE_MS
import cz.flipcom.listkomat.model.LocationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CitySelectionTest {

    private val now = 1_800_000_000_000L
    private val inPrague = LocationState.Located("praha", 2.0)
    private val inSaltLakeCity = LocationState.Located("brno", 9_200.0)

    // --- which city is shown ---------------------------------------------

    @Test
    fun `GPS wins over the persisted fallback`() {
        assertEquals("praha", CitySelection.cityKey(manual = null, location = inPrague, fallback = "brno"))
    }

    @Test
    fun `a manual pick this session wins over GPS`() {
        assertEquals("brno", CitySelection.cityKey(manual = "brno", location = inPrague, fallback = "brno"))
    }

    @Test
    fun `too far from every city falls back to the last pick`() {
        assertEquals("brno", CitySelection.cityKey(manual = null, location = inSaltLakeCity, fallback = "brno"))
    }

    @Test
    fun `too far with no fallback shows nothing, so the too-far empty state appears`() {
        assertNull(CitySelection.cityKey(manual = null, location = inSaltLakeCity, fallback = null))
    }

    @Test
    fun `denied, idle or still searching fall back to the last pick`() {
        for (state in listOf(LocationState.Denied, LocationState.Idle, LocationState.Searching)) {
            assertEquals(state.toString(), "ostrava",
                CitySelection.cityKey(manual = null, location = state, fallback = "ostrava"))
        }
    }

    @Test
    fun `denied, idle or still searching with no fallback show nothing`() {
        for (state in listOf(LocationState.Denied, LocationState.Idle, LocationState.Searching)) {
            assertNull(state.toString(), CitySelection.cityKey(manual = null, location = state, fallback = null))
        }
    }

    @Test
    fun `the 100 km limit itself still counts as nearby`() {
        val edge = LocationState.Located("brno", 100.0)
        assertEquals("brno", CitySelection.gpsCityKey(edge))
        assertNull(CitySelection.gpsCityKey(LocationState.Located("brno", 100.1)))
    }

    // --- the short hold for a first fix, so the list doesn't swap in place --

    @Test
    fun `while the first fix is awaited the fallback is held back`() {
        assertNull(CitySelection.cityKey(manual = null, location = LocationState.Searching,
            fallback = "brno", awaitingFirstFix = true))
    }

    @Test
    fun `once the hold is over the fallback shows`() {
        assertEquals("brno", CitySelection.cityKey(manual = null, location = LocationState.Searching,
            fallback = "brno", awaitingFirstFix = false))
    }

    @Test
    fun `a manual pick is never held back`() {
        assertEquals("brno", CitySelection.cityKey(manual = "brno", location = LocationState.Searching,
            fallback = "brno", awaitingFirstFix = true))
    }

    @Test
    fun `a fix that arrives during the hold shows straight away`() {
        assertEquals("praha", CitySelection.cityKey(manual = null, location = inPrague,
            fallback = "brno", awaitingFirstFix = true))
    }

    @Test
    fun `a too-far fix during the hold still holds the fallback back`() {
        // The view model ends the hold on any answer; this only covers the gap.
        assertNull(CitySelection.cityKey(manual = null, location = inSaltLakeCity,
            fallback = "brno", awaitingFirstFix = true))
    }

    // --- a recent last-known fix counts as GPS delivering -----------------

    @Test
    fun `a last-known fix minutes old is used right away`() {
        assertEquals(true, CitySelection.lastKnownIsFresh(ageMs = 6 * 60_000L))
    }

    @Test
    fun `a last-known fix at the age limit is still used`() {
        assertEquals(true, CitySelection.lastKnownIsFresh(ageMs = CitySelection.LAST_KNOWN_MAX_AGE_MS))
    }

    @Test
    fun `an older last-known fix waits for a current one first`() {
        assertEquals(false, CitySelection.lastKnownIsFresh(ageMs = CitySelection.LAST_KNOWN_MAX_AGE_MS + 1))
    }

    @Test
    fun `a last-known fix from the future (clock skew) is not trusted as fresh`() {
        assertEquals(false, CitySelection.lastKnownIsFresh(ageMs = -1))
    }

    // --- does the manual pick survive a return to the app -----------------

    @Test
    fun `a pick made moments ago survives a return to the app`() {
        assertEquals("brno", CitySelection.overrideAfterStart(fallback = "brno", lastSeenAtMs = now - 10_000, nowMs = now))
    }

    @Test
    fun `a pick just inside the grace window survives`() {
        assertEquals("brno",
            CitySelection.overrideAfterStart(fallback = "brno", lastSeenAtMs = now - MANUAL_GRACE_MS + 1, nowMs = now))
    }

    @Test
    fun `a pick older than the grace window no longer overrides GPS`() {
        assertNull(CitySelection.overrideAfterStart(fallback = "brno", lastSeenAtMs = now - MANUAL_GRACE_MS, nowMs = now))
    }

    @Test
    fun `a pick from before this rule (no timestamp) never overrides GPS`() {
        assertNull(CitySelection.overrideAfterStart(fallback = "brno", lastSeenAtMs = null, nowMs = now))
    }

    @Test
    fun `a pick in the future (clock moved back) does not override GPS`() {
        assertNull(CitySelection.overrideAfterStart(fallback = "brno", lastSeenAtMs = now + 60_000, nowMs = now))
    }

    @Test
    fun `no pick at all means no override`() {
        assertNull(CitySelection.overrideAfterStart(fallback = null, lastSeenAtMs = null, nowMs = now))
    }

    // --- the brief's device walkthrough, end to end ------------------------

    @Test
    fun `pick Brno abroad, come back after the grace window, still Brno`() {
        val manual = CitySelection.overrideAfterStart("brno", lastSeenAtMs = now - 3_600_000, nowMs = now)
        assertEquals("brno", CitySelection.cityKey(manual, inSaltLakeCity, fallback = "brno"))
    }

    @Test
    fun `pick Brno, then come back in Prague after the grace window, Prague wins`() {
        val manual = CitySelection.overrideAfterStart("brno", lastSeenAtMs = now - 3_600_000, nowMs = now)
        assertEquals("praha", CitySelection.cityKey(manual, inPrague, fallback = "brno"))
    }

    @Test
    fun `pick Brno in Prague, answer a text and come back, still Brno`() {
        val manual = CitySelection.overrideAfterStart("brno", lastSeenAtMs = now - 60_000, nowMs = now)
        assertEquals("brno", CitySelection.cityKey(manual, inPrague, fallback = "brno"))
    }

    @Test
    fun `pick Brno, browse for ten minutes, send the ticket SMS and come back, still Brno`() {
        // The view model stamps lastSeenAtMs when the app goes to the background.
        val leftForSms = now - 40_000
        val manual = CitySelection.overrideAfterStart("brno", lastSeenAtMs = leftForSms, nowMs = now)
        assertEquals("brno", CitySelection.cityKey(manual, inPrague, fallback = "brno"))
    }

    @Test
    fun `pick Brno, revoke location, Brno without an empty state`() {
        val manual = CitySelection.overrideAfterStart("brno", lastSeenAtMs = now - 3_600_000, nowMs = now)
        assertEquals("brno", CitySelection.cityKey(manual, LocationState.Denied, fallback = "brno"))
    }
}
