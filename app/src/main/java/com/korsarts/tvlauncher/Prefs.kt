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

    /** Cards por linha na grade (4 = grandes, 5 = médios, 6 = pequenos); os favoritos acompanham. */
    var gridColumns: Int
        get() = sp.getInt(KEY_GRID_COLUMNS, 5).coerceIn(4, 6)
        set(value) = sp.edit().putInt(KEY_GRID_COLUMNS, value).apply()

    /** Indicadores de rede, espaço e RAM no topo da tela inicial. */
    var showSystemStatus: Boolean
        get() = sp.getBoolean(KEY_SYSTEM_STATUS, true)
        set(value) = sp.edit().putBoolean(KEY_SYSTEM_STATUS, value).apply()

    /** Procurar versão nova do launcher sozinho (no máximo uma vez por dia). */
    var autoUpdateCheck: Boolean
        get() = sp.getBoolean(KEY_UPDATE_AUTO, true)
        set(value) = sp.edit().putBoolean(KEY_UPDATE_AUTO, value).apply()

    /** Momento da última consulta de versão que deu certo, em ms. */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_UPDATE_CHECKED_AT, 0)
        set(value) = sp.edit().putLong(KEY_UPDATE_CHECKED_AT, value).apply()

    /** Versão nova já encontrada ([Updater.Release.toJson]), para continuar oferecendo após reiniciar. */
    var pendingUpdate: String?
        get() = sp.getString(KEY_UPDATE_PENDING, null)
        set(value) = sp.edit().putString(KEY_UPDATE_PENDING, value).apply()

    /** Intervalo da atualização automática do clima, em minutos (0 = desligada). */
    var weatherIntervalMin: Int
        get() = sp.getInt(KEY_WEATHER_INTERVAL, 30)
        set(value) = sp.edit().putInt(KEY_WEATHER_INTERVAL, value).apply()

    var weatherEnabled: Boolean
        get() = sp.getBoolean(KEY_WEATHER_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_WEATHER_ENABLED, value).apply()

    /** Painel de próximas datas (feriados e comemorativas) revezando com a previsão. */
    var holidaysEnabled: Boolean
        get() = sp.getBoolean(KEY_HOLIDAYS_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_HOLIDAYS_ENABLED, value).apply()

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
        private const val KEY_HOLIDAYS_ENABLED = "holidays_enabled"
        private const val KEY_SKY_ENABLED = "sky_enabled"
        private const val KEY_SKY_STYLE = "sky_style"
        private const val KEY_GRID_COLUMNS = "grid_columns"
        private const val KEY_SYSTEM_STATUS = "system_status"
        private const val KEY_UPDATE_AUTO = "update_auto"
        private const val KEY_UPDATE_CHECKED_AT = "update_checked_at"
        private const val KEY_UPDATE_PENDING = "update_pending"
        private const val KEY_WEATHER_INTERVAL = "weather_interval_min"
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
