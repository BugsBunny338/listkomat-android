package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.City
import cz.flipcom.listkomat.model.NearestCity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearestCityTest {

    private val cities = listOf(
        City("praha", "Praha", 50.075538, 14.437800, "90206", emptyList()),
        City("brno", "Brno", 49.195060, 16.606837, "90206", emptyList()),
    )

    @Test
    fun `standing in Brno picks Brno, well inside the default radius`() {
        val r = NearestCity.nearest(49.19, 16.60, cities)!!
        assertEquals("brno", r.city.key)
        assertTrue(r.distanceKm < 5)
        assertTrue(r.distanceKm <= NearestCity.MAX_DEFAULT_DISTANCE_KM)
    }

    @Test
    fun `Vienna is nearest to Brno but too far to default`() {
        val r = NearestCity.nearest(48.2082, 16.3738, cities)!!
        assertEquals("brno", r.city.key)
        assertTrue(r.distanceKm > NearestCity.MAX_DEFAULT_DISTANCE_KM)
    }

    @Test
    fun `no cities means no result`() {
        assertEquals(null, NearestCity.nearest(50.0, 14.0, emptyList()))
    }
}
