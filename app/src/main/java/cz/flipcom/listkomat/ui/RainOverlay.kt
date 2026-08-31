package cz.flipcom.listkomat.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.android.awaitFrame
import kotlin.random.Random

/**
 * The mascot-rain easter egg, ported from the iOS RainLayer: tapping the
 * mascot drops a burst of falling emoji; rapid taps pile up for a heavier
 * downpour. Positions derive from spawn time + elapsed time, so new bursts
 * never interrupt drops already falling. No frame loop runs while idle.
 */
private data class RainDrop(
    val emoji: String,
    val x: Float,          // 0..1 fraction of width
    val scale: Float,
    val spin: Float,       // total degrees over the fall
    val durationMs: Long,
    val startAt: Long,     // uptimeMillis when this drop begins falling
)

@Composable
fun RainOverlay(trigger: Int, mascot: String?) {
    var drops by remember { mutableStateOf(listOf<RainDrop>()) }
    var recent by remember { mutableStateOf(0) }
    var now by remember { mutableLongStateOf(0L) }

    LaunchedEffect(trigger) {
        if (trigger == 0 || mascot == null) return@LaunchedEffect
        val t = android.os.SystemClock.uptimeMillis()
        recent += 1
        val count = minOf(10 + recent * 6, 60)
        drops = drops + List(count) {
            RainDrop(
                emoji = mascot,
                x = Random.nextFloat() * 0.94f + 0.03f,
                scale = Random.nextFloat() * 0.7f + 0.6f,
                spin = Random.nextFloat() * 440f - 220f,
                durationMs = (Random.nextFloat() * 1200 + 1600).toLong(),
                startAt = t + (Random.nextFloat() * 500).toLong(),
            )
        }
    }
    // One frame loop while anything is falling; drains and stops when done.
    LaunchedEffect(drops.isNotEmpty()) {
        while (drops.isNotEmpty()) {
            awaitFrame()
            val t = android.os.SystemClock.uptimeMillis()
            now = t
            if (drops.all { t > it.startAt + it.durationMs }) {
                drops = emptyList()
                recent = 0
            }
        }
    }
    if (drops.isEmpty()) return
    val textPx = with(LocalDensity.current) { 36.sp.toPx() }
    val paint = remember(textPx) { Paint().apply { textSize = textPx; textAlign = Paint.Align.CENTER } }
    Canvas(Modifier.fillMaxSize()) {
        val t = now
        for (d in drops) {
            val p = (t - d.startAt).toFloat() / d.durationMs
            if (p < 0f || p > 1f) continue
            val eased = p * p    // gentle gravity-like acceleration
            val y = -60f + (size.height + 120f) * eased
            drawContext.canvas.nativeCanvas.apply {
                save()
                translate(d.x * size.width, y)
                rotate(d.spin * p)
                scale(d.scale, d.scale)
                drawText(d.emoji, 0f, 0f, paint)
                restore()
            }
        }
    }
}
