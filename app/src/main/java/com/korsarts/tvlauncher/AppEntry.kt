package com.korsarts.tvlauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable

/** Um app instalável/lançável. Identificado pelo nome do pacote (um card por pacote). */
data class AppEntry(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    val isLeanback: Boolean,
    val isSystem: Boolean,
    val lastUpdate: Long,
    val banner: Drawable?,
    val icon: Drawable,
) {
    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(if (isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
