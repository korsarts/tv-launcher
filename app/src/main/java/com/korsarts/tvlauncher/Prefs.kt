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

    /** Fundo de céu dinâmico (hora do dia + clima); desligado = fundo escuro fixo. */
    var skyEnabled: Boolean
        get() = sp.getBoolean(KEY_SKY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_SKY_ENABLED, value).apply()

    /** Id do [SkyStyle] escolhido. */
    var skyStyle: String?
        get() = sp.getString(KEY_SKY_STYLE, null)
        set(value) = sp.edit().putString(KEY_SKY_STYLE, value).apply()

    var weatherEnabled: Boolean
        get() = sp.getBoolean(KEY_WEATHER_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_WEATHER_ENABLED, value).apply()

    /** Cidade escolhida na busca; null = localização automática pelo IP. */
    var weatherManualLocation: WeatherLocation?
        get() {
            val name = sp.getString(KEY_WEATHER_NAME, null) ?: return null
            val lat = sp.getString(KEY_WEATHER_LAT, null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString(KEY_WEATHER_LON, null)?.toDoubleOrNull() ?: return null
            return WeatherLocation(name, lat, lon, sp.getString(KEY_WEATHER_REGION, null))
        }
        set(value) {
            val e = sp.edit()
            if (value == null) {
                e.remove(KEY_WEATHER_NAME).remove(KEY_WEATHER_REGION).remove(KEY_WEATHER_LAT).remove(KEY_WEATHER_LON)
            } else {
                // Double.toString usa sempre ponto decimal: leitura independe do idioma.
                e.putString(KEY_WEATHER_NAME, value.name)
                    .putString(KEY_WEATHER_REGION, value.region)
                    .putString(KEY_WEATHER_LAT, value.lat.toString())
                    .putString(KEY_WEATHER_LON, value.lon.toString())
            }
            e.apply()
        }

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
        private const val KEY_WEATHER_ENABLED = "weather_enabled"
        private const val KEY_SKY_ENABLED = "sky_enabled"
        private const val KEY_SKY_STYLE = "sky_style"
        private const val KEY_WEATHER_NAME = "weather_name"
        private const val KEY_WEATHER_REGION = "weather_region"
        private const val KEY_WEATHER_LAT = "weather_lat"
        private const val KEY_WEATHER_LON = "weather_lon"

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
