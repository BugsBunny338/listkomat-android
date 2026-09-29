package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.PendingTicket
import cz.flipcom.listkomat.model.Purchase
import cz.flipcom.listkomat.model.PurchaseHandoff
import cz.flipcom.listkomat.model.PurchaseHandoff.Companion.HANDOFF_TIMEOUT_MS
import cz.flipcom.listkomat.model.PurchaseHandoff.Companion.RESTORE_WINDOW_MS
import cz.flipcom.listkomat.model.PurchaseHandoff.Companion.ROTATION_GUARD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseHandoffTest {

    private class FakeStore(var pending: PendingTicket? = null) : PurchaseHandoff.Store {
        override fun loadPending() = pending
        override fun savePending(pending: PendingTicket) { this.pending = pending }
        override fun clearPending() { pending = null }
    }

    private var clock = 1_000_000L
    private val store = FakeStore()
    private fun handoff() = PurchaseHandoff(store) { clock }

    private fun ticket(armedAt: Long = clock) = PendingTicket(
        cityKey = "praha", cityName = "Praha", ticketCode = "DPT42",
        durationMinutes = 30, priceKc = 42, armedAt = armedAt,
    )

    @Test
    fun `arming persists immediately and shows no dialog`() {
        val h = handoff()
        h.armed(ticket())
        assertTrue(h.state.value is Purchase.Armed)
        assertEquals(ticket(), store.pending)
    }

    @Test
    fun `armed then pause then resume asks`() {
        val h = handoff()
        h.armed(ticket())
        h.left()
        clock += 5_000
        h.returned()
        assertEquals(Purchase.AwaitingAnswer(ticket(armedAt = clock - 5_000)), h.state.value)
    }

    @Test
    fun `resume without a prior pause stays armed`() {
        val h = handoff()
        h.armed(ticket())
        h.returned()
        assertTrue(h.state.value is Purchase.Armed)
    }

    @Test
    fun `a pause-resume pair faster than the rotation guard stays armed`() {
        val h = handoff()
        h.armed(ticket())
        h.left()
        clock += ROTATION_GUARD_MS - 1
        h.returned()
        val armed = h.state.value as Purchase.Armed
        assertEquals(false, armed.sawPause)
    }

    @Test
    fun `armed and never paused within the timeout fails and clears persistence`() {
        val h = handoff()
        h.armed(ticket())
        assertEquals(HANDOFF_TIMEOUT_MS, h.handOffDeadlineIn())
        clock += HANDOFF_TIMEOUT_MS
        h.timedOut()
        assertEquals(Purchase.Failed, h.state.value)
        assertNull(store.pending)
    }

    @Test
    fun `timeout is a no-op once the SMS app took over`() {
        val h = handoff()
        h.armed(ticket())
        h.left()
        clock += HANDOFF_TIMEOUT_MS * 5
        h.timedOut()
        assertTrue(h.state.value is Purchase.Armed)
    }

    @Test
    fun `timeout checked early is a no-op`() {
        val h = handoff()
        h.armed(ticket())
        clock += HANDOFF_TIMEOUT_MS - 1
        h.timedOut()
        assertTrue(h.state.value is Purchase.Armed)
    }

    @Test
    fun `failed clears persistence`() {
        val h = handoff()
        h.armed(ticket())
        h.failed()
        assertEquals(Purchase.Failed, h.state.value)
        assertNull(store.pending)
    }

    @Test
    fun `persisted purchase younger than the window restores straight to asking`() {
        store.pending = ticket(armedAt = clock - RESTORE_WINDOW_MS + 1)
        val h = handoff()
        assertEquals(Purchase.AwaitingAnswer(store.pending!!), h.state.value)
    }

    @Test
    fun `persisted purchase older than the window is discarded`() {
        store.pending = ticket(armedAt = clock - RESTORE_WINDOW_MS)
        val h = handoff()
        assertNull(h.state.value)
        assertNull(store.pending)
    }

    @Test
    fun `answering returns the ticket and clears state and persistence`() {
        val h = handoff()
        h.armed(ticket())
        h.left(); clock += 1_000; h.returned()
        assertEquals("DPT42", h.resolved()?.ticketCode)
        assertNull(h.state.value)
        assertNull(store.pending)
    }

    @Test
    fun `resolving a failure returns nothing`() {
        val h = handoff()
        h.failed()
        assertNull(h.resolved())
        assertNull(h.state.value)
    }
}
