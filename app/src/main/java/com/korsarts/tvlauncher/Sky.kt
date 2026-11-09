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
 * Estado do céu num instante: 4 paradas do degradê vertical (topo, alto, horizonte, base) e um
 * brilho radial (sol ou lua). Tudo interpolável, para transições suaves.
 */
class SkyState(
    val top: Int,
    val upper: Int,
    val horizon: Int,
    val bottom: Int,
    val glow: Int,
    /** 0..1 */
    val glowAlpha: Float,
    /** Posição do brilho em fração da largura/altura. */
    val glowX: Float,
    val glowY: Float,
) {
    fun lerp(o: SkyState, t: Float) = SkyState(
        mix(top, o.top, t), mix(upper, o.upper, t), mix(horizon, o.horizon, t), mix(bottom, o.bottom, t),
        mix(glow, o.glow, t), glowAlpha + (o.glowAlpha - glowAlpha) * t,
        glowX + (o.glowX - glowX) * t, glowY + (o.glowY - glowY) * t,
    )

    companion object {
        private const val MIN = 60_000L
        private const val HOUR = 60 * MIN

        private class Key(val top: Int, val upper: Int, val horizon: Int, val bottom: Int, val glow: Int, val alpha: Float)

        private val NIGHT = Key(0xFF050915.toInt(), 0xFF0C1631.toInt(), 0xFF1A2850.toInt(), 0xFF04060B.toInt(), 0xFF6F86C8.toInt(), 0.12f)
        private val ASTRO = Key(0xFF0A1130.toInt(), 0xFF1C2250.toInt(), 0xFF453463.toInt(), 0xFF06060D.toInt(), 0xFFB07AA8.toInt(), 0.18f)
        private val SUNRISE = Key(0xFF1A2A58.toInt(), 0xFF4E4277.toInt(), 0xFFB8634F.toInt(), 0xFF100A12.toInt(), 0xFFFFA060.toInt(), 0.45f)
        private val MORNING = Key(0xFF163C6E.toInt(), 0xFF27598C.toInt(), 0xFF5683AA.toInt(), 0xFF09121F.toInt(), 0xFFFFE0A0.toInt(), 0.26f)
        private val MIDDAY = Key(0xFF10376A.toInt(), 0xFF1D558E.toInt(), 0xFF3F78AD.toInt(), 0xFF08111E.toInt(), 0xFFFFF2CC.toInt(), 0.22f)
        private val AFTERNOON = Key(0xFF153668.toInt(), 0xFF305A88.toInt(), 0xFFB88A55.toInt(), 0xFF0D1019.toInt(), 0xFFFFC070.toInt(), 0.36f)
        private val SUNSET = Key(0xFF201B45.toInt(), 0xFF62325C.toInt(), 0xFFC25A3B.toInt(), 0xFF12090E.toInt(), 0xFFFF7A45.toInt(), 0.5f)
        private val DUSK = Key(0xFF0D1232.toInt(), 0xFF272152.toInt(), 0xFF552C58.toInt(), 0xFF07060C.toInt(), 0xFFB0507A.toInt(), 0.2f)

        /** Fundo escuro neutro (igual ao tema): ponto de partida da entrada. */
        val BLANK = SkyState(0xFF0B0D14.toInt(), 0xFF0B0D14.toInt(), 0xFF0B0D14.toInt(), 0xFF08090D.toInt(), 0, 0f, 0.5f, 0.5f)

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

            var top: Int; var upper: Int; var horizon: Int; var bottom: Int; var glow: Int; var alpha: Float
            val i = keys.indexOfFirst { it.first > nowMs }
            if (i <= 0) {
                val k = if (i == 0) keys.first().second else keys.last().second
                top = k.top; upper = k.upper; horizon = k.horizon; bottom = k.bottom; glow = k.glow; alpha = k.alpha
            } else {
                val (t0, a) = keys[i - 1]
                val (t1, b) = keys[i]
                val t = smooth((nowMs - t0).toFloat() / (t1 - t0).coerceAtLeast(1))
                top = mix(a.top, b.top, t); upper = mix(a.upper, b.upper, t)
                horizon = mix(a.horizon, b.horizon, t); bottom = mix(a.bottom, b.bottom, t)
                glow = mix(a.glow, b.glow, t); alpha = a.alpha + (b.alpha - a.alpha) * t
            }

            // Brilho: segue o arco do sol entre o amanhecer e o anoitecer; à noite, a lua fica
            // num ponto fixo. Na troca, o brilho apaga e acende para não "pular".
            val sunFrom = sunrise - 75 * MIN
            val sunTo = sunset + 75 * MIN
            val gx: Float; val gy: Float
            if (nowMs in sunFrom until sunTo) {
                val p = (nowMs - sunrise).toFloat() / (sunset - sunrise).coerceAtLeast(1)
                gx = 0.12f + 0.76f * p
                gy = 0.78f - 0.62f * sin(PI * p.coerceIn(0f, 1f)).toFloat()
            } else {
                gx = 0.8f; gy = 0.1f
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
            var desat = cloud * 0.48f
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
            fun weather(col: Int) = mix(darken(desaturate(col, desat), dark), tint, tintAmt)
            top = weather(top); upper = weather(upper); horizon = weather(horizon); bottom = weather(bottom)
            alpha *= 1f - cloud * 0.8f

            return SkyState(top, upper, horizon, bottom, glow, alpha, gx, gy)
        }

        private fun smooth(t: Float) = t * t * (3 - 2 * t)

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
 * bilinear: na tela vira um único quad texturizado, o mesmo custo de GPU do degradê antigo,
 * por mais camadas que o céu tenha. Só é redesenhado quando o estado muda.
 */
class SkyDrawable(initial: SkyState) : Drawable() {

    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val bitmapCanvas = Canvas(bitmap)
    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val paint = Paint(Paint.DITHER_FLAG)
    private var current = initial
    private var animator: ValueAnimator? = null

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
            interpolator = LinearInterpolator() // a curva já vem do smoothstep abaixo
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
        paint.shader = LinearGradient(
            0f, 0f, 0f, H.toFloat(),
            intArrayOf(s.top, s.upper, s.horizon, s.bottom),
            floatArrayOf(0f, 0.4f, 0.74f, 1f),
            Shader.TileMode.CLAMP,
        )
        bitmapCanvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)

        if (s.glowAlpha > 0.005f) {
            val a = (s.glowAlpha * 255).toInt().coerceIn(0, 255)
            val center = (s.glow and 0x00FFFFFF) or (a shl 24)
            val mid = (s.glow and 0x00FFFFFF) or ((a * 0.35f).toInt() shl 24)
            paint.shader = RadialGradient(
                s.glowX * W, s.glowY * H, W * 0.55f,
                intArrayOf(center, mid, 0),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP,
            )
            bitmapCanvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        }

        // Base escura (ancora os favoritos) e véu leve no topo (relógio).
        paint.shader = LinearGradient(
            0f, 0f, 0f, H.toFloat(),
            intArrayOf(0x33000000, 0, 0, 0x8C000000.toInt()),
            floatArrayOf(0f, 0.22f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        bitmapCanvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        paint.shader = null
        invalidateSelf()
    }

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
    }
}
