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
    fun `live drops are capped at 100, falling drops never evicted`() {
        var s = RainState()
        repeat(4) { s = s.burst("🦄", now = 0, powerSave = false, random = random) } // 16+22+28+34
        assertEquals(MAX_LIVE_DROPS, s.drops.size)
        // Past the cap a tap adds nothing and removes nothing.
        val atCap = s.burst("🐸", now = 0, powerSave = false, random = random)
        assertEquals(s.drops, atCap.drops)
        // Once some have landed, a tap fills only the room they left.
        val later = atCap.burst("🐸", now = 3_400, powerSave = false, random = random)
        val stillFalling = s.drops.count { !it.isFinished(3_400) }
        assertEquals(
            minOf(RainState.burstSize(later.recent, powerSave = false), MAX_LIVE_DROPS - stillFalling),
            later.drops.count { it.emoji == "🐸" },
        )
        assertTrue(later.drops.size <= MAX_LIVE_DROPS)
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
        assertEquals(s.drops.count { !it.isFinished(2_000) }, mid.drops.size)
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
