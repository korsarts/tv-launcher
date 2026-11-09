package com.korsarts.tvlauncher

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Busca e cache da previsão. Todas as fontes são HTTPS e sem chave:
 * - Open-Meteo (previsão e busca de cidade);
 * - ipwho.is / ipapi.co (localização aproximada pelo IP, só no modo automático).
 *
 * Roda num executor próprio para que um timeout de rede não atrase a carga da lista de apps.
 * Callbacks chegam na main thread; resultados de pedidos já superados são descartados.
 */
class WeatherRepository(context: Context, private val prefs: Prefs) {

    sealed class Result {
        class Success(val data: WeatherData) : Result()
        /** [noLocation]: não deu para descobrir onde a TV está (IP falhou e não há cidade manual). */
        class Failure(val noLocation: Boolean) : Result()
    }

    private val sp: SharedPreferences = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var searchGeneration = 0
    @Volatile private var closed = false

    /**
     * Último resultado salvo (para exibir no boot ou sem rede). Não depende da geração: roda
     * antes de qualquer [refresh] enfileirado depois dele, então nunca sobrescreve dado novo.
     */
    fun loadCached(callback: (WeatherData?) -> Unit) {
        executor.execute {
            val data = runCatching {
                val json = sp.getString(KEY_JSON, null) ?: return@runCatching null
                WeatherData.parse(json, sp.getString(KEY_PLACE, "").orEmpty(), sp.getLong(KEY_FETCHED_AT, 0))
            }.getOrNull()
            deliver { callback(data) }
        }
    }

    fun refresh(callback: (Result) -> Unit) {
        val gen = ++generation
        val manual = prefs.weatherManualLocation
        executor.execute {
            val location = manual ?: runCatching { ipLocation() }.getOrNull()
            val result = if (location == null) {
                Result.Failure(noLocation = true)
            } else {
                val data = runCatching { fetch(location) }.getOrNull()
                if (data != null) Result.Success(data) else Result.Failure(noLocation = false)
            }
            deliver { if (gen == generation) callback(result) }
        }
    }

    /** null = falha de rede; lista vazia = nenhuma cidade encontrada. */
    fun searchCity(query: String, callback: (List<WeatherLocation>?) -> Unit) {
        val gen = ++searchGeneration
        executor.execute {
            val results = runCatching { geocode(query) }.getOrNull()
            deliver { if (gen == searchGeneration) callback(results) }
        }
    }

    /** Local mudou: o cache não serve mais e qualquer busca em andamento é descartada. */
    fun clearCache() {
        generation++
        sp.edit().remove(KEY_JSON).remove(KEY_PLACE).remove(KEY_FETCHED_AT).apply()
    }

    /** Nome da cidade detectada pelo IP na última vez, se houver. */
    val autoPlaceName: String? get() = sp.getString(KEY_IP_NAME, null)

    fun shutdown() {
        closed = true
        executor.shutdownNow()
    }

    private fun deliver(block: () -> Unit) {
        if (!closed) main.post { if (!closed) block() }
    }

    // ------------------------------------------------------------------ rede (worker thread)

    private fun fetch(location: WeatherLocation): WeatherData {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${coord(location.lat)}&longitude=${coord(location.lon)}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,cloud_cover" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&timezone=auto&timeformat=unixtime&forecast_days=7"
        val json = get(url)
        val now = System.currentTimeMillis()
        val data = WeatherData.parse(json, location.name, now) // valida antes de salvar
        sp.edit()
            .putString(KEY_JSON, json)
            .putString(KEY_PLACE, location.name)
            .putLong(KEY_FETCHED_AT, now)
            .apply()
        return data
    }

    /** Localização aproximada pelo IP, cacheada por [IP_TTL_MS]. */
    private fun ipLocation(): WeatherLocation? {
        val cachedAt = sp.getLong(KEY_IP_AT, 0)
        val cachedName = sp.getString(KEY_IP_NAME, null)
        val cached = if (cachedName != null) {
            WeatherLocation(cachedName, sp.getFloat(KEY_IP_LAT, 0f).toDouble(), sp.getFloat(KEY_IP_LON, 0f).toDouble())
        } else {
            null
        }
        if (cached != null && System.currentTimeMillis() - cachedAt < IP_TTL_MS) return cached

        val fresh = runCatching { ipWhoIs() }.getOrNull() ?: runCatching { ipApiCo() }.getOrNull()
        if (fresh == null) return cached // melhor uma localização antiga do que nenhuma
        sp.edit()
            .putString(KEY_IP_NAME, fresh.name)
            .putFloat(KEY_IP_LAT, fresh.lat.toFloat())
            .putFloat(KEY_IP_LON, fresh.lon.toFloat())
            .putLong(KEY_IP_AT, System.currentTimeMillis())
            .apply()
        return fresh
    }

    private fun ipWhoIs(): WeatherLocation? {
        val o = JSONObject(get("https://ipwho.is/?fields=success,city,region,country,latitude,longitude"))
        if (!o.optBoolean("success", false)) return null
        return locationFrom(o, "city", "latitude", "longitude")
    }

    private fun ipApiCo(): WeatherLocation? {
        val o = JSONObject(get("https://ipapi.co/json/"))
        if (o.optBoolean("error", false)) return null
        return locationFrom(o, "city", "latitude", "longitude")
    }

    private fun locationFrom(o: JSONObject, nameKey: String, latKey: String, lonKey: String): WeatherLocation? {
        val name = o.optString(nameKey).takeIf { it.isNotBlank() } ?: return null
        val lat = o.optDouble(latKey)
        val lon = o.optDouble(lonKey)
        if (lat.isNaN() || lon.isNaN()) return null
        return WeatherLocation(name, lat, lon)
    }

    private fun geocode(query: String): List<WeatherLocation> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=pt&format=json"))
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val base = locationFrom(r, "name", "latitude", "longitude") ?: return@mapNotNull null
            val region = listOf(r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
                .ifEmpty { null }
            WeatherLocation(base.name, base.lat, base.lon, region)
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "TVLauncher (Android)")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Coordenada com ponto decimal, independente do idioma do aparelho. */
    private fun coord(v: Double) = String.format(Locale.US, "%.4f", v)

    companion object {
        private const val TIMEOUT_MS = 8000
        private const val IP_TTL_MS = 12 * 60 * 60 * 1000L

        private const val KEY_JSON = "json"
        private const val KEY_PLACE = "place"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_IP_NAME = "ip_name"
        private const val KEY_IP_LAT = "ip_lat"
        private const val KEY_IP_LON = "ip_lon"
        private const val KEY_IP_AT = "ip_at"
    }
}
