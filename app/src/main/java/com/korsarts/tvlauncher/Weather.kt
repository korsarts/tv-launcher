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
    /** Latitude da cidade (hemisfério sul espelha a lua); 0 em caches antigos. */
    val latitude: Double = 0.0,
    /** Qualidade do ar; null se a consulta falhou (ela é opcional). */
    val air: AirQuality? = null,
) {
    class Current(
        val temp: Double,
        val feelsLike: Double,
        val humidity: Int,
        val windKmh: Double,
        val code: Int,
        val isDay: Boolean,
        /** Cobertura de nuvens em % (ausente em caches antigos). */
        val cloudCover: Int? = null,
        /** Índice UV agora (ausente em caches antigos). */
        val uv: Double? = null,
    )

    /** [usAqi]: índice de qualidade do ar na escala dos EUA (0 a 500). */
    class AirQuality(val usAqi: Int)

    /** Aviso de tempo ruim nas próximas horas. [time] em segundos (início da hora). */
    class Alert(val kind: Kind, val time: Long, val precip: Int?) {
        enum class Kind { STORM, HEAVY_RAIN, RAIN }
    }

    /** [time] em segundos (início da hora). */
    class Hour(
        val time: Long,
        val temp: Double,
        val code: Int,
        val isDay: Boolean,
        val precip: Int?,
        /** Chuva na hora, em mm (ausente em caches antigos). */
        val precipMm: Double? = null,
    )

    /** [time] em segundos (meia-noite local do dia). */
    class Day(
        val time: Long,
        val max: Double,
        val min: Double,
        val code: Int,
        val precip: Int?,
        /** Nascer/pôr do sol em segundos (ausentes em caches antigos). */
        val sunrise: Long? = null,
        val sunset: Long? = null,
    )

    /**
     * Nascer e pôr do sol (em ms) do dia que contém [ms]. Sem o dado (cache antigo ou fora da
     * previsão), estima 6h/18h no fuso da cidade.
     */
    fun sunTimes(ms: Long): Pair<Long, Long> {
        val s = ms / 1000
        val day = days.firstOrNull { s >= it.time && s < it.time + DAY_SECONDS }
        if (day?.sunrise != null && day.sunset != null) return day.sunrise * 1000 to day.sunset * 1000
        val offset = timeZone.getOffset(ms)
        val localMidnight = (ms + offset).floorDiv(DAY_MS) * DAY_MS - offset
        return localMidnight + 6 * HOUR_MS to localMidnight + 18 * HOUR_MS
    }

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

    /**
     * O aviso mais grave das próximas [ALERT_HOURS] horas: tempestade, depois chuva forte, depois
     * chuva provável (esta só nas próximas [RAIN_HOURS] horas e se não estiver chovendo agora).
     */
    fun alert(nowMs: Long): Alert? {
        val now = nowMs / 1000
        val next = hours.filter { it.time + HOUR_SECONDS > now && it.time <= now + ALERT_HOURS * HOUR_SECONDS }
        next.firstOrNull { it.code in STORM_CODES }
            ?.let { return Alert(Alert.Kind.STORM, it.time, it.precip) }
        next.firstOrNull { it.code in HEAVY_CODES || (it.precipMm ?: 0.0) >= HEAVY_MM }
            ?.let { return Alert(Alert.Kind.HEAVY_RAIN, it.time, it.precip) }
        if (current.code in WET_CODES) return null
        return next.firstOrNull { it.time > now && it.time <= now + RAIN_HOURS * HOUR_SECONDS && (it.precip ?: 0) >= RAIN_CHANCE }
            ?.let { Alert(Alert.Kind.RAIN, it.time, it.precip) }
    }

    companion object {
        private const val DAY_SECONDS = 24 * 60 * 60L
        private const val HOUR_SECONDS = 60 * 60L
        private const val ALERT_HOURS = 12
        private const val RAIN_HOURS = 3
        private const val RAIN_CHANCE = 70
        private const val HEAVY_MM = 7.5
        private val STORM_CODES = setOf(95, 96, 99)
        private val HEAVY_CODES = setOf(65, 67, 82)
        /** Já está chovendo/nevando: não faz sentido avisar "chuva provável". */
        private val WET_CODES = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99)
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS

        /**
         * Decodifica a resposta de `/v1/forecast` pedida com `timeformat=unixtime`. [airJson] é a
         * resposta (opcional) de `/v1/air-quality`; se estiver ausente ou inválida, fica sem o ar.
         */
        fun parse(json: String, place: String, fetchedAt: Long, airJson: String? = null): WeatherData {
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
                cloudCover = if (c.has("cloud_cover") && !c.isNull("cloud_cover")) c.optInt("cloud_cover") else null,
                uv = if (c.has("uv_index") && !c.isNull("uv_index")) c.optDouble("uv_index") else null,
            )

            val h = root.getJSONObject("hourly")
            val hTime = h.getJSONArray("time")
            val hTemp = h.getJSONArray("temperature_2m")
            val hCode = h.getJSONArray("weather_code")
            val hDay = h.optJSONArray("is_day")
            val hPrecip = h.optJSONArray("precipitation_probability")
            val hMm = h.optJSONArray("precipitation")
            val hours = (0 until hTime.length()).mapNotNull { i ->
                if (hTemp.isNull(i)) return@mapNotNull null
                Hour(
                    time = hTime.getLong(i),
                    temp = hTemp.getDouble(i),
                    code = hCode.optInt(i, 0),
                    isDay = hDay?.optInt(i, 1) != 0,
                    precip = hPrecip.intOrNull(i),
                    precipMm = if (hMm == null || i >= hMm.length() || hMm.isNull(i)) null else hMm.optDouble(i),
                )
            }

            val d = root.getJSONObject("daily")
            val dTime = d.getJSONArray("time")
            val dMax = d.getJSONArray("temperature_2m_max")
            val dMin = d.getJSONArray("temperature_2m_min")
            val dCode = d.getJSONArray("weather_code")
            val dPrecip = d.optJSONArray("precipitation_probability_max")
            val dSunrise = d.optJSONArray("sunrise")
            val dSunset = d.optJSONArray("sunset")
            val days = (0 until dTime.length()).mapNotNull { i ->
                if (dMax.isNull(i) || dMin.isNull(i)) return@mapNotNull null
                Day(
                    time = dTime.getLong(i),
                    max = dMax.getDouble(i),
                    min = dMin.getDouble(i),
                    code = dCode.optInt(i, 0),
                    precip = dPrecip.intOrNull(i),
                    sunrise = dSunrise.longOrNull(i),
                    sunset = dSunset.longOrNull(i),
                )
            }

            val air = runCatching {
                val a = JSONObject(airJson ?: return@runCatching null).getJSONObject("current")
                if (a.isNull("us_aqi")) null else AirQuality(Math.round(a.getDouble("us_aqi")).toInt())
            }.getOrNull()

            return WeatherData(place, tz, fetchedAt, current, hours, days, root.optDouble("latitude", 0.0), air)
        }

        private fun JSONArray?.longOrNull(i: Int): Long? =
            if (this == null || i >= length() || isNull(i)) null else optLong(i)

        private fun JSONArray?.intOrNull(i: Int): Int? =
            if (this == null || i >= length() || isNull(i)) null else optDouble(i).let { Math.round(it).toInt() }
    }
}

