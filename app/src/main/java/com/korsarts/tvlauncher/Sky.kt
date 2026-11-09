package com.korsarts.tvlauncher

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** Fase do dia, usada nos ícones do clima. */
enum class DayPhase {
    NIGHT, DAWN, DAY, DUSK;

    companion object {
        private const val MIN = 60_000L

        fun at(ms: Long, sunrise: Long, sunset: Long): DayPhase = when {
            ms >= sunrise - 45 * MIN && ms < sunrise + 45 * MIN -> DAWN
            ms >= sunset - 60 * MIN && ms < sunset + 40 * MIN -> DUSK
            ms in sunrise until sunset -> DAY
            else -> NIGHT
        }
    }
}

/**
 * Estilos do fundo. Cada um define paletas por fase do dia (6 cores: 3 da base + 2 de destaque +
 * brilho) e a geometria usada por [SkyDrawable].
 */
enum class SkyStyle(val id: String, val label: Int, val hint: Int) {
    MESH("mesh", R.string.sky_style_mesh, R.string.sky_style_mesh_hint),
    AURORA("aurora", R.string.sky_style_aurora, R.string.sky_style_aurora_hint),
    TONAL("tonal", R.string.sky_style_tonal, R.string.sky_style_tonal_hint),
    VIBRANT("vibrant", R.string.sky_style_vibrant, R.string.sky_style_vibrant_hint),
    MINIMAL("minimal", R.string.sky_style_minimal, R.string.sky_style_minimal_hint),
    DUOTONE("duotone", R.string.sky_style_duotone, R.string.sky_style_duotone_hint);

    /** Cores por fase: top, mid, bottom, destaque 1, destaque 2, brilho. */
    internal class Key(val c: IntArray, val alpha: Float) {
        fun mix(o: Key, t: Float) = Key(IntArray(c.size) { SkyState.mix(c[it], o.c[it], t) }, alpha + (o.alpha - alpha) * t)
    }

    /** Paletas: noite, aurora, nascer, manhã, meio-dia, tarde, pôr do sol, crepúsculo. */
    internal class Palette(
        val night: Key, val astro: Key, val sunrise: Key, val morning: Key,
        val midday: Key, val afternoon: Key, val sunset: Key, val dusk: Key,
    )

    internal val palette: Palette by lazy { buildPalette(this) }

