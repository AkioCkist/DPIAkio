package com.dpiakio.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Geometry shared by the background planet and the power button placed on it. */
object EarthLayout {
    /** Planet radius for a given container width. */
    fun radius(w: Float) = w * 0.66f
    /** Planet centre: horizontally centred, [centerFromTop] below the container top. */
    val centerFromTop: Dp = 560.dp
}

/** 720x360 equirectangular land mask (Natural Earth 1:50m, public domain), 1 bit per cell. */
private object EarthMask {
    const val W = 720
    const val H = 360
    @Volatile private var bits: ByteArray? = null
    @Volatile private var bitmap: Bitmap? = null

    fun bits(ctx: Context): ByteArray = bits ?: synchronized(this) {
        bits ?: ctx.assets.open("earth_mask.bin").use { it.readBytes() }.also { bits = it }
    }

    fun isLand(ctx: Context, latDeg: Double, lonDeg: Double): Boolean {
        val b = bits(ctx)
        val col = (((lonDeg + 180) / 360 * W).toInt() % W + W) % W
        val row = ((90 - latDeg) / 180 * H).toInt().coerceIn(0, H - 1)
        val i = row * W + col
        return (b[i shr 3].toInt() shr (i and 7)) and 1 == 1
    }

    fun bitmap(ctx: Context): Bitmap = bitmap ?: synchronized(this) {
        bitmap ?: run {
            val b = bits(ctx)
            val px = IntArray(W * H) { i -> if ((b[i shr 3].toInt() shr (i and 7)) and 1 == 1) -0x1 else 0 }
            Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888).also { bitmap = it }
        }
    }
}

/**
 * AGSL: dotted Earth on a sphere. Every pixel resolves its latitude / longitude, snaps to a
 * dot grid of ~1.5°, looks the cell up in the land mask and lights land dots, with a fresnel
 * rim and an atmosphere glow outside the disc.
 */
private const val RENDER_SCALE = 0.6f

private const val EARTH_AGSL = """
uniform float2 center;
uniform float radius;
uniform float rot;
uniform float energy;
uniform float2 maskSize;
uniform shader mask;

const float PI = 3.14159265;
const float TILT = 0.40;

half4 main(float2 p) {
    float2 q = (p - center) / radius;
    float d = length(q);
    float e = energy;
    float3 rimCol = mix(float3(0.30, 0.38, 0.95), float3(0.36, 0.55, 1.0), e);

    // atmosphere outside the disc, stronger on the upper edge
    if (d >= 1.0) {
        float up = clamp(0.55 - 0.55 * q.y / d, 0.0, 1.0);
        float g = exp(-(d - 1.0) * 9.0) * (0.30 + 0.70 * e) * (0.35 + 0.65 * up);
        return half4(half3(rimCol * g), half(g));
    }

    float z = sqrt(max(1.0 - d * d, 0.0));
    float3 v = float3(q.x, -q.y, z);
    float ct = cos(TILT);
    float st = sin(TILT);
    float3 w = float3(v.x, v.y * ct + v.z * st, -v.y * st + v.z * ct);

    float lat = asin(clamp(w.y, -1.0, 1.0));
    float lon = atan(w.x, w.z) + rot;

    float dLat = 0.0262;
    float latc = floor(lat / dLat + 0.5) * dLat;
    float dLon = dLat / max(cos(latc), 0.06);
    float lonc = floor(lon / dLon + 0.5) * dLon;
    float dist = length(float2((lon - lonc) / dLon, (lat - latc) / dLat));

    float u = fract(lonc / (2.0 * PI) + 0.5) * maskSize.x;
    float vv = clamp((0.5 - latc / PI) * maskSize.y, 0.5, maskSize.y - 0.5);
    float land = step(0.5, mask.eval(float2(u, vv)).a);

    // dot radius / soft edge in cell units (cells shrink on screen towards the limb)
    float soft = clamp(1.4 / (radius * dLat * max(z, 0.12)), 0.06, 0.45);
    float dotA = 1.0 - smoothstep(0.30 - soft * 0.5, 0.30 + soft * 0.5, dist);

    float3 L = normalize(float3(-0.35, 0.75, 0.55));
    float light = clamp(dot(v, L), 0.0, 1.0);
    float fres = pow(1.0 - z, 2.2);

    float3 base = float3(0.010, 0.020, 0.075) + float3(0.04, 0.06, 0.20) * light * (0.4 + 0.6 * e);
    float3 landCol = mix(float3(0.20, 0.34, 1.0), float3(0.70, 0.86, 1.0), clamp(fres * 1.6 + light * 0.25, 0.0, 1.0));
    float bright = (0.30 + 0.70 * light + 1.6 * fres) * (0.40 + 0.60 * e);
    float3 col = base + landCol * dotA * mix(0.06, 1.0, land) * bright;
    col += rimCol * fres * (0.55 + 0.75 * e);

    // anti-aliased limb
    float edge = smoothstep(1.0, 1.0 - 1.5 / radius, d);
    float glowIn = exp(-(1.0 - d) * 9.0) * (0.30 + 0.70 * e) * (1.0 - edge);
    float3 outCol = col * edge + rimCol * glowIn;
    float a = edge + (1.0 - edge) * glowIn;
    return half4(half3(outCol), half(a));
}
"""

/**
 * Rotating dotted Earth used as the home background. Rotation advances at ~30 updates/s and
 * is read only in the draw phase; the composable itself never recomposes while spinning.
 */