/** Códigos WMO usados pela Open-Meteo → ícone e descrição. */
object WeatherCode {

    /** Ícone para a condição [code] na fase do dia [phase]. */
    fun icon(code: Int, phase: DayPhase): Int {
        val night = phase == DayPhase.NIGHT
        val low = phase == DayPhase.DAWN || phase == DayPhase.DUSK
        // Chuva/neve fraca e pancadas: o astro aparece atrás da nuvem também de dia.
        val light = code in LIGHT
        fun variant(day: Int, lowRes: Int, nightRes: Int, base: Int) = when {
            night -> nightRes
            low -> if (light) lowRes else base
            else -> if (light) day else base
        }
        return when (code) {
            0, 1 -> when (phase) {
                DayPhase.DAWN -> R.drawable.ic_weather_clear_dawn
                DayPhase.DUSK -> R.drawable.ic_weather_clear_dusk
                DayPhase.NIGHT -> R.drawable.ic_weather_clear_night
                DayPhase.DAY -> R.drawable.ic_weather_clear_day
            }
            2 -> when {
                night -> R.drawable.ic_weather_partly_night
                low -> R.drawable.ic_weather_partly_low
                else -> R.drawable.ic_weather_partly_day
            }
            45, 48 -> variant(R.drawable.ic_weather_fog_day, R.drawable.ic_weather_fog_low, R.drawable.ic_weather_fog_night, R.drawable.ic_weather_fog)
            51, 53, 55, 56, 57 -> variant(R.drawable.ic_weather_drizzle_day, R.drawable.ic_weather_drizzle_low, R.drawable.ic_weather_drizzle_night, R.drawable.ic_weather_drizzle)
            61, 63, 65, 66, 67, 80, 81, 82 -> variant(R.drawable.ic_weather_rain_day, R.drawable.ic_weather_rain_low, R.drawable.ic_weather_rain_night, R.drawable.ic_weather_rain)
            71, 73, 75, 77, 85, 86 -> variant(R.drawable.ic_weather_snow_day, R.drawable.ic_weather_snow_low, R.drawable.ic_weather_snow_night, R.drawable.ic_weather_snow)
            95, 96, 99 -> variant(R.drawable.ic_weather_thunder_day, R.drawable.ic_weather_thunder_low, R.drawable.ic_weather_thunder_night, R.drawable.ic_weather_thunder)
            else -> when {
                night -> R.drawable.ic_weather_cloudy_night
                low -> R.drawable.ic_weather_cloudy_low
                else -> R.drawable.ic_weather_cloudy
            }
        }
    }

    /** Ícone diurno (previsão por dia). */
    fun dayIcon(code: Int): Int = icon(code, DayPhase.DAY)

    private val LIGHT = setOf(45, 51, 53, 56, 61, 80, 81, 71, 85, 95)

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

/** Faixas do índice de qualidade do ar (escala dos EUA) e do índice UV. */
object WeatherScale {
    fun aqiLabel(aqi: Int): Int = when {
        aqi <= 50 -> R.string.aqi_good
        aqi <= 100 -> R.string.aqi_moderate
        aqi <= 150 -> R.string.aqi_sensitive
        aqi <= 200 -> R.string.aqi_unhealthy
        aqi <= 300 -> R.string.aqi_very_unhealthy
        else -> R.string.aqi_hazardous
    }

    fun uvLabel(uv: Int): Int = when {
        uv < 3 -> R.string.uv_low
        uv < 6 -> R.string.uv_moderate
        uv < 8 -> R.string.uv_high
        uv < 11 -> R.string.uv_very_high
        else -> R.string.uv_extreme
    }
}
