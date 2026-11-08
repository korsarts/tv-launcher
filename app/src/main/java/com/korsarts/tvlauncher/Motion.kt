package com.korsarts.tvlauncher

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/** Curvas e durações compartilhadas (padrão Material "emphasized"/"standard"). */
object Motion {
    /** Desaceleração suave: bom para foco e rolagem. */
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Entradas/saídas de painéis. */
    val EMPHASIZED: Interpolator = PathInterpolator(0.3f, 0f, 0.1f, 1f)

    const val FOCUS_MS = 160L
    const val PRESS_MS = 90L
    const val SCROLL_MS = 240
    /** Troca entre a página inicial e a grade (desloca a tela inteira). */
    const val PAGE_MS = 360L
    const val PANEL_MS = 240L

    const val FOCUS_SCALE = 1.1f
    const val PRESS_SCALE = 1.04f
}