    companion object {
        fun from(id: String?): SkyStyle = values().firstOrNull { it.id == id } ?: MESH

        private fun k(vararg c: Long, alpha: Float = 0f) = Key(IntArray(c.size) { c[it].toInt() }, alpha)

        /** Estilos com só 4 âncoras: as fases intermediárias saem da mistura das vizinhas. */
        private fun derived(night: Key, sunrise: Key, midday: Key, sunset: Key) = Palette(
            night = night,
            astro = night.mix(sunrise, 0.4f),
            sunrise = sunrise,
            morning = sunrise.mix(midday, 0.65f),
            midday = midday,
            afternoon = midday.mix(sunset, 0.35f),
            sunset = sunset,
            dusk = sunset.mix(night, 0.55f),
        )

        private const val M0 = 0xFF0E0F14
        private const val M1 = 0xFF0A0B0F
        private const val M2 = 0xFF070709

        private fun buildPalette(style: SkyStyle): Palette = when (style) {
            MESH -> Palette(
                night = k(0xFF0A0F24, 0xFF0B1330, 0xFF06070F, 0xFF1F2A5C, 0xFF123049, 0xFF8FA2E6, alpha = 0.12f),
                astro = k(0xFF12153A, 0xFF1A1C45, 0xFF08070F, 0xFF4A3470, 0xFF1C2E5C, 0xFFC08AC0, alpha = 0.15f),
                sunrise = k(0xFF262C5E, 0xFF3B3A72, 0xFF120D1A, 0xFFE08A6E, 0xFF7A6AB0, 0xFFFFB27A, alpha = 0.32f),
                morning = k(0xFF1E4878, 0xFF2C5E92, 0xFF0C1A2C, 0xFF86AECB, 0xFF4E86B4, 0xFFFFE6B0, alpha = 0.15f),
                midday = k(0xFF1C4478, 0xFF2A5C94, 0xFF0B1A30, 0xFF4A9AB8, 0xFF5B7AC0, 0xFFFFF4D6, alpha = 0.13f),
                afternoon = k(0xFF1E4478, 0xFF355E92, 0xFF121524, 0xFFE0A26A, 0xFF6A7FC0, 0xFFFFD08A, alpha = 0.24f),
                sunset = k(0xFF2A2454, 0xFF4A2E64, 0xFF150C16, 0xFFF08A62, 0xFFA0507E, 0xFFFF9466, alpha = 0.32f),
                dusk = k(0xFF151A44, 0xFF221F50, 0xFF09080F, 0xFF7A4282, 0xFF2E3A78, 0xFFB06A9A, alpha = 0.15f),
            )
            AURORA -> derived(
                night = k(0xFF060B1C, 0xFF0A1530, 0xFF04060D, 0xFF2FD6A0, 0xFF7B5CFF, 0L),
                sunrise = k(0xFF1A1B3F, 0xFF2A2550, 0xFF0D0B18, 0xFFF2A07B, 0xFF8E7CC3, 0L),
                midday = k(0xFF123B6B, 0xFF1E5C8E, 0xFF0A1A2C, 0xFF5FD3C6, 0xFF7FA8E8, 0L),
                sunset = k(0xFF2A1E4A, 0xFF3F2456, 0xFF120A16, 0xFFFF8A5C, 0xFFD45D9A, 0L),
            )
            TONAL -> derived(
                night = k(0xFF1E2440, 0xFF121728, 0xFF080A12, 0xFF2C3658, 0xFF2C3658, 0L),
                sunrise = k(0xFF5A4060, 0xFF33233A, 0xFF16101C, 0xFF7E5670, 0xFF7E5670, 0L),
                midday = k(0xFF36557A, 0xFF203852, 0xFF0F1A28, 0xFF5277A0, 0xFF5277A0, 0L),
                sunset = k(0xFF6A4434, 0xFF3A2620, 0xFF1A100E, 0xFF9A6044, 0xFF9A6044, 0L),
            )
            VIBRANT -> derived(
                night = k(0xFF2B1F6E, 0xFF12123A, 0xFF05050F, 0xFF5B3CC4, 0xFF00B3B3, 0L),
                sunrise = k(0xFFFF7E67, 0xFF7B4BC4, 0xFF1B1036, 0xFFFFC371, 0xFFFF5E9C, 0L),
                midday = k(0xFF00A6D6, 0xFF2457C5, 0xFF0B1E4A, 0xFF3EE6C0, 0xFF6C63FF, 0L),
                sunset = k(0xFFFF6A3D, 0xFFC2306E, 0xFF2A0E3A, 0xFFFFB84D, 0xFF8A2BE2, 0L),
            )
            MINIMAL -> derived(
                night = k(M0, M1, M2, 0xFF3B4A8A, 0xFF3B4A8A, 0L),
                sunrise = k(M0, M1, M2, 0xFFC77B6B, 0xFFC77B6B, 0L),
                midday = k(M0, M1, M2, 0xFF4F7FB5, 0xFF4F7FB5, 0L),
                sunset = k(M0, M1, M2, 0xFFD0703F, 0xFFD0703F, 0L),
            )
            DUOTONE -> {
                fun duo(a: Long, b: Long) =
                    Key(intArrayOf(a.toInt(), SkyState.mix(a.toInt(), b.toInt(), 0.5f), b.toInt(), 0, 0, 0), 0f)
                derived(
                    night = duo(0xFF23306A, 0xFF05060F),
                    sunrise = duo(0xFFD9806C, 0xFF2B2459),
                    midday = duo(0xFF2F7FC1, 0xFF0E2347),
                    sunset = duo(0xFFE8844A, 0xFF3A1747),
                )
            }
        }
    }
}

/**
 * Estado do céu num instante: base de 3 cores, duas cores de destaque (manchas ou faixas, conforme
 * o estilo) e o brilho do sol/lua. Tudo interpolável, para transições suaves.
 */
