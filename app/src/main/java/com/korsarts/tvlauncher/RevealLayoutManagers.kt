package com.korsarts.tvlauncher

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Ao focar um item, rola suavemente para que ele fique a pelo menos [peekPx] da borda: o próximo
 * card sempre aparece "espiando", e a navegação não fica colada no limite da tela.
 * O cálculo usa as posições atuais, então teclas repetidas emendam as rolagens sem acumular erro.
 */
internal fun revealChild(parent: RecyclerView, child: View, immediate: Boolean, horizontal: Boolean, peekPx: Int): Boolean {
    val lp = child.layoutParams as RecyclerView.LayoutParams
    val start: Int
    val end: Int
    val viewStart: Int
    val viewEnd: Int
    if (horizontal) {
        start = child.left - lp.leftMargin
        end = child.right + lp.rightMargin
        viewStart = parent.paddingLeft
        viewEnd = parent.width - parent.paddingRight
    } else {
        start = child.top - lp.topMargin
        end = child.bottom + lp.bottomMargin
        viewStart = parent.paddingTop
        viewEnd = parent.height - parent.paddingBottom
    }
    // Item maior que a área útil: sem margem, só alinha o início.
    val peek = peekPx.coerceAtMost(((viewEnd - viewStart) - (end - start)) / 2).coerceAtLeast(0)
    val delta = when {
        start < viewStart + peek -> start - (viewStart + peek)
        end > viewEnd - peek -> end - (viewEnd - peek)
        else -> 0
    }
    if (delta == 0) return false
    val dx = if (horizontal) delta else 0
    val dy = if (horizontal) 0 else delta
    if (immediate) parent.scrollBy(dx, dy)
    else parent.smoothScrollBy(dx, dy, Motion.STANDARD, Motion.SCROLL_MS)
    return true
}

/** Garante a visibilidade (com margem) de um item já existente, ex.: após reordenar. */
internal fun RecyclerView.revealView(child: View) {
    layoutManager?.requestChildRectangleOnScreen(this, child, Rect(), false, false)
}

class RevealLinearLayoutManager(context: Context, private val peekPx: Int) :
    LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean = revealChild(parent, child, immediate, horizontal = true, peekPx)
}

class RevealGridLayoutManager(context: Context, spanCount: Int, private val peekPx: Int) :
    GridLayoutManager(context, spanCount) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean = revealChild(parent, child, immediate, horizontal = false, peekPx)
}
