package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.TicketCatalog
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parses a real-shaped snippet of the shared tickets.json contract. The point
 * is future-proofing: unknown keys must be ignored, i18n must fall back to
 * the Czech originals.
 */
class CatalogParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sample = """
    {
      "version": 5,
      "updatedAt": "2026-08-30",
      "someFutureKey": true,
      "cities": [
        {
          "key": "praha", "name": "Praha", "lat": 50.07, "lng": 14.43, "smsNumber": "90206",
          "i18n": { "en": { "name": "Prague" } },
          "futureCityKey": {"nested": 1},
          "tickets": [
            { "code": "DPT42", "duration": "30 min", "durationMinutes": 30, "priceKc": 42 },
            { "code": "DPO70", "duration": "70 min", "durationMinutes": 70, "priceKc": 38,
              "note": "o víkendu 90 min", "i18n": { "en": { "note": "90 min on weekends" } } }
          ]
        }
      ]
    }
    """

    @Test
    fun `parses the shared contract, ignoring unknown keys`() {
        val catalog = json.decodeFromString<TicketCatalog>(sample)
        assertEquals(5, catalog.version)
        val praha = catalog.cities.single()
        assertEquals("90206", praha.smsNumber)
        assertEquals(2, praha.tickets.size)
        assertEquals(42, praha.tickets[0].priceKc)
        assertNull(praha.tickets[0].note)
    }

    @Test
    fun `i18n overrides apply for en and fall back to Czech`() {
        val praha = json.decodeFromString<TicketCatalog>(sample).cities.single()
        assertEquals("Prague", praha.name("en"))
        assertEquals("Praha", praha.name("cs"))
        assertEquals("Praha", praha.name("de"))  // no override → Czech original
        assertEquals("90 min on weekends", praha.tickets[1].note("en"))
        assertEquals("o víkendu 90 min", praha.tickets[1].note("cs"))
        assertNull(praha.tickets[0].note("en"))
    }

    @Test
    fun `blank i18n override falls back to Czech, never blank`() {
        val blank = """{ "version": 1, "updatedAt": "x", "cities": [
          { "key": "praha", "name": "Praha", "lat": 1, "lng": 2, "smsNumber": "90206",
            "i18n": { "en": { "name": "" } }, "tickets": [] } ] }"""
        val praha = json.decodeFromString<TicketCatalog>(blank).cities.single()
        assertEquals("Praha", praha.name("en"))
    }
}
