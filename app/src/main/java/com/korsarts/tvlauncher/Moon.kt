package com.korsarts.tvlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/** Fase da lua calculada no aparelho, pela duração média do mês sinódico (erro de poucas horas). */
object Moon {
    private const val SYNODIC_MS = 29.530588853 * 24 * 60 * 60 * 1000
    /** Lua nova de referência: 6 de janeiro de 2000, 18:14 UTC. */
    private const val REFERENCE_NEW_MS = 947_182_440_000L

    /** Posição no ciclo: 0 = nova, 0.25 = quarto crescente, 0.5 = cheia, 0.75 = quarto minguante. */
    fun cycle(ms: Long): Double {
        val f = ((ms - REFERENCE_NEW_MS) / SYNODIC_MS) % 1.0
        return if (f < 0) f + 1 else f
    }

    /** Nome da fase (uma de oito). */
    fun label(cycle: Double): Int = when (((cycle * 8) + 0.5).toInt() % 8) {
        0 -> R.string.moon_new
        1 -> R.string.moon_waxing_crescent
        2 -> R.string.moon_first_quarter
        3 -> R.string.moon_waxing_gibbous
        4 -> R.string.moon_full
        5 -> R.string.moon_waning_gibbous
        6 -> R.string.moon_last_quarter
        else -> R.string.moon_waning_crescent
    }
}

/**
 * Disco da lua com a parte iluminada da fase. No hemisfério norte a crescente acende pela
 * direita; no sul ([southern]), pela esquerda.
 */
class MoonDrawable(private val sizePx: Int, litColor: Int, darkColor: Int) : Drawable() {

    private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = litColor }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darkColor }
    private val path = Path()
    private val disc = RectF()
    private val terminator = RectF()
    private var cycle = 0.0
    private var southern = false

    fun set(cycle: Double, southern: Boolean) {
        this.cycle = cycle
        this.southern = southern
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = minOf(b.width(), b.height()) / 2f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        canvas.drawCircle(cx, cy, r, dark)

        // c: +1 na lua nova, 0 nos quartos, -1 na cheia. O terminador é meia elipse de raio r·|c|.
        val c = cos(2 * PI * cycle).toFloat()
        disc.set(cx - r, cy - r, cx + r, cy + r)
        terminator.set(cx - r * abs(c), cy - r, cx + r * abs(c), cy + r)
        path.rewind()
        path.addArc(disc, -90f, 180f) // meia-lua da direita, de cima para baixo
        // Volta ao topo pela direita (crescente fina) ou pela esquerda (gibosa).
        path.arcTo(terminator, 90f, if (c > 0) -180f else 180f)
        path.close()

        val mirror = (cycle > 0.5) != southern
        if (mirror) {
            canvas.save()
            canvas.scale(-1f, 1f, cx, cy)
            canvas.drawPath(path, lit)
            canvas.restore()
        } else {
            canvas.drawPath(path, lit)
        }
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
