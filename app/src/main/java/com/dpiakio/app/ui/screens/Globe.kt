package com.dpiakio.app.ui.screens

import android.graphics.Paint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import com.dpiakio.app.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Unit-sphere points on latitude rings, computed once. */
private object GlobePoints {
    val xs: FloatArray
    val ys: FloatArray
    val zs: FloatArray
    val count: Int

    init {
        val x = ArrayList<Float>(2400); val y = ArrayList<Float>(2400); val z = ArrayList<Float>(2400)
        var lat = -84.0
        while (lat <= 84.0) {
            val r = cos(lat * PI / 180)
            val n = max(6, (r * 96).roundToInt())
            // stagger rings so dots don't form vertical columns
            val phase = if (((lat + 84) / 3.5).roundToInt() % 2 == 0) 0.0 else PI / n
            for (i in 0 until n) {
                val lon = 2 * PI * i / n + phase
                x += (r * cos(lon)).toFloat(); z += (r * sin(lon)).toFloat(); y += sin(lat * PI / 180).toFloat()
            }
            lat += 3.5
        }
        count = x.size
        xs = x.toFloatArray(); ys = y.toFloatArray(); zs = z.toFloatArray()
    }
}

private const val BUCKETS = 6

/**
 * Glowing dotted planet. Rotation is driven by a frame clock and read only in the draw
 * phase, so the composable never recomposes while spinning.
 */
@Composable
fun DottedGlobe(active: Boolean, modifier: Modifier = Modifier) {
    val angle = remember { mutableFloatStateOf(0.6f) }
    val energy = remember { mutableFloatStateOf(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                // Slow rotation: publishing ~30 updates/s is visually identical to 120 Hz
                // and lets the render thread idle 3 frames out of 4.
                if (last == 0L) last = t
                else if (t - last >= 32_000_000L) {
                    val dt = (t - last) / 1e9f
                    val target = if (active) 1f else 0f
                    energy.floatValue += (target - energy.floatValue) * (dt * 3f).coerceAtMost(1f)
                    angle.floatValue += dt * (0.05f + 0.20f * energy.floatValue)
                    last = t
                }
            }
            if (!active && energy.floatValue < 0.01f) { energy.floatValue = 0f; break }
        }
    }
    val dotPx = with(LocalDensity.current) { 2.1f * density }
    val buffers = remember { Array(BUCKETS) { FloatArray(GlobePoints.count * 2) } }
    val counts = remember { IntArray(BUCKETS) }
    val paints = remember {
        Array(BUCKETS) { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    }

    Spacer(modifier.drawWithCache {
        // Geometry and gradients depend only on size: built once, reused every frame.
        val r = size.width * 0.50f
        val c = Offset(size.width / 2, 96.dp.toPx() + r)
        val glowR = r * 1.35f
        val glowIdle = Brush.radialGradient(
            0f to Palette.Violet.copy(alpha = 0.10f), 0.75f to Palette.Indigo.copy(alpha = 0.10f), 1f to Color.Transparent,
            center = c, radius = glowR)
        val glowOn = Brush.radialGradient(
            0f to Palette.Violet.copy(alpha = 0.35f), 0.75f to Palette.Indigo.copy(alpha = 0.30f), 1f to Color.Transparent,
            center = c, radius = glowR)
        val rim = Brush.verticalGradient(
            0f to Palette.Lavender.copy(alpha = 0.8f), 0.25f to Palette.Violet.copy(alpha = 0.3f), 0.5f to Color.Transparent,
            startY = c.y - r, endY = c.y + r)
        val rimStroke = Stroke(width = dotPx * 1.4f)
        val tilt = 0.38f; val ct = cos(tilt); val st = sin(tilt)

        onDrawBehind {
            val e = energy.floatValue
            val a = angle.floatValue
            drawCircle(glowIdle, radius = glowR, center = c, alpha = 1f - e)
            if (e > 0.01f) drawCircle(glowOn, radius = glowR, center = c, alpha = e)

            val ca = cos(a); val sa = sin(a)
            counts.fill(0)
            val xs = GlobePoints.xs; val ys = GlobePoints.ys; val zs = GlobePoints.zs
            for (i in 0 until GlobePoints.count) {
                val x1 = xs[i] * ca + zs[i] * sa
                val z1 = -xs[i] * sa + zs[i] * ca
                val y2 = ys[i] * ct - z1 * st
                val z2 = ys[i] * st + z1 * ct
                if (z2 < -0.05f) continue
                val b = ((z2 + 0.05f) / 1.05f * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
                val k = counts[b] * 2
                buffers[b][k] = c.x + x1 * r
                buffers[b][k + 1] = c.y - y2 * r
                counts[b]++
            }
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                for (b in 0 until BUCKETS) {
                    if (counts[b] == 0) continue
                    val depth = (b + 1f) / BUCKETS
                    val p = paints[b]
                    p.strokeWidth = dotPx * (0.55f + 0.6f * depth)
                    val base = lerp(Palette.Indigo, Palette.Lavender, depth * (0.4f + 0.6f * e))
                    p.color = base.copy(alpha = (0.18f + 0.75f * depth) * (0.62f + 0.38f * e)).toArgb()
                    nc.drawPoints(buffers[b], 0, counts[b] * 2, p)
                }
            }
            drawCircle(rim, radius = r, center = c, style = rimStroke, alpha = 0.35f + 0.65f * e)
        }
    })
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t, 1f,
)
