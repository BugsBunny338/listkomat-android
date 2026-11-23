package cz.flipcom.listkomat.model

import kotlin.random.Random

/** One falling emoji. Its position derives from [startAt] + elapsed time, so
 *  new bursts never interrupt drops already falling. Times are frame-clock
 *  milliseconds (the same clock the overlay draws with). */
data class RainDrop(
    val emoji: String,
    val x: Float,          // 0..1 fraction of width
    val scale: Float,
    val spin: Float,       // total degrees over the fall
    val durationMs: Long,
    val startAt: Long,     // when this drop begins falling
) {
    /** 0..1 while falling, outside that range before and after. */
    fun progress(now: Long): Float = (now - startAt).toFloat() / durationMs
    fun isFinished(now: Long): Boolean = now > startAt + durationMs
}

/**
 * The mascot rain without Compose, so its rules are unit-testable. The burst
 * math is the iOS RainLayer's (keep both identical): `min(10 + recent * 6, 60)`
 * drops, x 0.03..0.97, scale 0.6..1.3, spin ±220°, 1.6–2.8 s, start staggered
 * 0..0.5 s. On top of that, both platforms prune finished drops on every
 * burst, cap the live drops at [MAX_LIVE_DROPS], and decay [recent] by one
 * [DECAY_MS] after each tap (the caller schedules [decay]).
 */
data class RainState(
    val drops: List<RainDrop> = emptyList(),
    val recent: Int = 0,
) {
    val isIdle: Boolean get() = drops.isEmpty()

    /** A new burst of [emoji] at [now]: finished drops go, the newest
     *  [MAX_LIVE_DROPS] stay. Battery saver caps the burst at [POWER_SAVE_BURST]. */
    fun burst(emoji: String, now: Long, powerSave: Boolean, random: Random = Random.Default): RainState {
        val nextRecent = recent + 1
        val count = burstSize(nextRecent, powerSave)
        val fresh = List(count) {
            RainDrop(
                emoji = emoji,
                x = random.nextFloat() * 0.94f + 0.03f,
                scale = random.nextFloat() * 0.7f + 0.6f,
                spin = random.nextFloat() * 440f - 220f,
                durationMs = (random.nextFloat() * 1200 + 1600).toLong(),
                startAt = now + (random.nextFloat() * 500).toLong(),
            )
        }
        val live = drops.filterNot { it.isFinished(now) } + fresh
        return RainState(live.takeLast(MAX_LIVE_DROPS), nextRecent)
    }

    /** Drops that have landed are gone; the same object when nothing changed. */
    fun prune(now: Long): RainState {
        if (drops.none { it.isFinished(now) }) return this
        return copy(drops = drops.filterNot { it.isFinished(now) })
    }

    /** One tap's weight wears off ([DECAY_MS] after it), floored at 0. */
    fun decay(): RainState = if (recent > 0) copy(recent = recent - 1) else this

    companion object {
        const val MAX_LIVE_DROPS = 100
        const val MAX_BURST = 60
        const val POWER_SAVE_BURST = 15
        const val DECAY_MS = 2_000L

        /** [recent] already counts this tap. */
        fun burstSize(recent: Int, powerSave: Boolean): Int {
            val size = minOf(10 + recent * 6, MAX_BURST)
            return if (powerSave) minOf(size, POWER_SAVE_BURST) else size
        }

        /** Animations off in the developer/accessibility settings (animator
         *  duration scale 0, "Remove animations"): no rain at all. */
        fun allowed(animatorDurationScale: Float): Boolean = animatorDurationScale > 0f
    }
}
