package com.korsarts.tvlauncher

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlin.random.Random

/**
 * Protetor de tela do sistema (Configurações → Protetor de tela): o céu dinâmico do launcher em
 * tela cheia, com relógio, data e o clima atual. Usa o estilo de fundo e a cidade já escolhidos.
 */
class ScreensaverService : DreamService() {

    private lateinit var prefs: Prefs
    private lateinit var weather: WeatherRepository
    private lateinit var sky: SkyDrawable
    private lateinit var root: View
    private lateinit var block: View
    private lateinit var weatherRow: View
    private lateinit var icon: ImageView
    private lateinit var condition: TextView
    private lateinit var place: TextView

    private val main = Handler(Looper.getMainLooper())
    private var data: WeatherData? = null
    private var lastAttempt = 0L
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            ticks++
            updateSky(SKY_ANIM_MS)
            data?.let { showWeather(it) } // o ícone muda com a fase do dia
            refreshIfStale()
            if (ticks % MOVE_EVERY_TICKS == 0) moveBlock()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.dream)

        prefs = Prefs(this)
        weather = WeatherRepository(this, prefs)
        sky = SkyDrawable(SkyState.BLANK, SkyStyle.from(prefs.skyStyle))
        root = findViewById(R.id.dream_root)
        block = findViewById(R.id.dream_block)
        weatherRow = findViewById(R.id.dream_weather)
        icon = findViewById(R.id.dream_icon)
        condition = findViewById(R.id.dream_condition)
        place = findViewById(R.id.dream_place)
        root.background = sky
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        updateSky(SKY_ENTER_MS)
        if (prefs.weatherEnabled) {
            weather.loadCached { cached ->
                if (cached != null && data == null) {
                    data = cached
                    showWeather(cached)
                    updateSky(SKY_ENTER_MS)
                }
                refreshIfStale()
            }
        }
        // Depois do primeiro layout (o bloco já tem tamanho): escolhe um lugar e aparece.
        root.post { placeBlock(); fadeBlock(1f) }
        main.postDelayed(tick, TICK_MS)
    }

    override fun onDreamingStopped() {
        main.removeCallbacks(tick)
        block.animate().cancel()
        sky.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(tick)
        weather.shutdown()
        super.onDetachedFromWindow()
    }

    private fun updateSky(durationMs: Long) {
        sky.setState(SkyState.now(sky.style, data), durationMs)
    }

    private fun showWeather(d: WeatherData) {
        val now = System.currentTimeMillis()
        val (rise, set) = d.sunTimes(now)
        icon.setImageResource(WeatherCode.icon(d.current.code, DayPhase.at(now, rise, set)))
        condition.text = getString(
            R.string.weather_join,
            getString(R.string.weather_temp, Math.round(d.current.temp).toInt()),
            getString(WeatherCode.label(d.current.code)),
        )
        place.text = d.place
        weatherRow.visibility = View.VISIBLE
    }

    /**
     * Busca a previsão se não há nenhuma ou se ela passou do intervalo configurado no launcher
     * (com a atualização automática desligada, só busca quando não há nada salvo).
     */
    private fun refreshIfStale() {
        if (!prefs.weatherEnabled) return
        val now = System.currentTimeMillis()
        val interval = prefs.weatherIntervalMin.takeIf { it > 0 }?.let { it * 60_000L }
        val d = data
        val stale = d == null || (interval != null && now - d.fetchedAt >= interval)
        if (!stale || now - lastAttempt < RETRY_MS) return
        lastAttempt = now
        weather.refresh { result ->
            if (result is WeatherRepository.Result.Success) {
                data = result.data
                showWeather(result.data)
                updateSky(SKY_ENTER_MS)
            }
        }
    }

    /** Some, troca de lugar e reaparece: nada fica parado tempo bastante para marcar a tela. */
    private fun moveBlock() {
        block.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            placeBlock()
            fadeBlock(1f)
        }.start()
    }

    private fun fadeBlock(alpha: Float) {
        block.animate().alpha(alpha).setDuration(FADE_MS).start()
    }

    private fun placeBlock() {
        val margin = (MARGIN_DP * resources.displayMetrics.density).toInt()
        val roomX = root.width - block.width - 2 * margin
        val roomY = root.height - block.height - 2 * margin
        block.translationX = margin + if (roomX > 0) Random.nextInt(roomX + 1).toFloat() else 0f
        block.translationY = margin + if (roomY > 0) Random.nextInt(roomY + 1).toFloat() else 0f
    }

    private companion object {
        const val TICK_MS = 60_000L
        /** Troca o bloco de lugar a cada 2 minutos. */
        const val MOVE_EVERY_TICKS = 2
        const val FADE_MS = 1200L
        const val SKY_ENTER_MS = 2500L
        const val SKY_ANIM_MS = 1500L
        const val RETRY_MS = 5 * 60_000L
        const val MARGIN_DP = 56
    }
}
