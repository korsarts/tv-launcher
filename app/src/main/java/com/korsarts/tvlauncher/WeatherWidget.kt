package com.korsarts.tvlauncher

import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Views do widget de clima. Só exibe; quem busca os dados é o [WeatherRepository]. */
class WeatherWidget(container: ViewGroup) {

    private val context = container.context
    val root: View = LayoutInflater.from(context).inflate(R.layout.widget_weather, container, false)
        .also { container.addView(it) }

    private val content: View = root.findViewById(R.id.weather_content)
    private val message: TextView = root.findViewById(R.id.weather_message)
    private val place: TextView = root.findViewById(R.id.weather_place)
    private val updated: TextView = root.findViewById(R.id.weather_updated)
    private val icon: ImageView = root.findViewById(R.id.weather_icon)
    private val temp: TextView = root.findViewById(R.id.weather_temp)
    private val condition: TextView = root.findViewById(R.id.weather_condition)
    private val feels: TextView = root.findViewById(R.id.weather_feels)
    private val details: TextView = root.findViewById(R.id.weather_details)
    private val alert: TextView = root.findViewById(R.id.weather_alert)
    private val sun: TextView = root.findViewById(R.id.weather_sun)
    private val moon: TextView = root.findViewById(R.id.weather_moon)
    // resources.getColor(int): a versão sem tema é a única disponível na API 21.
    @Suppress("DEPRECATION")
    private val moonDisc = MoonDrawable(
        (14 * context.resources.displayMetrics.density).toInt(),
        context.resources.getColor(R.color.weather_moon), context.resources.getColor(R.color.weather_moon_dark),
    ).also {
        it.setBounds(0, 0, it.intrinsicWidth, it.intrinsicHeight)
        moon.setCompoundDrawablesRelative(it, null, null, null)
    }
    private val hourCells = buildCells(root.findViewById(R.id.weather_hours))
    private val dayCells = buildCells(root.findViewById(R.id.weather_days))

    private val ptBr: Locale = Locale.forLanguageTag("pt-BR")

    // Linha que reveza umidade, vento, qualidade do ar e UV com um fade curto.
    private val handler = Handler(Looper.getMainLooper())
    private var facts: List<String> = emptyList()
    private var factIndex = 0
    private var fading = false
    private var active = false
    private val shift = 6 * context.resources.displayMetrics.density
    private val rotate = object : Runnable {
        override fun run() {
            // Só anima com o widget à vista; fora disso, só espera a próxima vez.
            if (facts.size > 1 && details.isShown) nextFact()
            handler.postDelayed(this, FACT_MS)
        }
    }

    private class Cell(view: View) {
        val root = view
        val label: TextView = view.findViewById(R.id.forecast_label)
        val icon: ImageView = view.findViewById(R.id.forecast_icon)
        val temp: TextView = view.findViewById(R.id.forecast_temp)
        val tempMin: TextView = view.findViewById(R.id.forecast_temp_min)
        val precip: TextView = view.findViewById(R.id.forecast_precip)
    }

    private fun buildCells(row: LinearLayout): List<Cell> {
        val inflater = LayoutInflater.from(context)
        return List(COLUMNS) {
            val v = inflater.inflate(R.layout.item_forecast, row, false)
            row.addView(v)
            Cell(v)
        }
    }

    fun showLoading() = showMessage(R.string.weather_loading)

    fun showMessage(textRes: Int) {
        content.animate().cancel()
        content.visibility = View.INVISIBLE
        message.setText(textRes)
        message.visibility = View.VISIBLE
    }

    /** Exibe [data] relativo a [nowMs]: horas/dias que já passaram são pulados. */
    fun show(data: WeatherData, nowMs: Long = System.currentTimeMillis()) {
        val hours = data.upcomingHours(nowMs, COLUMNS)
        val days = data.upcomingDays(nowMs, COLUMNS)
        if (hours.isEmpty() && days.isEmpty()) {
            // Cache velho demais (mais de uma semana): não há nada útil para mostrar.
            showMessage(R.string.weather_unavailable)
            return
        }

        val c = data.current
        place.text = data.place
        updated.text = context.getString(R.string.weather_updated, DateFormat.getTimeFormat(context).format(Date(data.fetchedAt)))
        val (sunrise, sunset) = data.sunTimes(nowMs)
        icon.setImageResource(WeatherCode.icon(c.code, DayPhase.at(nowMs, sunrise, sunset)))
        temp.text = context.getString(R.string.weather_temp, c.temp.r())
        condition.setText(WeatherCode.label(c.code))
        val today = data.today(nowMs)
        feels.text = if (today != null) {
            context.getString(R.string.weather_feels, c.feelsLike.r(), today.min.r(), today.max.r())
        } else {
            context.getString(R.string.weather_feels_only, c.feelsLike.r())
        }
        setFacts(buildFacts(data))

        // Rótulos no fuso da cidade (pode não ser o do aparelho se a cidade foi escolhida à mão).
        val hourFmt = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H'h'" else "h a", ptBr)
            .apply { timeZone = data.timeZone }

        bindAlert(data.alert(nowMs), nowMs, hourFmt)
        val clockFmt = DateFormat.getTimeFormat(context).apply { timeZone = data.timeZone }
        sun.text = context.getString(
            R.string.weather_sun_times, clockFmt.format(Date(sunrise)), clockFmt.format(Date(sunset)),
        )
        val cycle = Moon.cycle(nowMs)
        moonDisc.set(cycle, southern = data.latitude < 0)
        moon.setText(Moon.label(cycle))
        val dayFmt = SimpleDateFormat("EEE", ptBr).apply { timeZone = data.timeZone }

        hourCells.forEachIndexed { i, cell ->
            val h = hours.getOrNull(i)
            cell.root.visibility = if (h == null) View.INVISIBLE else View.VISIBLE
            if (h == null) return@forEachIndexed
            cell.label.text = hourFmt.format(Date(h.time * 1000))
            val mid = h.time * 1000 + HALF_HOUR_MS
            val (rise, set) = data.sunTimes(mid)
            cell.icon.setImageResource(WeatherCode.icon(h.code, DayPhase.at(mid, rise, set)))
            cell.temp.text = context.getString(R.string.weather_temp, h.temp.r())
            cell.tempMin.visibility = View.GONE
            bindPrecip(cell, h.precip)
        }
        dayCells.forEachIndexed { i, cell ->
            val d = days.getOrNull(i)
            cell.root.visibility = if (d == null) View.INVISIBLE else View.VISIBLE
            if (d == null) return@forEachIndexed
            cell.label.text = dayLabel(dayFmt.format(Date(d.time * 1000)))
            cell.icon.setImageResource(WeatherCode.dayIcon(d.code))
            cell.temp.text = context.getString(R.string.weather_temp, d.max.r())
            cell.tempMin.text = context.getString(R.string.weather_temp, d.min.r())
            cell.tempMin.visibility = View.VISIBLE
            bindPrecip(cell, d.precip)
        }

        message.visibility = View.GONE
        if (content.visibility != View.VISIBLE) fadeIn()
    }

