package com.korsarts.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /** Consulta o PackageManager. Executar fora da main thread (carrega drawables). */
    fun load(includePhoneApps: Boolean): List<AppEntry> {
        val result = LinkedHashMap<String, AppEntry>()

        query(Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { ri ->
            toEntry(ri, leanback = true)?.let { if (it.packageName !in result) result[it.packageName] = it }
        }
        if (includePhoneApps) {
            query(Intent.CATEGORY_LAUNCHER).forEach { ri ->
                if (!result.containsKey(ri.activityInfo.packageName)) {
                    toEntry(ri, leanback = false)?.let { result[it.packageName] = it }
                }
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun toEntry(ri: ResolveInfo, leanback: Boolean): AppEntry? {
        val ai = ri.activityInfo ?: return null
        if (ai.packageName == context.packageName) return null
        if (!ai.enabled || !ai.applicationInfo.enabled) return null

        val banner = if (leanback) {
            runCatching { ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm) }.getOrNull()
        } else null

        val lastUpdate = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ai.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)

        return AppEntry(
            packageName = ai.packageName,
            component = ComponentName(ai.packageName, ai.name),
            label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName },
            isLeanback = leanback,
            isSystem = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            lastUpdate = lastUpdate,
            banner = banner,
            icon = ri.loadIcon(pm),
        )
    }
}
