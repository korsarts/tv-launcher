package com.korsarts.tvlauncher

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.PI
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
 * Estado do céu num instante: degradê diagonal de 3 cores (claro no alto à esquerda, escuro
 * embaixo à direita), duas manchas elípticas de cor (horizonte e alto do céu, estilo "mesh
 * gradient") e o brilho do sol/lua. Tudo interpolável, para transições suaves.
 */
class SkyState(
    val top: Int,
    val mid: Int,
    val bottom: Int,
    /** Mancha baixa (horizonte), segue o sol na horizontal. */
    val horizon: Int,
    /** Mancha alta, do lado oposto. */
    val accent: Int,
    val glow: Int,
    /** 0..1 */
    val glowAlpha: Float,
    /** Posições em fração da largura/altura. */
    val glowX: Float,
    val glowY: Float,
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

        /** top, mid, bottom, horizon, accent, glow */
        private class Key(vararg val c: Int, val alpha: Float)

        private fun k(vararg c: Long, alpha: Float) = Key(*IntArray(c.size) { c[it].toInt() }, alpha = alpha)

        private val NIGHT = k(0xFF0A0F24, 0xFF0B1330, 0xFF06070F, 0xFF1F2A5C, 0xFF123049, 0xFF8FA2E6, alpha = 0.12f)
        private val ASTRO = k(0xFF12153A, 0xFF1A1C45, 0xFF08070F, 0xFF4A3470, 0xFF1C2E5C, 0xFFC08AC0, alpha = 0.15f)
        private val SUNRISE = k(0xFF262C5E, 0xFF3B3A72, 0xFF120D1A, 0xFFE08A6E, 0xFF7A6AB0, 0xFFFFB27A, alpha = 0.32f)
        private val MORNING = k(0xFF1E4878, 0xFF2C5E92, 0xFF0C1A2C, 0xFF86AECB, 0xFF4E86B4, 0xFFFFE6B0, alpha = 0.15f)
        private val MIDDAY = k(0xFF1C4478, 0xFF2A5C94, 0xFF0B1A30, 0xFF4A9AB8, 0xFF5B7AC0, 0xFFFFF4D6, alpha = 0.13f)
        private val AFTERNOON = k(0xFF1E4478, 0xFF355E92, 0xFF121524, 0xFFE0A26A, 0xFF6A7FC0, 0xFFFFD08A, alpha = 0.24f)
        private val SUNSET = k(0xFF2A2454, 0xFF4A2E64, 0xFF150C16, 0xFFF08A62, 0xFFA0507E, 0xFFFF9466, alpha = 0.32f)
        private val DUSK = k(0xFF151A44, 0xFF221F50, 0xFF09080F, 0xFF7A4282, 0xFF2E3A78, 0xFFB06A9A, alpha = 0.15f)

        /** Fundo escuro neutro (igual ao tema): ponto de partida da entrada e da saída. */
        val BLANK = SkyState(
            0xFF10131C.toInt(), 0xFF0B0D14.toInt(), 0xFF08090D.toInt(), 0xFF0B0D14.toInt(), 0xFF141925.toInt(), 0,
            0f, 0.5f, 0.5f, 0.5f,
        )

        /** Céu para [nowMs] com nascer/pôr [sunrise]/[sunset] e o tempo atual ([code] WMO, nuvens em %). */
        fun compute(nowMs: Long, sunrise: Long, sunset: Long, code: Int?, cloudCover: Int?): SkyState {
            val noon = (sunrise + sunset) / 2
            val keys = listOf(
                sunrise - 90 * MIN to NIGHT,
                sunrise - 40 * MIN to ASTRO,
                sunrise to SUNRISE,
                sunrise + 70 * MIN to MORNING,
                noon to MIDDAY,
                sunset - 100 * MIN to AFTERNOON,
                sunset - 10 * MIN to SUNSET,
                sunset + 40 * MIN to DUSK,
                sunset + 95 * MIN to NIGHT,
            ).sortedBy { it.first }

            val cols: IntArray
            var alpha: Float
            val i = keys.indexOfFirst { it.first > nowMs }
            if (i <= 0) {
                val key = if (i == 0) keys.first().second else keys.last().second
                cols = key.c.copyOf(); alpha = key.alpha
            } else {
                val (t0, a) = keys[i - 1]
                val (t1, b) = keys[i]
                val t = smooth((nowMs - t0).toFloat() / (t1 - t0).coerceAtLeast(1))
                cols = IntArray(a.c.size) { mix(a.c[it], b.c[it], t) }
                alpha = lerpF(a.alpha, b.alpha, t)
            }

            // Sol: arco entre o amanhecer e o anoitecer. À noite o brilho vira a lua num ponto
            // fixo (apaga e acende na troca); a mancha do horizonte volta devagar de leste a oeste.
            val sunFrom = sunrise - 75 * MIN
            val sunTo = sunset + 75 * MIN
            fun arcX(ms: Long) = 0.12f + 0.76f * (ms - sunrise).toFloat() / (sunset - sunrise).coerceAtLeast(1)
            val gx: Float; val gy: Float; val sx: Float
            if (nowMs in sunFrom until sunTo) {
                val p = (nowMs - sunrise).toFloat() / (sunset - sunrise).coerceAtLeast(1)
                gx = arcX(nowMs)
                gy = 0.78f - 0.62f * sin(PI * p.coerceIn(0f, 1f)).toFloat()
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
 * Fundo do launcher. O céu é desenhado num bitmap pequeno (192x108) e esticado com filtro
 * bilinear: na tela vira um único quad texturizado, o mesmo custo de GPU de um degradê simples,
 * por mais camadas que o céu tenha. Só é redesenhado quando o estado muda.
 */
class SkyDrawable(initial: SkyState) : Drawable() {

    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val bitmapCanvas = Canvas(bitmap)
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val paint = Paint(Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val baseColors = IntArray(BASE_STOPS)
    private val basePositions = FloatArray(BASE_STOPS) { it / (BASE_STOPS - 1f) }
    private val blobColors = IntArray(FALLOFF.size)
    private var current = initial
    private var animator: ValueAnimator? = null

    /** Véu de leitura: leve no topo (relógio), mais forte embaixo (favoritos). Fixo. */
    private val veil = LinearGradient(
        0f, 0f, 0f, H.toFloat(),
        intArrayOf(0x2E000000, 0, 0, 0x80000000.toInt()),
        floatArrayOf(0f, 0.18f, 0.62f, 1f),
        Shader.TileMode.CLAMP,
    )

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

    fun stop() {
        animator?.end()
        animator = null
    }

    private fun render(s: SkyState) {
        val w = W.toFloat()
        val h = H.toFloat()

        // Base diagonal (~160°): várias paradas com smoothstep, sem "dobra" visível no meio.
        for (i in 0 until BASE_STOPS) {
            val t = basePositions[i]
            baseColors[i] = if (t < 0.5f) {
                SkyState.mix(s.top, s.mid, smooth(t / 0.5f))
            } else {
                SkyState.mix(s.mid, s.bottom, smooth((t - 0.5f) / 0.5f))
            }
        }
        paint.shader = LinearGradient(0.3f * w, 0f, 0.7f * w, h, baseColors, basePositions, Shader.TileMode.CLAMP)
        bitmapCanvas.drawRect(0f, 0f, w, h, paint)

        // Manchas elípticas: horizonte (embaixo, acompanha o sol) e acento (em cima, oposto).
        blob(s.horizon, 0.55f, s.sunX * w, 0.95f * h, 0.7f * w, 0.55f)
        blob(s.accent, 0.42f, (1f - s.sunX * 0.8f) * w, 0.02f * h, 0.6f * w, 0.6f)
        if (s.glowAlpha > 0.005f) blob(s.glow, s.glowAlpha, s.glowX * w, s.glowY * h, 0.45f * w, 1f)

        paint.shader = veil
        bitmapCanvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        invalidateSelf()
    }

    /** Mancha radial com queda quase gaussiana, achatada verticalmente por [squash]. */
    private fun blob(color: Int, alpha: Float, cx: Float, cy: Float, r: Float, squash: Float) {
        val rgb = color and 0x00FFFFFF
        for (i in FALLOFF.indices) {
            blobColors[i] = rgb or ((alpha * FALLOFF[i] * 255).toInt().coerceIn(0, 255) shl 24)
        }
        paint.shader = RadialGradient(cx, cy, r, blobColors, FALLOFF_POS, Shader.TileMode.CLAMP)
        bitmapCanvas.save()
        bitmapCanvas.scale(1f, squash, cx, cy)
        bitmapCanvas.drawCircle(cx, cy, r, paint)
        bitmapCanvas.restore()
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(bitmap, null, bounds, drawPaint)
    }

    override fun onBoundsChange(bounds: Rect) = invalidateSelf()
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        private const val W = 192
        private const val H = 108
        private const val BASE_STOPS = 9
        private val FALLOFF = floatArrayOf(1f, 0.62f, 0.28f, 0.08f, 0f)
        private val FALLOFF_POS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    }
}
