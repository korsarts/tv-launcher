package com.korsarts.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/** Local usado para a previsão. [region] é só texto de apoio ("Estado, País"). */
class WeatherLocation(
    val name: String,
    val lat: Double,
    val lon: Double,
    val region: String? = null,
)

/**
 * Previsão da Open-Meteo já decodificada. Horas e dias ficam inteiros (não recortados no momento
 * da busca) para que um cache antigo continue mostrando "as próximas" a partir de agora.
 */
class WeatherData(
    val place: String,
    val timeZone: TimeZone,
    /** Momento da busca, em ms. */
    val fetchedAt: Long,
    val current: Current,
    private val hours: List<Hour>,
    private val days: List<Day>,
) {
    class Current(
        val temp: Double,
        val feelsLike: Double,
        val humidity: Int,
        val windKmh: Double,
        val code: Int,
        val isDay: Boolean,
    )

    /** [time] em segundos (início da hora). */
    class Hour(val time: Long, val temp: Double, val code: Int, val isDay: Boolean, val precip: Int?)

    /** [time] em segundos (meia-noite local do dia). */
    class Day(val time: Long, val max: Double, val min: Double, val code: Int, val precip: Int?)

    /** Próximas [count] horas depois de [nowMs]. */
    fun upcomingHours(nowMs: Long, count: Int): List<Hour> {
        val now = nowMs / 1000
        return hours.asSequence().filter { it.time > now }.take(count).toList()
    }

    /** Próximos [count] dias, começando amanhã (o dia de hoje vai no bloco "atual"). */
    fun upcomingDays(nowMs: Long, count: Int): List<Day> {
        val now = nowMs / 1000
        return days.asSequence().filter { it.time > now }.take(count).toList()
    }

    /** O dia que contém [nowMs] (mín/máx de hoje), se ainda estiver na previsão. */
    fun today(nowMs: Long): Day? {
        val now = nowMs / 1000
        return days.firstOrNull { now >= it.time && now < it.time + DAY_SECONDS }
    }

    companion object {
        private const val DAY_SECONDS = 24 * 60 * 60L

        /** Decodifica a resposta de `/v1/forecast` pedida com `timeformat=unixtime`. */
        fun parse(json: String, place: String, fetchedAt: Long): WeatherData {
            val root = JSONObject(json)
            val tz = TimeZone.getTimeZone(root.optString("timezone", "GMT"))

            val c = root.getJSONObject("current")
            val current = Current(
                temp = c.getDouble("temperature_2m"),
                feelsLike = c.optDouble("apparent_temperature", c.getDouble("temperature_2m")),
                humidity = c.optInt("relative_humidity_2m", 0),
                windKmh = c.optDouble("wind_speed_10m", 0.0),
                code = c.optInt("weather_code", 0),
                isDay = c.optInt("is_day", 1) == 1,
            )

            val h = root.getJSONObject("hourly")
            val hTime = h.getJSONArray("time")
            val hTemp = h.getJSONArray("temperature_2m")
            val hCode = h.getJSONArray("weather_code")
            val hDay = h.optJSONArray("is_day")
            val hPrecip = h.optJSONArray("precipitation_probability")
            val hours = (0 until hTime.length()).mapNotNull { i ->
                if (hTemp.isNull(i)) return@mapNotNull null
                Hour(
                    time = hTime.getLong(i),
                    temp = hTemp.getDouble(i),
                    code = hCode.optInt(i, 0),
                    isDay = hDay?.optInt(i, 1) != 0,
                    precip = hPrecip.intOrNull(i),
                )
            }

            val d = root.getJSONObject("daily")
            val dTime = d.getJSONArray("time")
            val dMax = d.getJSONArray("temperature_2m_max")
            val dMin = d.getJSONArray("temperature_2m_min")
            val dCode = d.getJSONArray("weather_code")
            val dPrecip = d.optJSONArray("precipitation_probability_max")
            val days = (0 until dTime.length()).mapNotNull { i ->
                if (dMax.isNull(i) || dMin.isNull(i)) return@mapNotNull null
                Day(
                    time = dTime.getLong(i),
                    max = dMax.getDouble(i),
                    min = dMin.getDouble(i),
                    code = dCode.optInt(i, 0),
                    precip = dPrecip.intOrNull(i),
                )
            }

            return WeatherData(place, tz, fetchedAt, current, hours, days)
        }

        private fun JSONArray?.intOrNull(i: Int): Int? =
            if (this == null || i >= length() || isNull(i)) null else optDouble(i).let { Math.round(it).toInt() }
    }
}

/** Códigos WMO usados pela Open-Meteo → ícone e descrição. */
object WeatherCode {

    fun icon(code: Int, isDay: Boolean): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_weather_clear_day else R.drawable.ic_weather_clear_night
        2 -> if (isDay) R.drawable.ic_weather_partly_day else R.drawable.ic_weather_partly_night
        3 -> R.drawable.ic_weather_cloudy
        45, 48 -> R.drawable.ic_weather_fog
        51, 53, 55, 56, 57 -> R.drawable.ic_weather_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_weather_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
        95, 96, 99 -> R.drawable.ic_weather_thunder
        else -> R.drawable.ic_weather_cloudy
    }

    fun label(code: Int): Int = when (code) {
        0 -> R.string.wmo_clear
        1 -> R.string.wmo_mainly_clear
        2 -> R.string.wmo_partly_cloudy
        3 -> R.string.wmo_overcast
        45, 48 -> R.string.wmo_fog
        51, 53, 55 -> R.string.wmo_drizzle
        56, 57 -> R.string.wmo_freezing_drizzle
        61 -> R.string.wmo_rain_light
        63 -> R.string.wmo_rain
        65 -> R.string.wmo_rain_heavy
        66, 67 -> R.string.wmo_freezing_rain
        71, 73, 75, 77 -> R.string.wmo_snow
        80, 81 -> R.string.wmo_showers
        82 -> R.string.wmo_showers_heavy
        85, 86 -> R.string.wmo_snow_showers
        95 -> R.string.wmo_thunderstorm
        96, 99 -> R.string.wmo_thunderstorm_hail
        else -> R.string.wmo_unknown
    }
}
