package com.korsarts.tvlauncher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Dois filhos empilhados: cabeçalho (altura livre) e corpo (altura = altura visível menos o
 * padding superior). A view fica mais alta que a tela; "recolher" é só um translationY que tira
 * o cabeçalho de cena e deixa o corpo ocupando a tela toda. Nenhum relayout durante a animação.
 */
class CollapsingLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var isCollapsed = false
        private set

    private var lastHeaderHeight = -1

    private val header: View get() = getChildAt(0)
    private val body: View get() = getChildAt(1)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(childCount == 2) { "CollapsingLayout precisa de exatamente 2 filhos" }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val visibleHeight = MeasureSpec.getSize(heightMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.EXACTLY)

        header.measure(childWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val bodyHeight = (visibleHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        body.measure(childWidth, MeasureSpec.makeMeasureSpec(bodyHeight, MeasureSpec.EXACTLY))

        setMeasuredDimension(width, paddingTop + header.measuredHeight + bodyHeight + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = paddingLeft
        var top = paddingTop
        header.layout(left, top, left + header.measuredWidth, top + header.measuredHeight)
        top += header.measuredHeight
        body.layout(left, top, left + body.measuredWidth, top + body.measuredHeight)

        // Cabeçalho mudou de altura (ex.: favoritos vazios) enquanto recolhido: acompanha sem animar.
        val headerHeight = header.measuredHeight
        if (headerHeight != lastHeaderHeight) {
            lastHeaderHeight = headerHeight
            if (isCollapsed) {
                animate().cancel()
                translationY = -headerHeight.toFloat()
            }
        }
    }

    fun setCollapsed(collapsed: Boolean, animate: Boolean = true) {
        if (collapsed == isCollapsed) return
        isCollapsed = collapsed
        val target = if (collapsed) -header.height.toFloat() else 0f
        animate().cancel()
        if (animate && isLaidOut) {
            animate().translationY(target)
                .setDuration(Motion.COLLAPSE_MS)
                .setInterpolator(Motion.EMPHASIZED)
                .start()
        } else {
            translationY = target
        }
    }
}
