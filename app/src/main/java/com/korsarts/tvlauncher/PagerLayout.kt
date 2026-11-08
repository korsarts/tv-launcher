package com.korsarts.tvlauncher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Duas páginas empilhadas verticalmente, cada uma com a altura visível da tela. A view fica com
 * o dobro da altura; trocar de página é só um translationY (nenhum relayout durante a animação).
 */
class PagerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** true = segunda página (grade de apps) em cena. */
    var isSecondPage = false
        private set

    private var pageHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        check(childCount == 2) { "PagerLayout precisa de exatamente 2 filhos" }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        for (i in 0 until 2) getChildAt(i).measure(childWidth, childHeight)
        setMeasuredDimension(width, height * 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val first: View = getChildAt(0)
        val second: View = getChildAt(1)
        val h = first.measuredHeight
        first.layout(0, 0, first.measuredWidth, h)
        second.layout(0, h, second.measuredWidth, h + second.measuredHeight)

        // Altura da tela mudou (raro na TV): reposiciona sem animar.
        if (h != pageHeight) {
            pageHeight = h
            animate().cancel()
            translationY = if (isSecondPage) -h.toFloat() else 0f
        }
    }

    fun showSecondPage(second: Boolean, animate: Boolean = true) {
        if (second == isSecondPage) return
        isSecondPage = second
        val target = if (second) -pageHeight.toFloat() else 0f
        animate().cancel()
        if (animate && isLaidOut) {
            animate().translationY(target)
                .setDuration(Motion.PAGE_MS)
                .setInterpolator(Motion.EMPHASIZED)
                .start()
        } else {
            translationY = target
        }
    }
}