    private fun bindAlert(a: WeatherData.Alert?, nowMs: Long, hourFmt: SimpleDateFormat) {
        if (a == null) {
            alert.visibility = View.GONE
            return
        }
        val now = a.time * 1000 <= nowMs // a hora do aviso já começou
        val at = hourFmt.format(Date(a.time * 1000))
        val text = when (a.kind) {
            WeatherData.Alert.Kind.STORM ->
                if (now) context.getString(R.string.alert_storm_now) else context.getString(R.string.alert_storm, at)
            WeatherData.Alert.Kind.HEAVY_RAIN ->
                if (now) context.getString(R.string.alert_heavy_rain_now) else context.getString(R.string.alert_heavy_rain, at)
            WeatherData.Alert.Kind.RAIN -> context.getString(R.string.alert_rain, at)
        }
        alert.text = if (a.precip != null && a.precip > 0) context.getString(R.string.alert_chance, text, a.precip) else text
        alert.visibility = View.VISIBLE
    }

    /** Umidade e vento sempre; ar e UV quando vieram na previsão. */
    private fun buildFacts(data: WeatherData): List<String> {
        val c = data.current
        val list = ArrayList<String>(4)
        list += context.getString(R.string.weather_humidity, c.humidity)
        list += context.getString(R.string.weather_wind, c.windKmh.r())
        data.air?.usAqi?.let {
            list += context.getString(R.string.weather_air, context.getString(WeatherScale.aqiLabel(it)), it)
        }
        c.uv?.r()?.let {
            list += context.getString(R.string.weather_uv, it, context.getString(WeatherScale.uvLabel(it)))
        }
        return list
    }

    /** Troca os textos sem reiniciar o revezamento: a posição atual continua na tela. */
    private fun setFacts(list: List<String>) {
        facts = list
        if (factIndex >= list.size) factIndex = 0
        if (!fading) details.text = list.getOrNull(factIndex)
    }

    /** Liga o revezamento com o launcher na tela; desliga (parado no texto atual) fora dela. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        handler.removeCallbacks(rotate)
        if (value) {
            handler.postDelayed(rotate, FACT_MS)
        } else {
            details.animate().cancel()
            fading = false
            details.alpha = 1f
            details.translationY = 0f
            details.text = facts.getOrNull(factIndex)
        }
    }

    /** Sobe e some; troca o texto; entra de baixo. */
    private fun nextFact() {
        fading = true
        details.animate().alpha(0f).translationY(-shift)
            .setDuration(FADE_OUT_MS).setInterpolator(Motion.STANDARD)
            .withEndAction {
                if (facts.isEmpty()) return@withEndAction
                factIndex = (factIndex + 1) % facts.size
                details.text = facts[factIndex]
                details.translationY = shift
                details.animate().alpha(1f).translationY(0f)
                    .setDuration(FADE_IN_MS).setInterpolator(Motion.STANDARD)
                    .withEndAction { fading = false }
                    .start()
            }
            .start()
    }

    private fun bindPrecip(cell: Cell, precip: Int?) {
        if (precip != null && precip > 0) {
            cell.precip.text = context.getString(R.string.weather_precip, precip)
            cell.precip.visibility = View.VISIBLE
        } else {
            cell.precip.visibility = View.INVISIBLE
        }
    }

    /** "seg." → "Seg" (o ponto aparece em algumas versões do Android). */
    private fun dayLabel(raw: String): String =
        raw.trimEnd('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(ptBr) else it.toString() }

    private fun fadeIn() {
        content.visibility = View.VISIBLE
        content.alpha = 0f
        content.translationY = context.resources.displayMetrics.density * 12
        content.animate().alpha(1f).translationY(0f)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.STANDARD)
            .withLayer()
            .start()
    }

    private fun Double.r(): Int = Math.round(this).toInt()

    companion object {
        const val COLUMNS = 5
        /** Tempo de cada informação na tela, contando a troca. */
        private const val FACT_MS = 4500L
        private const val FADE_OUT_MS = 280L
        private const val FADE_IN_MS = 360L
        private const val HALF_HOUR_MS = 30 * 60 * 1000L
    }
}
