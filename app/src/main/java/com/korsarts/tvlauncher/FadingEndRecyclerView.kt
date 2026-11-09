package com.korsarts.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView horizontal cujo conteúdo some em degradê de alpha na borda direita da view
 * (que coincide com a borda da tela). O conteúdo é desenhado numa camada e um gradiente
 * DST_OUT vai apagando o alpha até zero no limite direito. O fade de borda nativo do View
 * não serve aqui: só aparece com rolagem pendente e fica fraco demais.
 */
class FadingEndRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    private val fadeLength = resources.getDimensionPixelSize(R.dimen.row_fade_length)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var shaderWidth = -1

    // dispatchDraw, não draw(): com overScrollMode=never o RecyclerView liga willNotDraw e o
    // sistema pula draw() desta view, chamando só dispatchDraw() para desenhar os filhos.
    override fun dispatchDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || fadeLength <= 0) {
            super.dispatchDraw(canvas)
            return
        }
        if (shaderWidth != w) {
            val start = (w - fadeLength).toFloat()
            fadePaint.shader = LinearGradient(
                start, 0f, w.toFloat(), 0f,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP,
            )
            shaderWidth = w
        }
        val save = canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect((w - fadeLength).toFloat(), 0f, w.toFloat(), h.toFloat(), fadePaint)
        canvas.restoreToCount(save)
    }
}
