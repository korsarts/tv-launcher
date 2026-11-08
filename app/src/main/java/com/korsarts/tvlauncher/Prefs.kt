package com.korsarts.tvlauncher

import android.content.Context
import android.content.SharedPreferences

/** Preferências persistidas. Favoritos guardam ordem (lista separada por '\n'). */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var favorites: List<String>
        get() = sp.getString(KEY_FAVORITES, null)?.split('\n')?.filter { it.isNotBlank() }
            ?: DEFAULT_FAVORITES
        set(value) = sp.edit().putString(KEY_FAVORITES, value.joinToString("\n")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN, value).apply()

    var showPhoneApps: Boolean
        get() = sp.getBoolean(KEY_PHONE_APPS, true)
        set(value) = sp.edit().putBoolean(KEY_PHONE_APPS, value).apply()

    var showHidden: Boolean
        get() = sp.getBoolean(KEY_SHOW_HIDDEN, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()

    /** Se o serviço de acessibilidade deve trocar o launcher original pelo nosso quando ele aparecer. */
    var redirectStockLauncher: Boolean
        get() = sp.getBoolean(KEY_REDIRECT, true)
        set(value) = sp.edit().putBoolean(KEY_REDIRECT, value).apply()

    fun toggleFavorite(pkg: String) {
        val list = favorites.toMutableList()
        if (!list.remove(pkg)) list.add(pkg)
        favorites = list
    }

    /**
     * Troca [pkg] com o próximo favorito na direção [delta] que esteja em [visible] (os salvos mas
     * não instalados são pulados). Retorna false se já estava na ponta.
     */
    fun moveFavorite(pkg: String, delta: Int, visible: Set<String>): Boolean {
        val list = favorites.toMutableList()
        val i = list.indexOf(pkg)
        if (i < 0) return false
        var j = i + delta
        while (j in list.indices && list[j] !in visible) j += delta
        if (j !in list.indices) return false
        list[i] = list[j].also { list[j] = list[i] }
        favorites = list
        return true
    }

    fun toggleHidden(pkg: String) {
        val set = hidden.toMutableSet()
        if (!set.remove(pkg)) set.add(pkg)
        hidden = set
    }

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_PHONE_APPS = "show_phone_apps"
        private const val KEY_SHOW_HIDDEN = "show_hidden"
        private const val KEY_REDIRECT = "redirect_stock"

        /** Favoritos iniciais (só aparecem se instalados). */
        private val DEFAULT_FAVORITES = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "com.spotify.tv.android",
            "com.google.android.youtube.tvmusic",
            "com.android.vending",
        )
    }
}
