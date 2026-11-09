package com.korsarts.tvlauncher

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
    private val hourCells = buildCells(root.findViewById(R.id.weather_hours))
    private val dayCells = buildCells(root.findViewById(R.id.weather_days))

    private val ptBr: Locale = Locale.forLanguageTag("pt-BR")

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
        details.text = context.getString(R.string.weather_details, c.humidity, c.windKmh.r())

        // Rótulos no fuso da cidade (pode não ser o do aparelho se a cidade foi escolhida à mão).
        val hourFmt = SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H'h'" else "h a", ptBr)
            .apply { timeZone = data.timeZone }
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
        private const val HALF_HOUR_MS = 30 * 60 * 1000L
    }
}
