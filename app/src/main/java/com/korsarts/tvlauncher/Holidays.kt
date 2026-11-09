package com.korsarts.tvlauncher

import java.util.Calendar
import java.util.TimeZone

/**
 * Feriados nacionais, pontos facultativos e datas comemorativas do Brasil, calculados no aparelho
 * (a Páscoa e as datas que dependem dela saem do algoritmo de Meeus/Jones/Butcher).
 */
object Holidays {

    enum class Kind { HOLIDAY, OPTIONAL, OBSERVANCE }

    /** [month] de 1 a 12. [name] é um @StringRes. */
    class Holiday(val year: Int, val month: Int, val day: Int, val name: Int, val kind: Kind) {
        /** Dia contado desde a época, usado para ordenar e para a distância em dias. */
        val epochDay: Long = epochDay(year, month, day)

        /** Dia da semana no padrão do [Calendar] (1 = domingo … 7 = sábado). */
        val weekday: Int get() = ((epochDay + 4).mod(7L) + 1).toInt()

        /** Feriado numa terça ou quinta: dá para emendar com o fim de semana. */
        val isBridge: Boolean
            get() = kind == Kind.HOLIDAY && (weekday == Calendar.TUESDAY || weekday == Calendar.THURSDAY)
    }

    /** As próximas [count] datas a partir de hoje (inclusive) no fuso [tz]. */
    fun upcoming(nowMs: Long, tz: TimeZone, count: Int): List<Holiday> {
        val today = today(nowMs, tz)
        val year = Calendar.getInstance(tz).apply { timeInMillis = nowMs }.get(Calendar.YEAR)
        return (forYear(year) + forYear(year + 1))
            .filter { it.epochDay >= today }
            .sortedBy { it.epochDay }
            .take(count)
    }

    /** Dias entre hoje e [h] (0 = hoje). */
    fun daysUntil(h: Holiday, nowMs: Long, tz: TimeZone): Int = (h.epochDay - today(nowMs, tz)).toInt()

    fun forYear(year: Int): List<Holiday> {
        val list = ArrayList<Holiday>(24)
        fun add(month: Int, day: Int, name: Int, kind: Kind) { list += Holiday(year, month, day, name, kind) }

        add(1, 1, R.string.holiday_new_year, Kind.HOLIDAY)
        add(4, 21, R.string.holiday_tiradentes, Kind.HOLIDAY)
        add(5, 1, R.string.holiday_labour, Kind.HOLIDAY)
        add(6, 12, R.string.holiday_valentines, Kind.OBSERVANCE)
        add(9, 7, R.string.holiday_independence, Kind.HOLIDAY)
        add(10, 12, R.string.holiday_aparecida, Kind.HOLIDAY)
        add(11, 2, R.string.holiday_all_souls, Kind.HOLIDAY)
        add(11, 15, R.string.holiday_republic, Kind.HOLIDAY)
        add(11, 20, R.string.holiday_black_consciousness, Kind.HOLIDAY)
        add(12, 24, R.string.holiday_christmas_eve, Kind.OPTIONAL)
        add(12, 25, R.string.holiday_christmas, Kind.HOLIDAY)
        add(12, 31, R.string.holiday_new_years_eve, Kind.OPTIONAL)

        add(5, secondSunday(year, 5), R.string.holiday_mothers, Kind.OBSERVANCE)
        add(8, secondSunday(year, 8), R.string.holiday_fathers, Kind.OBSERVANCE)

        val easter = easter(year)
        fun fromEaster(offset: Int, name: Int, kind: Kind) {
            val (y, m, d) = civil(easter + offset)
            list += Holiday(y, m, d, name, kind)
        }
        fromEaster(-48, R.string.holiday_carnival, Kind.OPTIONAL)
        fromEaster(-47, R.string.holiday_carnival, Kind.OPTIONAL)
        fromEaster(-46, R.string.holiday_ash_wednesday, Kind.OPTIONAL)
        fromEaster(-2, R.string.holiday_good_friday, Kind.HOLIDAY)
        fromEaster(0, R.string.holiday_easter, Kind.OBSERVANCE)
        fromEaster(60, R.string.holiday_corpus_christi, Kind.OPTIONAL)
        return list
    }

    /** Domingo de Páscoa (calendário gregoriano), como dia desde a época. */
    fun easter(year: Int): Long {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return epochDay(year, month, day)
    }

    private fun secondSunday(year: Int, month: Int): Int {
        val first = epochDay(year, month, 1)
        val weekday = (first + 4).mod(7L).toInt() // 0 = domingo
        return 1 + (7 - weekday) % 7 + 7
    }

    private fun today(nowMs: Long, tz: TimeZone): Long =
        (nowMs + tz.getOffset(nowMs)).floorDiv(DAY_MS)

    /** Dias desde 1970-01-01 (algoritmo de Howard Hinnant, sem depender do fuso). */
    private fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civil(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return Triple(year, month, day)
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
