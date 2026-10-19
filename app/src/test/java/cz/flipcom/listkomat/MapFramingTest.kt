package cz.flipcom.listkomat

import cz.flipcom.listkomat.ui.INITIAL_LAT_SPAN
import cz.flipcom.listkomat.ui.RECENTER_LAT_SPAN
import cz.flipcom.listkomat.ui.REFERENCE_MAP_HEIGHT_DP
import cz.flipcom.listkomat.ui.STOP_LAT_SPAN
import cz.flipcom.listkomat.ui.zoomForLatSpan
import kotlin.math.cos
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFramingTest {

    private val prahaLat = 50.075538

    @Test
    fun `the zoom shows exactly the requested latitude span`() {
        val z = zoomForLatSpan(0.05, prahaLat, 764.0)
        // Degrees of latitude per dp at that zoom, times the height.
        val span = 764.0 * 360.0 * cos(Math.toRadians(prahaLat)) / (512.0 * 2.0.pow(z))
        assertEquals(0.05, span, 1e-9)
    }

    @Test
    fun `halving the span is one zoom level`() {
        assertEquals(1.0,
            zoomForLatSpan(0.025, prahaLat, 764.0) - zoomForLatSpan(0.05, prahaLat, 764.0), 1e-9)
    }

    @Test
    fun `stops appear after recenter but not at the first framing (iOS parity)`() {
        val stops = zoomForLatSpan(STOP_LAT_SPAN, prahaLat, REFERENCE_MAP_HEIGHT_DP)
        assertTrue(zoomForLatSpan(INITIAL_LAT_SPAN, prahaLat, REFERENCE_MAP_HEIGHT_DP) < stops)
        assertTrue(zoomForLatSpan(RECENTER_LAT_SPAN, prahaLat, REFERENCE_MAP_HEIGHT_DP) > stops)
    }
}