class SkyState(
    val top: Int,
    val mid: Int,
    val bottom: Int,
    val horizon: Int,
    val accent: Int,
    val glow: Int,
    /** 0..1 */
    val glowAlpha: Float,
    /** Posições em fração da largura/altura. */
    val glowX: Float,
    val glowY: Float,
    /** Posição horizontal do sol (ou do caminho de volta à noite), contínua ao longo do dia. */
    val sunX: Float,
) {
    fun lerp(o: SkyState, t: Float) = SkyState(
        mix(top, o.top, t), mix(mid, o.mid, t), mix(bottom, o.bottom, t),
        mix(horizon, o.horizon, t), mix(accent, o.accent, t), mix(glow, o.glow, t),
        lerpF(glowAlpha, o.glowAlpha, t), lerpF(glowX, o.glowX, t), lerpF(glowY, o.glowY, t), lerpF(sunX, o.sunX, t),
    )

    companion object {
        private const val MIN = 60_000L
        private const val DAY = 24 * 60 * MIN

        /** Fundo escuro neutro (igual ao tema): ponto de partida da entrada e da saída. */
        val BLANK = SkyState(
            0xFF10131C.toInt(), 0xFF0B0D14.toInt(), 0xFF08090D.toInt(), 0xFF0B0D14.toInt(), 0xFF10131C.toInt(), 0,
            0f, 0.5f, 0.5f, 0.5f,
        )

        /** Céu de [style] para [nowMs] com nascer/pôr [sunrise]/[sunset] e o tempo atual ([code] WMO, nuvens em %). */
        fun compute(style: SkyStyle, nowMs: Long, sunrise: Long, sunset: Long, code: Int?, cloudCover: Int?): SkyState {
            val p = style.palette
            val noon = (sunrise + sunset) / 2
            val keys = listOf(
                sunrise - 90 * MIN to p.night,
                sunrise - 40 * MIN to p.astro,
                sunrise to p.sunrise,
                sunrise + 70 * MIN to p.morning,
                noon to p.midday,
                sunset - 100 * MIN to p.afternoon,
                sunset - 10 * MIN to p.sunset,
                sunset + 40 * MIN to p.dusk,
                sunset + 95 * MIN to p.night,
            ).sortedBy { it.first }

            val i = keys.indexOfFirst { it.first > nowMs }
            val key = if (i <= 0) {
                if (i == 0) keys.first().second else keys.last().second
            } else {
                val (t0, a) = keys[i - 1]
                val (t1, b) = keys[i]
                a.mix(b, smooth((nowMs - t0).toFloat() / (t1 - t0).coerceAtLeast(1)))
            }
            val cols = key.c.copyOf()
            var alpha = key.alpha

            // Sol: arco entre o amanhecer e o anoitecer. À noite o brilho vira a lua num ponto
            // fixo (apaga e acende na troca); a posição do sol volta devagar de oeste a leste.
            val sunFrom = sunrise - 75 * MIN
            val sunTo = sunset + 75 * MIN
            fun arcX(ms: Long) = 0.12f + 0.76f * (ms - sunrise).toFloat() / (sunset - sunrise).coerceAtLeast(1)
            val gx: Float; val gy: Float; val sx: Float
            if (nowMs in sunFrom until sunTo) {
                val pr = (nowMs - sunrise).toFloat() / (sunset - sunrise).coerceAtLeast(1)
                gx = arcX(nowMs)
                gy = 0.78f - 0.62f * sin(PI * pr.coerceIn(0f, 1f)).toFloat()
                sx = gx
            } else {
                gx = 0.8f; gy = 0.1f
                val nightStart = if (nowMs >= sunTo) sunTo else sunTo - DAY
                val nightEnd = if (nowMs >= sunTo) sunFrom + DAY else sunFrom
                val q = smooth(((nowMs - nightStart).toFloat() / (nightEnd - nightStart).coerceAtLeast(1)).coerceIn(0f, 1f))
                sx = lerpF(arcX(sunTo), arcX(sunFrom), q)
            }
            val edge = min(abs(nowMs - sunFrom), abs(nowMs - sunTo)).toFloat() / (25 * MIN)
            alpha *= smooth(edge.coerceIn(0f, 1f))

            // Clima: nuvens dessaturam e escurecem; chuva/tempestade/neblina/neve dão um tom.
            val c = code
            val cloud = when {
                cloudCover != null -> cloudCover / 100f
                c == null -> 0.15f
                c == 0 -> 0f
                c == 1 -> 0.2f
                c == 2 -> 0.5f
                else -> 0.9f
            }.let { if (c != null && c >= 45) maxOf(it, 0.8f) else it }
            var desat = cloud * 0.5f
            var dark = cloud * 0.14f
            var tint = 0
            var tintAmt = 0f
            when (c) {
                45, 48 -> { tint = 0xFF5E6672.toInt(); tintAmt = 0.3f }
                in 51..67, 80, 81, 82 -> { dark += 0.1f; tint = 0xFF1C2C3C.toInt(); tintAmt = 0.18f }
                in 71..77, 85, 86 -> { tint = 0xFF7D8BA2.toInt(); tintAmt = 0.25f; dark -= 0.05f }
                95, 96, 99 -> { dark += 0.22f; desat += 0.1f; tint = 0xFF2A1F3D.toInt(); tintAmt = 0.3f }
            }
            desat = desat.coerceIn(0f, 0.85f)
            dark = dark.coerceIn(0f, 0.5f)
            for (j in 0 until 5) cols[j] = mix(darken(desaturate(cols[j], desat), dark), tint, tintAmt)
            alpha *= 1f - cloud * 0.8f

            return SkyState(
                cols[0], cols[1], cols[2], cols[3], cols[4], cols[5],
                alpha, gx, gy, sx.coerceIn(0.1f, 0.9f),
            )
        }

        private fun smooth(t: Float) = t * t * (3 - 2 * t)

        private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun mix(a: Int, b: Int, t: Float): Int {
            if (t <= 0f) return a
            if (t >= 1f) return b
            fun ch(sh: Int) = (((a shr sh) and 0xFF) + (((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * t + 0.5f).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun desaturate(c: Int, t: Float): Int {
            val l = (Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f + 0.5f).toInt()
            return mix(c, Color.argb(255, l, l, l), t)
        }

        private fun darken(c: Int, t: Float): Int {
            val f = 1f - t
            return Color.argb(255, (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
        }
    }
}

/**
 * Fundo do launcher. O céu é calculado pixel a pixel num bitmap pequeno (192x108) e esticado com
 * filtro bilinear: na tela vira um único quad texturizado, o mesmo custo de GPU de um degradê
 * simples, qualquer que seja o estilo. Só é recalculado quando o estado muda (~20 mil pixels,
 * poucos ms na CPU do stick).
 */
class SkyDrawable(initial: SkyState, style: SkyStyle) : Drawable() {

    var style: SkyStyle = style
        private set

    private var bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    /** Estilo anterior durante a troca (crossfade). */
    private var previous: Bitmap? = null
    private var fade = 1f
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fadePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val r = FloatArray(N)
    private val g = FloatArray(N)
    private val b = FloatArray(N)
    private val pixels = IntArray(N)
    private var current = initial
    private var animator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null

    init {
        render(current)
    }

    /** Vai até [target] em [durationMs] (0 = na hora). */
    fun setState(target: SkyState, durationMs: Long) {
        animator?.cancel()
        if (durationMs <= 0) {
            current = target
            render(target)
            return
        }
        val from = current
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator() // a curva vem do smoothstep abaixo
            addUpdateListener {
                val t = it.animatedValue as Float
                current = from.lerp(target, t * t * (3 - 2 * t))
                render(current)
            }
            start()
        }
    }

    /** Troca de estilo com crossfade de [durationMs] até o céu [target] do novo estilo. */
    fun setStyle(newStyle: SkyStyle, target: SkyState, durationMs: Long) {
        if (newStyle == style) {
            setState(target, durationMs)
            return
        }
        animator?.cancel()
        fadeAnimator?.cancel()
        previous = bitmap
        bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        style = newStyle
        current = target
        render(target)
        fade = 0f
        fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = Motion.STANDARD
            addUpdateListener {
                fade = it.animatedValue as Float
                invalidateSelf()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    fade = 1f
                    previous?.recycle()
                    previous = null
                    invalidateSelf()
                }
            })
            start()
        }
    }

    fun stop() {
        animator?.end()
        animator = null
        fadeAnimator?.end()
        fadeAnimator = null
    }

    // ------------------------------------------------------------------ desenho do céu

    private fun render(s: SkyState) {
        var gain = 1f
        when (style) {
            SkyStyle.MESH -> {
                base(s, 160f)
                blob(s.horizon, 0.55f, s.sunX, 0.95f, 0.7f, 0.55f)
                blob(s.accent, 0.42f, 1f - s.sunX * 0.8f, 0.02f, 0.6f, 0.6f)
                if (s.glowAlpha > 0.005f) blob(s.glow, s.glowAlpha, s.glowX, s.glowY, 0.45f, 1f)
            }
            SkyStyle.AURORA -> {
                base(s, 175f)
                // As faixas ondulam devagar ao longo do dia (fase acompanha o sol).
                val drift = s.sunX * PI.toFloat()
                wave(s.horizon, 0.55f, 0.30f, 0.07f, 1.1f, 0.6f + drift, 0.07f)
                wave(s.accent, 0.45f, 0.46f, 0.06f, 1.6f, 2.2f - drift * 0.7f, 0.06f)
            }
            SkyStyle.TONAL -> {
                base(s, 180f)
                blob(s.horizon, 0.5f, 0.5f, 0.05f, 1.0f, 0.5f)
            }
            SkyStyle.VIBRANT -> {
                base(s, 135f)
                blob(s.horizon, 0.6f, 0.12f, 0.1f, 0.55f, 0.8f)
                blob(s.accent, 0.55f, 0.9f, 0.75f, 0.6f, 0.7f)
                gain = 0.82f
            }
            SkyStyle.MINIMAL -> {
                base(s, 160f)
                blob(s.horizon, 0.28f, 0.15f, 0f, 0.9f, 0.6f)
                blob(s.accent, 0.1f, 0.95f, 1f, 0.6f, 0.6f)
            }
            SkyStyle.DUOTONE -> {
                base(s, 135f)
                gain = 0.86f
            }
        }
        finish(gain)
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H)
        invalidateSelf()
    }

    /** Degradê linear de 3 cores no ângulo CSS [angleDeg] (180 = de cima para baixo), com smoothstep. */
    private fun base(s: SkyState, angleDeg: Float) {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = sin(a).toFloat()
        val dy = (-cos(a)).toFloat()
        val half = abs(W / 2f * dx) + abs(H / 2f * dy)
        val r0 = Color.red(s.top).toFloat(); val g0 = Color.green(s.top).toFloat(); val b0 = Color.blue(s.top).toFloat()
        val r1 = Color.red(s.mid).toFloat(); val g1 = Color.green(s.mid).toFloat(); val b1 = Color.blue(s.mid).toFloat()
        val r2 = Color.red(s.bottom).toFloat(); val g2 = Color.green(s.bottom).toFloat(); val b2 = Color.blue(s.bottom).toFloat()
        var i = 0
        for (y in 0 until H) {
            val py = y + 0.5f - H / 2f
            for (x in 0 until W) {
                val t = (((x + 0.5f - W / 2f) * dx + py * dy) / (2 * half) + 0.5f).coerceIn(0f, 1f)
                if (t < 0.5f) {
                    val u = smooth(t * 2)
                    r[i] = r0 + (r1 - r0) * u; g[i] = g0 + (g1 - g0) * u; b[i] = b0 + (b1 - b0) * u
                } else {
                    val u = smooth(t * 2 - 1)
                    r[i] = r1 + (r2 - r1) * u; g[i] = g1 + (g2 - g1) * u; b[i] = b1 + (b2 - b1) * u
                }
                i++
            }
        }
    }

    /** Mancha elíptica com queda quase gaussiana; posições/raio em fração da largura/altura. */
    private fun blob(color: Int, alpha: Float, cx: Float, cy: Float, radius: Float, squash: Float) {
        val cr = Color.red(color).toFloat(); val cg = Color.green(color).toFloat(); val cb = Color.blue(color).toFloat()
        val px = cx * W; val py = cy * H
        val rx = radius * W; val ry = radius * W * squash
        val y0 = (py - ry).toInt().coerceIn(0, H); val y1 = (py + ry + 1).toInt().coerceIn(0, H)
        val x0 = (px - rx).toInt().coerceIn(0, W); val x1 = (px + rx + 1).toInt().coerceIn(0, W)
        for (y in y0 until y1) {
            val ny = (y + 0.5f - py) / ry
            for (x in x0 until x1) {
                val nx = (x + 0.5f - px) / rx
                val d2 = nx * nx + ny * ny
                if (d2 >= 1f) continue
                val a = alpha * falloff(sqrt(d2))
                val i = y * W + x
                r[i] += (cr - r[i]) * a; g[i] += (cg - g[i]) * a; b[i] += (cb - b[i]) * a
            }
        }
    }

    /** Faixa de luz ondulada (aurora): gaussiana em torno de uma senoide, mais fraca nas pontas. */
    private fun wave(color: Int, alpha: Float, y0: Float, amp: Float, freq: Float, phase: Float, thick: Float) {
        val cr = Color.red(color).toFloat(); val cg = Color.green(color).toFloat(); val cb = Color.blue(color).toFloat()
        val th = thick * H
        for (x in 0 until W) {
            val fx = (x + 0.5f) / W
            val cy = (y0 + amp * sin(freq * fx * 2 * PI.toFloat() + phase)) * H
            val edge = 0.55f + 0.45f * sin(fx * PI.toFloat())
            for (y in 0 until H) {
                val dy = y + 0.5f - cy
                val d = dy / th
                val d2 = d * d
                if (d2 > 30f) continue
                var a = alpha * exp(-2f * d2) * edge
                a *= (1f - maxOf(0f, dy) / (th * 3.5f)).coerceIn(0f, 1f) * 0.6f + 0.4f * exp(-0.6f * d2)
                a = a.coerceIn(0f, 1f)
                val i = y * W + x
                r[i] += (cr - r[i]) * a; g[i] += (cg - g[i]) * a; b[i] += (cb - b[i]) * a
            }
        }
    }

    /** Véu de leitura (topo e base), ganho do estilo, dither ordenado e empacotamento. */
    private fun finish(gain: Float) {
        var i = 0
        for (y in 0 until H) {
            val t = (y + 0.5f) / H
            val shade = when {
                t < 0.18f -> 0.18f * (1f - t / 0.18f)
                t < 0.62f -> 0f
                else -> 0.5f * (t - 0.62f) / 0.38f
            }
            val f = (1f - shade) * gain
            for (x in 0 until W) {
                val dither = BAYER[(y and 3) * 4 + (x and 3)]
                val rr = (r[i] * f + dither).toInt().coerceIn(0, 255)
                val gg = (g[i] * f + dither).toInt().coerceIn(0, 255)
                val bb = (b[i] * f + dither).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
                i++
            }
        }
    }

    private fun falloff(d: Float): Float {
        val p = d * 4f
        val k = p.toInt().coerceAtMost(3)
        return FALLOFF[k] + (FALLOFF[k + 1] - FALLOFF[k]) * (p - k)
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    override fun draw(canvas: Canvas) {
        val prev = previous
        if (prev != null && fade < 1f) {
            canvas.drawBitmap(prev, null, bounds, drawPaint)
            fadePaint.alpha = (fade * 255).toInt()
            canvas.drawBitmap(bitmap, null, bounds, fadePaint)
        } else {
            canvas.drawBitmap(bitmap, null, bounds, drawPaint)
        }
    }

    override fun onBoundsChange(bounds: Rect) = invalidateSelf()
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private const val W = 192
        private const val H = 108
        private const val N = W * H
        private val FALLOFF = floatArrayOf(1f, 0.62f, 0.28f, 0.08f, 0f)
        /** Bayer 4x4 centrado em 0.5 (arredondamento + dither). */
        private val BAYER = floatArrayOf(0f, 8f, 2f, 10f, 12f, 4f, 14f, 6f, 3f, 11f, 1f, 9f, 15f, 7f, 13f, 5f)
            .map { (it + 0.5f) / 16f }.toFloatArray()
    }
}
