package com.korsarts.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade opcional.
 *
 * O Android TV de fábrica não oferece seletor de launcher: o botão HOME sempre abre o
 * launcher do sistema (com.google.android.tvlauncher no Mi TV Stick). Este serviço:
 *  1. consome KEYCODE_HOME e abre o nosso launcher;
 *  2. se o launcher original aparecer mesmo assim (ex.: após o boot), troca pelo nosso.
 * Não lê conteúdo de janelas (canRetrieveWindowContent=false).
 */
class HomeButtonService : AccessibilityService() {

    private lateinit var prefs: Prefs
    private var lastLaunch = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!::prefs.isInitialized || !prefs.redirectStockLauncher) return
        if (event.packageName?.toString() in STOCK_LAUNCHERS) goHome()
    }

    override fun onInterrupt() = Unit

    private fun goHome() {
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch < 400) return // evita disparos duplicados
        lastLaunch = now
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        private val STOCK_LAUNCHERS = setOf(
            "com.google.android.tvlauncher",          // Android TV (Mi TV Stick, Mi Box)
            "com.google.android.apps.tv.launcherx",   // Google TV
            "com.mitv.tvhome",                        // PatchWall (Xiaomi)
        )
    }
}
