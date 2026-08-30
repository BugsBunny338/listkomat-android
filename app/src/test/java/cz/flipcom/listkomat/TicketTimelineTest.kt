package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.TicketTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketTimelineTest {

    @Test
    fun `make anchors validity after the confirmation buffer`() {
        val t = TicketTimeline.make(sentAtMs = 1_000_000, durationMinutes = 30)
        assertEquals(1_000_000, t.sentAtMs)
        assertEquals(1_000_000 + 120_000, t.validFromMs)
        assertEquals(t.validFromMs + 30 * 60_000, t.endMs)
        assertEquals(30 * 60_000, t.durationMs)
    }

    @Test
    fun `pending until validFrom, expired from endMs`() {
        val t = TicketTimeline.make(sentAtMs = 0, durationMinutes = 30)
        assertTrue(t.isPending(t.validFromMs - 1))
        assertFalse(t.isPending(t.validFromMs))
        assertFalse(t.isExpired(t.endMs - 1))
        assertTrue(t.isExpired(t.endMs))
    }

    @Test
    fun `confirmed re-anchors to now keeping the duration`() {
        val t = TicketTimeline.make(sentAtMs = 0, durationMinutes = 90)
        val now = 60_000L  // confirmation arrived 1 min in, before validFrom
        val c = t.confirmed(now)
        assertEquals(0, c.sentAtMs)
        assertEquals(now, c.validFromMs)
        assertEquals(t.durationMs, c.durationMs)
        assertFalse(c.isPending(now))
    }
}
