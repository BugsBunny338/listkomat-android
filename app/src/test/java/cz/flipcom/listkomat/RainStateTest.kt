package cz.flipcom.listkomat

import cz.flipcom.listkomat.model.RainState
import cz.flipcom.listkomat.model.RainState.Companion.MAX_LIVE_DROPS
import cz.flipcom.listkomat.model.RainState.Companion.POWER_SAVE_BURST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RainStateTest {

    private val random = Random(42)

    @Test
    fun `burst size follows the iOS formula`() {
        assertEquals(16, RainState.burstSize(1, powerSave = false))
        assertEquals(22, RainState.burstSize(2, powerSave = false))
        assertEquals(58, RainState.burstSize(8, powerSave = false))
        assertEquals(60, RainState.burstSize(9, powerSave = false))
        assertEquals(60, RainState.burstSize(50, powerSave = false))
    }

    @Test
    fun `battery saver caps a burst at 15`() {
        assertEquals(POWER_SAVE_BURST, RainState.burstSize(1, powerSave = true))
        assertEquals(POWER_SAVE_BURST, RainState.burstSize(9, powerSave = true))
        val s = RainState().burst("🦄", now = 0, powerSave = true, random = random)
        assertEquals(POWER_SAVE_BURST, s.drops.size)
    }

    @Test
    fun `animations off means no rain`() {
        assertFalse(RainState.allowed(0f))
        assertTrue(RainState.allowed(0.5f))
        assertTrue(RainState.allowed(1f))
    }

    @Test
    fun `drops keep the shared ranges`() {
        var s = RainState()
        repeat(5) { s = s.burst("🦄", now = 1_000, powerSave = false, random = random) }
        for (d in s.drops) {
            assertTrue(d.x in 0.03f..0.97f)
            assertTrue(d.scale in 0.6f..1.3f)
            assertTrue(d.spin in -220f..220f)
            assertTrue(d.durationMs in 1_600L..2_800L)
            assertTrue(d.startAt in 1_000L..1_500L)
            assertEquals("🦄", d.emoji)
        }
    }

    @Test
    fun `each tap adds to recent`() {
        var s = RainState()
        repeat(3) { s = s.burst("🦄", now = 0, powerSave = false, random = random) }
        assertEquals(3, s.recent)
        // 16 + 22 + 28
        assertEquals(66, s.drops.size)
    }

    @Test
    fun `live drops are capped at 100, newest kept`() {
        var s = RainState()
        repeat(10) { s = s.burst("🦄", now = 0, powerSave = false, random = random) }
        assertEquals(MAX_LIVE_DROPS, s.drops.size)
        val last = s.burst("🐸", now = 0, powerSave = false, random = random)
        assertEquals(MAX_LIVE_DROPS, last.drops.size)
        assertEquals(60, last.drops.count { it.emoji == "🐸" })
        assertEquals("🐸", last.drops.last().emoji)
    }

    @Test
    fun `a burst drops the ones that have landed`() {
        val first = RainState().burst("🦄", now = 0, powerSave = false, random = random)
        // Every first-burst drop ends by 500 + 2800 ms.
        val second = first.burst("🐸", now = 4_000, powerSave = false, random = random)
        assertTrue(second.drops.all { it.emoji == "🐸" })
        assertEquals(22, second.drops.size)
    }

    @Test
    fun `prune keeps falling drops and empties when all have landed`() {
        val s = RainState().burst("🦄", now = 0, powerSave = false, random = random)
        assertSame(s, s.prune(0))
        val mid = s.prune(2_000)
        assertTrue(mid.drops.all { !it.isFinished(2_000) })
        assertTrue(s.prune(3_301).isIdle)
        assertEquals(s.recent, s.prune(3_301).recent)
    }

    @Test
    fun `recent decays one per tap and stops at zero`() {
        var s = RainState()
        repeat(2) { s = s.burst("🦄", now = 0, powerSave = false, random = random) }
        s = s.decay()
        assertEquals(1, s.recent)
        s = s.decay().decay()
        assertEquals(0, s.recent)
        // After the decay, the next burst is light again.
        assertEquals(16, s.burst("🦄", now = 10_000, powerSave = false, random = random).drops.size)
    }

    @Test
    fun `progress runs 0 to 1 over the drop's duration`() {
        val d = RainState().burst("🦄", now = 0, powerSave = false, random = random).drops.first()
        assertEquals(0f, d.progress(d.startAt), 0f)
        assertEquals(1f, d.progress(d.startAt + d.durationMs), 0f)
        assertFalse(d.isFinished(d.startAt + d.durationMs))
        assertTrue(d.isFinished(d.startAt + d.durationMs + 1))
    }
}
