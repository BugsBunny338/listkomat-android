package cz.flipcom.listkomat

import androidx.compose.ui.graphics.Color
import cz.flipcom.listkomat.data.BrnoStream
import cz.flipcom.listkomat.data.BrnoStreamSnapshot
import cz.flipcom.listkomat.data.PragueVehicleSource
import cz.flipcom.listkomat.model.TransitPalette
import cz.flipcom.listkomat.model.VehicleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMapDecodersTest {

    @Test
    fun `prague decode filters stale and garbage, keeps fresh`() {
        val now = 1_788_200_000_000L
        val fresh = java.time.Instant.ofEpochMilli(now - 60_000).toString()
        val stale = java.time.Instant.ofEpochMilli(now - 400_000).toString()
        val body = """{"vehicles":[
          {"id":"a","lat":50.08,"lng":14.43,"brng":90,"line":"22","rt":0,"ts":"$fresh","dest":"Bílá Hora"},
          {"id":"b","lat":50.08,"lng":14.43,"line":"A","rt":1,"ts":"$stale"},
          {"id":"c","lat":10.0,"lng":14.43,"line":"9","rt":0,"ts":"$fresh"},
          {"id":"d","lat":50.08,"lng":14.43,"line":"9","rt":0,"ts":"not-a-date"}]}"""
        val vehicles = PragueVehicleSource.decode(body, nowMs = now)
        assertEquals(listOf("a"), vehicles.map { it.id })
        assertEquals(VehicleKind.TRAM, vehicles[0].kind)
        assertEquals("Bílá Hora", vehicles[0].destinationName)
    }

    @Test
    fun `prague kind mapping follows GTFS route types`() {
        assertEquals(VehicleKind.METRO, PragueVehicleSource.kind(1))
        assertEquals(VehicleKind.TRAIN, PragueVehicleSource.kind(2))
        assertEquals(VehicleKind.FERRY, PragueVehicleSource.kind(4))
        assertEquals(VehicleKind.TROLLEYBUS, PragueVehicleSource.kind(11))
        assertEquals(VehicleKind.BUS, PragueVehicleSource.kind(3))
        assertEquals(VehicleKind.BUS, PragueVehicleSource.kind(99))
    }

    @Test
    fun `brno stream message applies and inactive removes`() {
        val msg = """{"geometry":{"x":16.6,"y":49.2},"attributes":{"ID":42,"VType":0,
          "Bearing":123.0,"LineName":"1","IsInactive":"false","TimeUpdated":1788200000000,
          "FinalStopID":1001,"Delay":0.5}}"""
        val snap = BrnoStreamSnapshot()
        snap.apply(BrnoStream.decode(msg))
        val v = snap.vehicles(nowMs = 1_788_200_060_000)
        assertEquals(1, v.size)
        assertEquals(VehicleKind.TRAM, v[0].kind)
        assertEquals(1001, v[0].destinationId)
        val gone = msg.replace("\"false\"", "\"true\"")
        snap.apply(BrnoStream.decode(gone))
        assertTrue(snap.vehicles(nowMs = 1_788_200_060_000).isEmpty())
    }

    @Test
    fun `brno freshness filter drops old positions at read time`() {
        val msg = """{"geometry":{"x":16.6,"y":49.2},"attributes":{"ID":1,"VType":0,
          "Bearing":-1,"LineName":"1","IsInactive":"false","TimeUpdated":1788200000000}}"""
        val snap = BrnoStreamSnapshot()
        snap.apply(BrnoStream.decode(msg))
        assertEquals(1, snap.vehicles(nowMs = 1_788_200_000_000 + 119_000).size)
        assertEquals(0, snap.vehicles(nowMs = 1_788_200_000_000 + 121_000).size)
        assertNull(snap.vehicles(nowMs = 1_788_200_000_000).first().bearing)
    }

    @Test
    fun `metro lines keep official colours, yellow gets black glyph`() {
        assertEquals(Color(0xFF00A05A), TransitPalette.fill(VehicleKind.METRO, "A"))
        assertEquals(Color(0xFFFFCE00), TransitPalette.fill(VehicleKind.METRO, "B"))
        assertEquals(Color(0xFFE1252B), TransitPalette.fill(VehicleKind.METRO, "C"))
        assertEquals(Color(0xFFE0812B), TransitPalette.fill(VehicleKind.METRO, "D"))
        assertEquals(Color.Black, TransitPalette.style(VehicleKind.METRO, "B").glyph)
        assertEquals(Color.White, TransitPalette.style(VehicleKind.BUS, "99").glyph)
    }
}
