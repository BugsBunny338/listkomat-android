package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.City
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** City.showsLiveMap decides both the "Živá mapa" button and, when false, the
 *  footer's "no live map for this city yet" line (P1 item 3). */
class LiveMapAvailabilityTest {

    private fun city(key: String, hasLiveMap: Boolean? = null, liveMapDisabled: Boolean? = null) =
        City(key = key, name = key, lat = 0.0, lng = 0.0, smsNumber = "90206", tickets = emptyList(),
            hasLiveMap = hasLiveMap, liveMapDisabled = liveMapDisabled)

    @Test
    fun `Prague has a map without the catalog flag`() {
        assertTrue(city("praha").showsLiveMap)
    }

    @Test
    fun `Brno has a map only when the catalog says so`() {
        assertTrue(city("brno", hasLiveMap = true).showsLiveMap)
        assertFalse(city("brno").showsLiveMap)
        assertFalse(city("brno", hasLiveMap = false).showsLiveMap)
    }

    @Test
    fun `map-less cities get the note`() {
        assertFalse(city("ostrava").showsLiveMap)
        assertFalse(city("hradec").showsLiveMap)
    }

    @Test
    fun `the kill switch turns the map off for Prague and Brno`() {
        assertFalse(city("praha", liveMapDisabled = true).showsLiveMap)
        assertFalse(city("brno", hasLiveMap = true, liveMapDisabled = true).showsLiveMap)
        assertTrue(city("brno", hasLiveMap = true, liveMapDisabled = false).showsLiveMap)
    }
}
