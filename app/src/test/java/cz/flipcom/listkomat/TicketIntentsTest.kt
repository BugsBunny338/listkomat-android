package cz.flipcom.listkomat

import cz.flipcom.listkomat.notify.TicketActions
import cz.flipcom.listkomat.notify.TicketIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketIntentsTest {

    private class Recorder : TicketActions {
        val calls = mutableListOf<String>()
        override fun confirmNow() { calls += "confirmNow" }
        override fun end() { calls += "end" }
        override fun flipped() { calls += "flipped" }
        override fun expired() { calls += "expired" }
    }

    private fun routed(action: String?): List<String> =
        Recorder().also { TicketIntents.route(action, it) }.calls

    @Test
    fun `notification buttons route to confirm and end`() {
        assertEquals(listOf("confirmNow"), routed(TicketIntents.CONFIRM_NOW))
        assertEquals(listOf("end"), routed(TicketIntents.END))
    }

    @Test
    fun `flip alarm routes to flipped`() {
        assertEquals(listOf("flipped"), routed(TicketIntents.FLIP))
    }

    @Test
    fun `expiry alarm carries no action, like the alarms build 5 armed`() {
        assertEquals(listOf("expired"), routed(null))
    }

    @Test
    fun `unknown actions are ignored`() {
        val r = Recorder()
        assertFalse(TicketIntents.route("android.intent.action.BOOT_COMPLETED", r))
        assertFalse(TicketIntents.route("something.else", r))
        assertTrue(r.calls.isEmpty())
    }
}