@Composable
fun EarthGlobe(active: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val angle = remember { mutableFloatStateOf(-1.9f) }   // start over Asia
    val energy = remember { mutableFloatStateOf(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last == 0L) last = t
                else if (t - last >= 32_000_000L) {
                    val dt = (t - last) / 1e9f
                    val target = if (active) 1f else 0f
                    energy.floatValue += (target - energy.floatValue) * (dt * 2.5f).coerceAtMost(1f)
                    angle.floatValue += dt * (0.025f + 0.09f * energy.floatValue)
                    last = t
                }
            }
            if (!active && energy.floatValue < 0.01f) { energy.floatValue = 0f; break }
        }
    }
    val centerPx = with(LocalDensity.current) { EarthLayout.centerFromTop.toPx() }

    if (Build.VERSION.SDK_INT >= 33) {
        val shader = remember { earthShader(ctx) }
        val brush = remember { ShaderBrush(shader) }
        // Shaded at 60 % resolution into an offscreen texture (once per frame, reused by every
        // glass surface) and scaled up — the soft glow hides the difference, the GPU does ~1/3 the work.
        val k = RENDER_SCALE
        Spacer(
            modifier
                .layout { m, c ->
                    val w = c.maxWidth; val h = c.maxHeight
                    val p = m.measure(Constraints.fixed((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1)))
                    layout(w, h) {
                        p.placeWithLayer(0, 0) {
                            scaleX = 1f / k; scaleY = 1f / k
                            transformOrigin = TransformOrigin(0f, 0f)
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                    }
                }
                .drawWithCache {
                    val r = EarthLayout.radius(size.width)
                    val cy = centerPx * k
                    shader.setFloatUniform("center", size.width / 2, cy)
                    shader.setFloatUniform("radius", r)
                    // Only the disc + atmosphere band is shaded, not the whole screen.
                    val top = (cy - r * 1.6f).coerceAtLeast(0f)   // glow < 1% beyond this
                    val area = androidx.compose.ui.geometry.Size(size.width, (size.height - top).coerceAtLeast(0f))
                    val origin = Offset(0f, top)
                    onDrawBehind {
                        shader.setFloatUniform("rot", angle.floatValue)
                        shader.setFloatUniform("energy", energy.floatValue)
                        drawRect(brush, topLeft = origin, size = area)
                    }
                }
        )
    } else {
        CpuEarth(angle, energy, centerPx, modifier)
    }
}

@RequiresApi(33)
private fun earthShader(ctx: Context): RuntimeShader = RuntimeShader(EARTH_AGSL).apply {
    setInputShader("mask", BitmapShader(EarthMask.bitmap(ctx), Shader.TileMode.REPEAT, Shader.TileMode.CLAMP))
    setFloatUniform("maskSize", EarthMask.W.toFloat(), EarthMask.H.toFloat())
    setFloatUniform("rot", 0f); setFloatUniform("energy", 0f)
    setFloatUniform("center", 0f, 0f); setFloatUniform("radius", 1f)
}

// ---------------------------------------------------------------- fallback (< Android 13)

private class LandPoints(val xs: FloatArray, val ys: FloatArray, val zs: FloatArray)

private var cpuPoints: LandPoints? = null

private fun landPoints(ctx: Context): LandPoints = cpuPoints ?: run {
    val x = ArrayList<Float>(); val y = ArrayList<Float>(); val z = ArrayList<Float>()
    var lat = -84.0
    while (lat <= 84.0) {
        val r = cos(lat * PI / 180)
        val n = max(6, (r * 200).roundToInt())
        for (i in 0 until n) {
            val lon = 2 * PI * i / n
            val lonDeg = lon * 180 / PI - 180
            if (!EarthMask.isLand(ctx, lat, lonDeg)) continue
            x += (r * sin(lon)).toFloat(); z += (r * cos(lon)).toFloat(); y += sin(lat * PI / 180).toFloat()
        }
        lat += 1.8
    }
    LandPoints(x.toFloatArray(), y.toFloatArray(), z.toFloatArray()).also { cpuPoints = it }
}

@Composable
private fun CpuEarth(
    angle: androidx.compose.runtime.MutableFloatState,
    energy: androidx.compose.runtime.MutableFloatState,
    centerPx: Float,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    val pts = remember { landPoints(ctx) }
    val buf = remember { FloatArray(pts.xs.size * 2) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }
    val dot = with(LocalDensity.current) { 2.2f * density }
    Spacer(modifier.graphicsLayer().drawWithCache {
        val r = EarthLayout.radius(size.width)
        val c = Offset(size.width / 2, centerPx)
        val glow = Brush.radialGradient(
            0f to Color(0x00000000), 0.70f to Color(0x00000000), 0.74f to Color(0x663D5AFE), 1f to Color.Transparent,
            center = c, radius = r * 1.35f,
        )
        val ct = cos(0.40f); val st = sin(0.40f)
        onDrawBehind {
            val e = energy.floatValue
            drawCircle(glow, r * 1.35f, c, alpha = 0.4f + 0.6f * e)
            drawCircle(Color(0xFF030619), r, c)
            val a = angle.floatValue
            val ca = cos(a); val sa = sin(a)
            var n = 0
            for (i in pts.xs.indices) {
                val x1 = pts.xs[i] * ca - pts.zs[i] * sa
                val z1 = pts.xs[i] * sa + pts.zs[i] * ca
                val y2 = pts.ys[i] * ct - z1 * st
                val z2 = pts.ys[i] * st + z1 * ct
                if (z2 < 0f) continue
                buf[n++] = c.x + x1 * r; buf[n++] = c.y - y2 * r
            }
            paint.strokeWidth = dot
            paint.color = Color(0xFF5B7CFF).copy(alpha = 0.55f + 0.45f * e).toArgb()
            drawIntoCanvas { it.nativeCanvas.drawPoints(buf, 0, n, paint) }
        }
    })
}

