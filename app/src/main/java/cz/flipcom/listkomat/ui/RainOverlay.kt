package cz.flipcom.listkomat.ui

import android.content.Context
import android.graphics.Paint
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import cz.flipcom.listkomat.model.RainState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The mascot-rain easter egg, ported from the iOS RainLayer: tapping the
 * mascot drops a burst of falling emoji; rapid taps pile up for a heavier
 * downpour. The rules (burst size, cap, pruning, decay) live in [RainState].
 *
 * Drawing is kept cheap for low-end GPUs (android#1 item 3): each emoji is
 * rasterised once into a bitmap and every drop just draws that bitmap under
 * its transform; the overlay sits in its own graphics layer, so a frame only
 * re-records the rain, not the screen under it; time comes from the Compose
 * frame clock. Nothing is composed and no frame loop runs while idle, and
 * touches pass through (no pointer input). With animations turned off the rain
 * is skipped; battery saver makes it lighter.
 */
@Composable
fun RainOverlay(trigger: Int, mascot: String?) {
    val context = LocalContext.current
    var rain by remember { mutableStateOf(RainState()) }
    var now by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val idle by remember { derivedStateOf { rain.isIdle } }

    LaunchedEffect(trigger) {
        if (trigger == 0 || mascot == null) return@LaunchedEffect
        // Read per tap, not per frame: cheap, and follows a change made while open.
        if (!RainState.allowed(animatorDurationScale(context))) return@LaunchedEffect
        val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        // On the composable's scope, not this effect's: the next tap cancels this
        // effect, and must not drop a burst still waiting for its frame.
        scope.launch {
            // Spawn on the frame clock the drops are drawn with.
            val t = withFrameMillis { it }
            now = t
            rain = rain.burst(mascot, t, powerSave)
            delay(RainState.DECAY_MS)
            rain = rain.decay()
        }
    }
    // One long-lived frame loop that sleeps while idle and wakes through the
    // snapshot system. Keying an effect on `idle` instead could miss a burst that
    // lands on the very frame the last drop is pruned (idle flips false → true →
    // false between two compositions), leaving the rain frozen for good.
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { rain.isIdle }.first { !it }
            var frames = 0
            while (!rain.isIdle) {
                val t = withFrameMillis { it }
                now = t
                frames += 1
                if (frames % PRUNE_EVERY_FRAMES == 0 || rain.drops.all { it.isFinished(t) }) {
                    rain = rain.prune(t)
                }
            }
        }
    }
    // dp, not sp: a fixed 36 pt glyph like iOS, whatever the system font scale.
    val textPx = with(LocalDensity.current) { 36.dp.toPx() }
    // Above the idle return, so the bitmaps outlive a single shower.
    val glyphs = remember(textPx) { GlyphCache(textPx * RASTER_SCALE) }
    if (idle) return

    // `now` and `rain` are read only while drawing: a frame invalidates the
    // draw of this layer, never composition.
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val t = now
        val edge = 60.dp.toPx()   // iOS: starts 60 pt above the top, ends 60 pt below
        for (d in rain.drops) {
            val p = d.progress(t)
            if (p < 0f || p > 1f) continue
            val eased = p * p    // gentle gravity-like acceleration
            val y = -edge + (size.height + 2 * edge) * eased
            val glyph = glyphs[d.emoji]
            val s = d.scale / RASTER_SCALE
            withTransform({
                translate(d.x * size.width, y)
                rotate(d.spin * p, pivot = Offset.Zero)
                scale(s, s, pivot = Offset.Zero)
            }) {
                drawImage(glyph, topLeft = Offset(-glyph.width / 2f, -glyph.height / 2f))
            }
        }
    }
}

/** How often the frame loop drops landed emoji between bursts. */
private const val PRUNE_EVERY_FRAMES = 30

/** The largest drop scale: glyphs are rasterised at it, so they only ever shrink. */
private const val RASTER_SCALE = 1.3f

private fun animatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Each emoji rendered once to a bitmap, centred, with a little padding. */
private class GlyphCache(textPx: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        textSize = textPx
        textAlign = Paint.Align.CENTER
    }
    private val bitmaps = HashMap<String, ImageBitmap>()

    operator fun get(emoji: String): ImageBitmap = bitmaps.getOrPut(emoji) {
        val metrics = paint.fontMetrics
        val pad = 4
        val w = ceil(paint.measureText(emoji)).toInt().coerceAtLeast(1) + 2 * pad
        val h = ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1) + 2 * pad
        val bitmap = createBitmap(w, h)
        android.graphics.Canvas(bitmap).drawText(emoji, w / 2f, pad - metrics.ascent, paint)
        bitmap.asImageBitmap()
    }
}
