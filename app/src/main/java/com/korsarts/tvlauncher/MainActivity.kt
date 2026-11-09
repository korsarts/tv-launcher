package com.korsarts.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import java.text.Normalizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), AppAdapter.Listener {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var content: PagerLayout
    private lateinit var homePage: View
    private lateinit var appsPage: View
    private lateinit var favoritesView: RecyclerView
    private lateinit var appsView: RecyclerView
    private lateinit var favoritesEmpty: TextView
    private lateinit var favoritesAdapter: AppAdapter
    private lateinit var appsAdapter: AppAdapter
    private lateinit var panel: SidePanel
    private lateinit var weather: WeatherRepository
    private lateinit var weatherWidget: WeatherWidget
    private lateinit var systemStatus: SystemStatus
    private lateinit var updater: Updater
    private lateinit var updateBadge: View
    /** Versão nova do launcher já encontrada e ainda não instalada, ou null. */
    private var update: Updater.Release? = null
    private var spanCount = 1

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Último resultado do PackageManager (todos os apps, antes de filtros). */
    private var allApps: List<AppEntry> = emptyList()
    private var loadGeneration = 0
    private var firstLoad = true
    private var reloadPending = false
    private var resumed = false

    /** Favorito sendo reordenado (modo mover), ou null. */
    private var movingPkg: String? = null

    /** Último favorito focado: ao subir da grade, o foco volta para ele. */
    private var lastFavoritePos = 0

    /** Onde o foco estava quando o painel abriu, para devolvê-lo ao fechar. */
    private var focusBeforePanel: View? = null

    /** Última previsão exibida (do cache ou da rede). */
    private var weatherData: WeatherData? = null
    /** A última busca falhou: quando a internet voltar, tenta de novo na hora. */
    private var weatherFailed = false
    private val weatherTick = Runnable { refreshWeather() }

    /** Fundo dinâmico (hora do dia + clima). */
    private lateinit var sky: SkyDrawable
    private val skyTick = object : Runnable {
        override fun run() {
            updateSky(SKY_TICK_ANIM_MS)
            refreshWidgetForTime()
            systemStatus.refresh()
            main.postDelayed(this, SKY_TICK_MS)
        }
    }
    /** Fase + hora exibidas no widget: muda → redesenha ícones e "próximas horas". */
    private var widgetTimeKey = -1L

    private lateinit var refreshButton: ImageButton
    private var refreshSpin: ObjectAnimator? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (resumed) loadApps() else reloadPending = true
        }
    }

    // InvalidSetHasFixedSize: falso positivo, a grade usa 0dp + layout_weight (altura fixa).
    @SuppressLint("InvalidSetHasFixedSize")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        sky = SkyDrawable(SkyState.BLANK, SkyStyle.from(prefs.skyStyle))
        if (prefs.skyEnabled) window.setBackgroundDrawable(sky)
        repo = AppRepository(this)

        content = findViewById(R.id.content)
        homePage = findViewById(R.id.home_page)
        appsPage = findViewById(R.id.apps_page)
        favoritesView = findViewById(R.id.favorites)
        appsView = findViewById(R.id.apps)
        favoritesEmpty = findViewById(R.id.favorites_empty)

        favoritesAdapter = AppAdapter(this)
        appsAdapter = AppAdapter(this)

        favoritesView.adapter = favoritesAdapter
        // Animador só nos favoritos, para a reordenação deslizar. Sem crossfade de "change".
        favoritesView.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            moveDuration = 180
            addDuration = 120
            removeDuration = 120
        }

        appsView.adapter = appsAdapter
        appsView.itemAnimator = null
        appsView.setHasFixedSize(true)
        applyCardSize()

        // Começam invisíveis e entram com fade quando os apps carregam.
        favoritesView.alpha = 0f
        appsView.alpha = 0f

        panel = SidePanel(
            findViewById(R.id.root),
            onOpened = { content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS },
            onClosed = ::restoreFocusAfterPanel,
        )

        weather = WeatherRepository(this, prefs)
        weatherWidget = WeatherWidget(findViewById(R.id.widget_area))
        weatherWidget.root.visibility = if (prefs.weatherEnabled) View.VISIBLE else View.GONE
        weatherWidget.setDatesEnabled(prefs.holidaysEnabled)
        weather.loadCached { cached ->
            if (cached != null && weatherData == null) {
                weatherData = cached
                showWeather(cached)
                updateSky(SKY_CHANGE_MS)
            } else if (cached == null && weatherData == null) {
                weatherWidget.showLoading()
            }
        }

        systemStatus = SystemStatus(findViewById(R.id.status_group)) {
            if (resumed && prefs.weatherEnabled && (weatherData == null || weatherFailed)) refreshWeather()
        }
        systemStatus.setVisible(prefs.showSystemStatus)

        updater = Updater(this)
        updateBadge = findViewById(R.id.update_badge)
        // Oferta guardada de uma consulta anterior; some sozinha depois de instalada.
        setUpdate(Updater.Release.fromJson(prefs.pendingUpdate)?.takeIf { updater.isNewer(it) })

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { openSystemSettings() }
        findViewById<ImageButton>(R.id.btn_options).setOnClickListener { showLauncherOptions() }
        findViewById<ImageButton>(R.id.btn_search).setOnClickListener { openAppSearch() }
        refreshButton = findViewById(R.id.btn_refresh)
        refreshButton.visibility = if (prefs.weatherEnabled) View.VISIBLE else View.GONE
        refreshButton.setOnClickListener { refreshWeather() }

        content.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus -> onFocusMoved(newFocus) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (reloadPending) {
            reloadPending = false
            loadApps()
        }
        resumeWeather()
        weatherWidget.setActive(true)
        systemStatus.start()
        autoCheckUpdate()
        updateSky(if (weatherData == null) SKY_CHANGE_MS else SKY_TICK_ANIM_MS)
        main.removeCallbacks(skyTick)
        main.postDelayed(skyTick, SKY_TICK_MS)
    }

    override fun onPause() {
        resumed = false
        weatherWidget.setActive(false)
        systemStatus.stop()
        main.removeCallbacks(weatherTick)
        main.removeCallbacks(skyTick)
        sky.stop()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        main.removeCallbacks(weatherTick)
        main.removeCallbacks(skyTick)
        sky.stop()
        weather.shutdown()
        updater.shutdown()
        executor.shutdownNow()
        super.onDestroy()
    }

    /** HOME pressionado com o launcher já aberto: volta ao início sem animação. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome(animate = false)
    }

    /** Launcher não deve fechar com BACK. */
    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        when {
            panel.isOpen -> panel.back()
            movingPkg != null -> exitMoveMode()
            else -> goHome(animate = true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // MENU com o painel aberto fecha o painel.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && panel.isOpen) {
            if (event.action == KeyEvent.ACTION_UP) panel.close()
            return true
        }
        // Botão de busca do controle/teclado (quando existe): abre a busca de apps.
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH && !panel.isOpen) {
            if (event.action == KeyEvent.ACTION_UP) openAppSearch()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun goHome(animate: Boolean) {
        panel.close(animate)
        exitMoveMode()
        content.showSecondPage(false, animate)
        lastFavoritePos = 0
        appsView.stopScroll()
        favoritesView.stopScroll()
        appsView.scrollToPosition(0)
        favoritesView.scrollToPosition(0)
        focusFirstItem()
    }

    // ---------------------------------------------------------------- dados

    private fun loadApps() {
        val generation = ++loadGeneration
        val includePhone = prefs.showPhoneApps
        executor.execute {
            val apps = runCatching { repo.load(includePhone) }.getOrDefault(emptyList())
            main.post {
                if (generation != loadGeneration || isDestroyed) return@post
                allApps = apps
                render()
            }
        }
    }

    private fun render() {
        val hidden = prefs.hidden
        val showHidden = prefs.showHidden
        val byPkg = allApps.associateBy { it.packageName }

        val favorites = prefs.favorites.mapNotNull { byPkg[it] }
        val apps = allApps.filter { showHidden || it.packageName !in hidden }

        favoritesAdapter.setHidden(hidden)
        appsAdapter.setHidden(if (showHidden) hidden else emptySet())

        // Se o app focado vai sumir (desinstalado/desabilitado), foca o vizinho em vez de
        // deixar o RecyclerView jogar o foco para os botões do topo.
        val refocus = focusTargetAfterRemoval(favorites, apps)
        favoritesAdapter.submitList(favorites) {
            movingPkg?.let { pkg -> revealPosition(favoritesView, favoritesAdapter.indexOf(pkg)) }
        }
        appsAdapter.submitList(apps) {
            when {
                firstLoad -> {
                    firstLoad = false
                    focusFirstItem()
                    fadeIn(favoritesView)
                    fadeIn(appsView)
                }
                refocus != null -> focusPosition(refocus.first, refocus.second)
            }
        }
        favoritesView.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
        favoritesEmpty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun fadeIn(view: View) {
        view.translationY = resources.displayMetrics.density * 16
        view.animate().alpha(1f).translationY(0f)
            .setDuration(Motion.PANEL_MS)
            .setInterpolator(Motion.STANDARD)
            .withLayer() // camada só durante a animação; liberada no fim
            .start()
    }

    /** (lista, posição) que deve receber foco se o item focado não existir na nova lista. */
    private fun focusTargetAfterRemoval(
        newFavorites: List<AppEntry>,
        newApps: List<AppEntry>,
    ): Pair<RecyclerView, Int>? {
        val focused = currentFocus ?: return null
        for ((rv, newList) in listOf(favoritesView to newFavorites, appsView to newApps)) {
            val holder = rv.findContainingViewHolder(focused) ?: continue
            val oldPos = holder.bindingAdapterPosition
            val adapter = rv.adapter as AppAdapter
            if (oldPos == RecyclerView.NO_POSITION || oldPos >= adapter.itemCount) return null
            val pkg = adapter.currentList[oldPos].packageName
            if (newList.any { it.packageName == pkg }) return null
            return when {
                newList.isNotEmpty() -> rv to oldPos.coerceAtMost(newList.size - 1)
                rv === favoritesView && newApps.isNotEmpty() -> appsView to 0
                else -> null
            }
        }
        return null
    }

    // ---------------------------------------------------------------- clima

    /** Intervalo da atualização automática, ou null se ela estiver desligada. */
    private fun weatherIntervalMs(): Long? = prefs.weatherIntervalMin.takeIf { it > 0 }?.let { it * 60_000L }

    /**
     * Ao voltar para o launcher: busca se não há dado ou se ele venceu; senão só reagenda e
     * redesenha. Com a atualização automática desligada, só busca quando não há nada salvo.
     */
    private fun resumeWeather() {
        if (!prefs.weatherEnabled) return
        val data = weatherData
        if (data == null) {
            refreshWeather()
            return
        }
        val interval = weatherIntervalMs()
        val age = System.currentTimeMillis() - data.fetchedAt
        if (interval != null && (age >= interval || age < 0)) {
            refreshWeather()
        } else {
            showWeather(data) // as "próximas horas" andam mesmo sem buscar de novo
            if (interval != null) scheduleWeather(interval - age)
        }
    }

    private fun refreshWeather() {
        main.removeCallbacks(weatherTick)
        if (!prefs.weatherEnabled) return
        if (weatherData == null) weatherWidget.showLoading()
        startRefreshSpin()
        weather.refresh { result ->
            stopRefreshSpin()
            when (result) {
                is WeatherRepository.Result.Success -> {
                    weatherFailed = false
                    weatherData = result.data
                    showWeather(result.data)
                    updateSky(SKY_CHANGE_MS)
                    weatherIntervalMs()?.let { scheduleWeather(it) }
                }
                is WeatherRepository.Result.Failure -> {
                    weatherFailed = true
                    val cached = weatherData
                    when {
                        cached != null -> showWeather(cached)
                        result.noLocation -> weatherWidget.showMessage(R.string.weather_no_location)
                        else -> weatherWidget.showMessage(R.string.weather_unavailable)
                    }
                    weatherIntervalMs()?.let { scheduleWeather(minOf(it, WEATHER_RETRY_MS)) }
                }
            }
        }
    }

    private fun showWeather(data: WeatherData) {
        val now = System.currentTimeMillis()
        weatherWidget.show(data, now)
        widgetTimeKey = widgetKey(data, now)
    }

    /** A cada minuto: se a fase do dia ou a hora mudou, atualiza ícones e próximas horas. */
    private fun refreshWidgetForTime() {
        val data = weatherData ?: return
        if (!prefs.weatherEnabled) return
        if (widgetKey(data, System.currentTimeMillis()) != widgetTimeKey) showWeather(data)
    }

    private fun widgetKey(data: WeatherData, now: Long): Long {
        val (rise, set) = data.sunTimes(now)
        return now / HOUR_MS * 4 + DayPhase.at(now, rise, set).ordinal
    }

    /** Liga: o céu nasce do fundo escuro. Desliga: escurece e volta ao fundo fixo do tema. */
    private fun setSkyEnabled(enabled: Boolean) {
        prefs.skyEnabled = enabled
        main.removeCallbacks(restoreStaticBackground)
        if (enabled) {
            sky.setState(SkyState.BLANK, 0)
            window.setBackgroundDrawable(sky)
            updateSky(SKY_CHANGE_MS)
        } else {
            sky.setState(SkyState.BLANK, SKY_OFF_MS)
            main.postDelayed(restoreStaticBackground, SKY_OFF_MS)
        }
    }

    /** Troca o estilo com crossfade; escolher um estilo também liga o fundo dinâmico. */
    private fun setSkyStyle(style: SkyStyle) {
        prefs.skyStyle = style.id
        if (!prefs.skyEnabled) {
            sky.setStyle(style, SkyState.BLANK, 0)
            setSkyEnabled(true)
        } else {
            sky.setStyle(style, skyTarget(style), SKY_STYLE_MS)
        }
        // Atualiza o texto de apoio do item no menu principal ao voltar.
        panel.parentItem(getString(R.string.opt_sky_style))?.hint = getString(style.label)
    }

    private val restoreStaticBackground = Runnable {
        sky.stop()
        if (!prefs.skyEnabled) window.setBackgroundDrawableResource(R.drawable.bg_window)
    }

    /** Recalcula o céu para agora (com o clima, se houver) e anima até ele. */
    private fun updateSky(durationMs: Long) {
        if (!prefs.skyEnabled) return
        sky.setState(skyTarget(sky.style), durationMs)
    }

    private fun skyTarget(style: SkyStyle): SkyState =
        SkyState.now(style, weatherData?.takeIf { prefs.weatherEnabled })

    private fun startRefreshSpin() {
        val running = refreshSpin
        if (running != null) {
            running.repeatCount = ValueAnimator.INFINITE
            if (running.isRunning) return
        }
        refreshSpin = ObjectAnimator.ofFloat(refreshButton, View.ROTATION, 0f, 360f).apply {
            duration = SPIN_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /** Termina a volta atual em vez de parar no meio (e garante pelo menos uma volta). */
    private fun stopRefreshSpin() {
        refreshSpin?.repeatCount = 0
    }

    /** Só agenda com o launcher em primeiro plano; o onResume retoma. */
    private fun scheduleWeather(delayMs: Long) {
        main.removeCallbacks(weatherTick)
        if (resumed && prefs.weatherEnabled) main.postDelayed(weatherTick, delayMs)
    }

    private fun setWeatherEnabled(enabled: Boolean) {
        prefs.weatherEnabled = enabled
        weatherWidget.root.visibility = if (enabled) View.VISIBLE else View.GONE
        refreshButton.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) resumeWeather() else main.removeCallbacks(weatherTick)
        updateSky(SKY_CHANGE_MS)
    }

    private fun setWeatherLocation(location: WeatherLocation?) {
        prefs.weatherManualLocation = location
        weather.clearCache()
        weatherData = null
        refreshWeather()
    }

    // ---------------------------------------------------------------- atualização

    private fun setUpdate(release: Updater.Release?) {
        update = release
        prefs.pendingUpdate = release?.toJson()
        updateBadge.visibility = if (release != null) View.VISIBLE else View.GONE
    }

    /** No máximo uma consulta por dia, e só se a verificação automática estiver ligada. */
    private fun autoCheckUpdate() {
        val now = System.currentTimeMillis()
        val age = now - prefs.lastUpdateCheck
        if (!prefs.autoUpdateCheck || (age in 0 until UPDATE_CHECK_MS)) return
        updater.check { result ->
            if (result is Updater.Check.Failed) return@check // tenta de novo na próxima vez
            prefs.lastUpdateCheck = System.currentTimeMillis()
            val found = (result as? Updater.Check.Available)?.release
            val isNews = found != null && found.version != update?.version
            setUpdate(found)
            if (isNews && found != null && resumed) {
                Toast.makeText(this, getString(R.string.update_found, found.version), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePage(release: Updater.Release): SidePanel.Page {
        lateinit var page: SidePanel.Page
        val items = listOf(
            SidePanel.Item(R.drawable.ic_sync, getString(R.string.update_install), keepOpen = true) {
                startUpdate(release, page)
            },
            backItem(),
        )
        page = SidePanel.Page(
            title = getString(R.string.update_title),
            subtitle = getString(R.string.update_subtitle, release.version, updater.installedVersion),
            icon = getDrawable(R.drawable.ic_launcher),
            body = release.notes.ifEmpty {
                getString(R.string.update_default_notes, Formatter.formatShortFileSize(this, release.size))
            },
            items = items,
        )
        return page
    }

    private fun startUpdate(release: Updater.Release, page: SidePanel.Page) {
        if (updater.installing) return
        if (!updater.canInstall()) {
            // Android 8+: o usuário precisa liberar o launcher como fonte de instalação.
            Toast.makeText(this, R.string.update_allow_source, Toast.LENGTH_LONG).show()
            val allow = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null))
            if (!safeStart(allow) && !safeStart(Intent(Settings.ACTION_SECURITY_SETTINGS))) openSystemSettings()
            return
        }
        panel.setBody(page, getString(R.string.update_downloading, 0))
        updater.install(
            release,
            onProgress = { percent -> panel.setBody(page, getString(R.string.update_downloading, percent)) },
            onDone = { ok ->
                panel.setBody(page, getString(if (ok) R.string.update_downloaded else R.string.update_download_failed))
            },
        )
    }

    // ---------------------------------------------------------------- foco

    /** A página em cena acompanha o foco: grade focada = página da grade; senão, início. */
    private fun onFocusMoved(newFocus: View?) {
        if (newFocus == null || panel.isOpen) return

        movingPkg?.let { pkg ->
            if (packageAt(favoritesView, newFocus) != pkg) exitMoveMode()
        }

        favoritesView.findContainingViewHolder(newFocus)?.bindingAdapterPosition
            ?.takeIf { it != RecyclerView.NO_POSITION }
            ?.let { lastFavoritePos = it }

        when {
            newFocus.isDescendantOf(appsPage) -> content.showSecondPage(true)
            newFocus.isDescendantOf(homePage) -> content.showSecondPage(false)
        }
    }

    private fun packageAt(rv: RecyclerView, view: View): String? {
        val pos = rv.findContainingViewHolder(view)?.bindingAdapterPosition ?: return null
        val adapter = rv.adapter as AppAdapter
        return if (pos in 0 until adapter.itemCount) adapter.currentList[pos].packageName else null
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private fun focusFirstItem() {
        val target = if (favoritesAdapter.itemCount > 0) favoritesView else appsView
        focusPosition(target, 0)
    }

    /** Foca o item na posição após o próximo layout (antes disso a view pode não existir). */
    private fun focusPosition(rv: RecyclerView, position: Int) {
        if (position < 0) return
        // Já está na tela e sem atualizações pendentes: foca direto, sem esperar layout.
        if (!rv.hasPendingAdapterUpdates()) {
            rv.layoutManager?.findViewByPosition(position)?.let { view ->
                if (view.requestFocus()) return
            }
        }
        rv.scrollToPosition(position)
        afterNextLayout(rv) {
            (rv.layoutManager?.findViewByPosition(position) ?: rv.getChildAt(0))?.requestFocus()
        }
    }

    /** Rola (com margem) até o item, sem mudar o foco. */
    private fun revealPosition(rv: RecyclerView, position: Int) {
        if (position < 0) return
        afterNextLayout(rv) {
            rv.layoutManager?.findViewByPosition(position)?.let { rv.revealView(it) }
                ?: rv.smoothScrollToPosition(position)
        }
    }

    private fun afterNextLayout(rv: RecyclerView, block: () -> Unit) {
        rv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                block()
            }
        })
        rv.requestLayout()
    }

    /**
     * Aplica o tamanho de card escolhido ([Prefs.gridColumns] cards por linha na grade, 16:9) às
     * duas listas. Os cards já criados têm o tamanho antigo, então são descartados e recriados.
     */
    private fun applyCardSize() {
        val res = resources
        val columns = prefs.gridColumns
        val available = res.displayMetrics.widthPixels - 2 * res.getDimensionPixelSize(R.dimen.row_padding_h)
        val width = available / columns - 2 * res.getDimensionPixelSize(R.dimen.card_margin)
        val height = width * 9 / 16
        spanCount = columns
        favoritesAdapter.setCardSize(width, height)
        appsAdapter.setCardSize(width, height)
        for (rv in arrayOf(favoritesView, appsView)) {
            val adapter = rv.adapter
            rv.adapter = null
            rv.recycledViewPool.clear()
            rv.layoutManager = if (rv === favoritesView) {
                RevealLinearLayoutManager(this, width / 2)
            } else {
                RevealGridLayoutManager(this, columns, height / 2)
            }
            rv.adapter = adapter
        }
        appsView.setItemViewCacheSize(columns * 2)
    }

    private fun setGridColumns(columns: Int) {
        if (columns == prefs.gridColumns) return
        prefs.gridColumns = columns
        exitMoveMode()
        applyCardSize()
        panel.parentItem(getString(R.string.opt_card_size))?.hint = getString(cardSizeLabel(columns))
    }

    private fun cardSizeLabel(columns: Int): Int = when (columns) {
        4 -> R.string.card_size_large
        6 -> R.string.card_size_small
        else -> R.string.card_size_medium
    }

    // ---------------------------------------------------------------- cards

    override fun onAppClick(app: AppEntry, card: View) = launch(app, card)

    // ---------------------------------------------------------------- busca

    private fun openAppSearch() {
        lateinit var page: SidePanel.Page
        val input = SidePanel.Input(
            getString(R.string.search_hint),
            showKeyboard = true,
            onChange = { query ->
                val items = searchItems(query)
                panel.setItems(page, items)
                panel.setBody(page, getString(if (query.isEmpty()) R.string.search_body else R.string.search_empty).takeIf { items.isEmpty() })
            },
            // Confirmar no teclado leva o foco ao primeiro resultado.
            onSubmit = { if (page.items.isNotEmpty()) panel.focusItem(0) },
        )
        page = SidePanel.Page(
            title = getString(R.string.search_title),
            body = getString(R.string.search_body),
            input = input,
            items = emptyList(),
        )
        openPanel(page)
    }

    /** Apps cujo nome contém [query], sem diferenciar maiúsculas nem acentos; começo do nome primeiro. */
    private fun searchItems(query: String): List<SidePanel.Item> {
        val q = searchKey(query)
        if (q.isEmpty()) return emptyList()
        val hidden = if (prefs.showHidden) emptySet() else prefs.hidden
        return allApps.asSequence()
            .filter { it.packageName !in hidden }
            .map { it to searchKey(it.label) }
            .filter { (_, key) -> q in key }
            .sortedBy { (_, key) ->
                when {
                    key.startsWith(q) -> 0
                    " $q" in key -> 1 // começo de outra palavra do nome
                    else -> 2
                }
            }
            .take(SEARCH_RESULTS)
            .map { (app, _) ->
                // Cópia do drawable: o original está em uso pelo card do app na grade.
                val icon = app.icon.constantState?.newDrawable(resources) ?: app.icon
                SidePanel.Item(0, app.label, iconDrawable = icon) { launch(app, null) }
            }
            .toList()
    }

    private fun searchKey(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase().trim()

    override fun onAppMenu(app: AppEntry) {
        if (movingPkg == null) showAppMenu(app)
    }

    override fun onAppKey(adapter: AppAdapter, position: Int, app: AppEntry, keyCode: Int, event: KeyEvent): Boolean {
        if (movingPkg != null) return onMoveKey(app, keyCode, event)
        if (event.action != KeyEvent.ACTION_DOWN || position == RecyclerView.NO_POSITION) return false
        return when {
            // ▼ nos favoritos: desce para a página da grade, começando pelo primeiro app.
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && adapter === favoritesAdapter -> {
                if (appsAdapter.itemCount == 0) return true
                focusPosition(appsView, 0)
                true
            }
            // ▲ na primeira linha da grade: volta para o favorito que estava focado.
            keyCode == KeyEvent.KEYCODE_DPAD_UP && adapter === appsAdapter && position < spanCount &&
                favoritesAdapter.itemCount > 0 -> {
                focusPosition(favoritesView, lastFavoritePos.coerceIn(0, favoritesAdapter.itemCount - 1))
                true
            }
            else -> false
        }
    }

    /** Modo mover: ◀ ▶ trocam de posição, OK/BACK confirmam, ▲ ▼ ficam presos no card. */
    private fun onMoveKey(app: AppEntry, keyCode: Int, event: KeyEvent): Boolean {
        val pkg = movingPkg ?: return false
        if (app.packageName != pkg) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveFavorite(pkg, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveFavorite(pkg, +1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (!down) exitMoveMode()
            else -> return false
        }
        return true
    }

    private fun enterMoveMode(pkg: String) {
        val index = favoritesAdapter.indexOf(pkg)
        if (index < 0) return
        movingPkg = pkg
        favoritesAdapter.setMoving(pkg)
        content.showSecondPage(false)
        focusPosition(favoritesView, index)
    }

    private fun exitMoveMode() {
        if (movingPkg == null) return
        movingPkg = null
        favoritesAdapter.setMoving(null)
    }

    private fun moveFavorite(pkg: String, delta: Int) {
        // Pula favoritos salvos que não estão instalados (invisíveis na fileira).
        val visible = favoritesAdapter.currentList.mapTo(HashSet()) { it.packageName }
        if (prefs.moveFavorite(pkg, delta, visible)) render()
    }

    // ---------------------------------------------------------------- ações

    /** [card] nulo (app aberto pela busca): abre com a animação padrão do sistema. */
    private fun launch(app: AppEntry, card: View?) {
        // Abre "crescendo" a partir do card: animação de janela do sistema, sem custo para nós.
        val options = card?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        if (!safeStart(app.launchIntent(), options)) {
            // Fallback: intent de launch padrão do pacote.
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback == null || !safeStart(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)) {
                Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun safeStart(intent: Intent, options: Bundle? = null): Boolean = try {
        startActivity(intent, options)
        true
    } catch (e: Exception) { // ActivityNotFoundException, SecurityException
        false
    }

    private fun openSystemSettings() {
        safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    private fun openPanel(page: SidePanel.Page) {
        if (!panel.isOpen) focusBeforePanel = currentFocus
        panel.open(page)
    }

    private fun restoreFocusAfterPanel() {
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val target = focusBeforePanel
        focusBeforePanel = null
        // A view do card continua ligada ao mesmo app enquanto o painel está aberto (IDs estáveis).
        if (target == null || !target.isAttachedToWindow || !target.isShown || !target.requestFocus()) {
            focusFirstItem()
        }
    }

    private fun showAppMenu(app: AppEntry) {
        val pkg = app.packageName
        val isFav = pkg in prefs.favorites
        val isHidden = pkg in prefs.hidden
        val items = ArrayList<SidePanel.Item>(6)

        items += if (isFav) {
            SidePanel.Item(R.drawable.ic_star, getString(R.string.action_remove_favorite)) {
                prefs.toggleFavorite(pkg); render()
            }
        } else {
            SidePanel.Item(R.drawable.ic_star_border, getString(R.string.action_add_favorite)) {
                prefs.toggleFavorite(pkg); render()
            }
        }
        if (isFav && favoritesAdapter.itemCount > 1) {
            items += SidePanel.Item(
                R.drawable.ic_swap, getString(R.string.action_move), getString(R.string.action_move_hint),
            ) { enterMoveMode(pkg) }
        }
        items += if (isHidden) {
            SidePanel.Item(R.drawable.ic_visibility, getString(R.string.action_unhide)) {
                prefs.toggleHidden(pkg); render()
            }
        } else {
            SidePanel.Item(R.drawable.ic_visibility_off, getString(R.string.action_hide)) {
                prefs.toggleHidden(pkg); render()
            }
        }
        items += SidePanel.Item(R.drawable.ic_info, getString(R.string.action_app_info)) {
            safeStart(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        if (!app.isSystem) {
            items += SidePanel.Item(R.drawable.ic_delete, getString(R.string.action_uninstall)) {
                @Suppress("DEPRECATION")
                safeStart(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", pkg, null)))
            }
        }

        openPanel(SidePanel.Page(title = app.label, subtitle = pkg, icon = app.icon, items = items))
    }

    private fun showLauncherOptions() {
        val items = ArrayList<SidePanel.Item>(10)
        update?.let { release ->
            items += SidePanel.Item(
                R.drawable.ic_sync, getString(R.string.update_item, release.version),
                getString(R.string.update_item_hint), keepOpen = true,
            ) {
                panel.push(updatePage(release))
            }
        }
        items += listOf(
            SidePanel.Item(R.drawable.ic_settings, getString(R.string.opt_system_settings)) {
                openSystemSettings()
            },
            SidePanel.Item(
                R.drawable.ic_phone, getString(R.string.opt_show_phone_apps),
                getString(R.string.opt_show_phone_apps_hint), checked = { prefs.showPhoneApps },
            ) {
                prefs.showPhoneApps = !prefs.showPhoneApps
                loadApps()
            },
            SidePanel.Item(
                R.drawable.ic_visibility, getString(R.string.opt_show_hidden), checked = { prefs.showHidden },
            ) {
                prefs.showHidden = !prefs.showHidden
                render()
            },
            SidePanel.Item(
                R.drawable.ic_sync, getString(R.string.opt_redirect),
                getString(R.string.opt_redirect_hint), checked = { prefs.redirectStockLauncher },
            ) {
                prefs.redirectStockLauncher = !prefs.redirectStockLauncher
            },
            SidePanel.Item(
                R.drawable.ic_home, getString(R.string.opt_accessibility), getString(R.string.opt_accessibility_hint),
            ) {
                if (!safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                    Toast.makeText(this, R.string.accessibility_missing, Toast.LENGTH_LONG).show()
                }
            },
            SidePanel.Item(
                R.drawable.ic_gradient, getString(R.string.opt_appearance), getString(R.string.opt_appearance_hint),
                keepOpen = true,
            ) {
                panel.push(appearancePage())
            },
            SidePanel.Item(
                R.drawable.ic_weather, getString(R.string.opt_weather), getString(R.string.opt_weather_hint),
                keepOpen = true,
            ) {
                panel.push(weatherPage())
            },
            SidePanel.Item(
                R.drawable.ic_storage, getString(R.string.opt_system), getString(R.string.opt_system_hint),
                keepOpen = true,
            ) {
                panel.push(systemPage())
            },
            SidePanel.Item(
                R.drawable.ic_info, getString(R.string.opt_about), getString(R.string.opt_about_hint),
                keepOpen = true,
            ) {
                panel.push(aboutPage())
            },
        )
        openPanel(SidePanel.Page(title = getString(R.string.launcher_options), items = items))
    }

    private fun systemPage(): SidePanel.Page {
        val s = systemStatus.snapshot()
        val net = when {
            s.net != SystemStatus.Net.WIFI -> SystemStatus.netLabel(s.net)
            s.wifiLevel == 0 -> R.string.system_wifi_weak
            s.wifiLevel == 1 -> R.string.system_wifi_medium
            else -> R.string.system_wifi_strong
        }
        fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)
        val items = listOf(
            SidePanel.Item(
                R.drawable.ic_visibility, getString(R.string.opt_status_show),
                getString(R.string.opt_status_show_hint), checked = { prefs.showSystemStatus },
            ) {
                prefs.showSystemStatus = !prefs.showSystemStatus
                systemStatus.setVisible(prefs.showSystemStatus)
            },
            SidePanel.Item(
                R.drawable.ic_storage, getString(R.string.opt_storage), getString(R.string.opt_storage_hint),
            ) {
                if (!safeStart(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))) openSystemSettings()
            },
            backItem(),
        )
        return SidePanel.Page(
            title = getString(R.string.opt_system),
            body = getString(
                R.string.system_body, getString(net),
                size(s.storageFree), size(s.storageTotal), size(s.ramFree), size(s.ramTotal),
            ),
            items = items,
        )
    }

    private fun appearancePage(): SidePanel.Page {
        val items = listOf(
            SidePanel.Item(
                R.drawable.ic_gradient, getString(R.string.opt_sky), getString(R.string.opt_sky_hint),
                checked = { prefs.skyEnabled },
            ) {
                setSkyEnabled(!prefs.skyEnabled)
            },
            SidePanel.Item(
                R.drawable.ic_tune, getString(R.string.opt_sky_style), getString(sky.style.label),
                keepOpen = true,
            ) {
                panel.push(skyStylePage())
            },
            SidePanel.Item(
                R.drawable.ic_grid, getString(R.string.opt_card_size), getString(cardSizeLabel(prefs.gridColumns)),
                keepOpen = true,
            ) {
                panel.push(cardSizePage())
            },
            SidePanel.Item(
                R.drawable.ic_schedule, getString(R.string.opt_screensaver), getString(R.string.opt_screensaver_hint),
                keepOpen = true,
            ) {
                panel.push(screensaverPage())
            },
            backItem(),
        )
        return SidePanel.Page(title = getString(R.string.opt_appearance), items = items)
    }

    private fun screensaverPage(): SidePanel.Page {
        val items = listOf(
            SidePanel.Item(
                R.drawable.ic_settings, getString(R.string.screensaver_settings),
                getString(R.string.screensaver_settings_hint),
            ) {
                if (!safeStart(Intent(Settings.ACTION_DREAM_SETTINGS))) {
                    Toast.makeText(this, R.string.screensaver_settings_missing, Toast.LENGTH_LONG).show()
                    openSystemSettings()
                }
            },
            SidePanel.Item(
                R.drawable.ic_visibility, getString(R.string.screensaver_test),
                getString(R.string.screensaver_test_hint),
            ) {
                // Atalho do próprio Android para iniciar agora o protetor selecionado.
                val start = Intent(Intent.ACTION_MAIN)
                    .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (!safeStart(start)) {
                    Toast.makeText(this, R.string.screensaver_test_missing, Toast.LENGTH_LONG).show()
                }
            },
            backItem(),
        )
        return SidePanel.Page(
            title = getString(R.string.opt_screensaver),
            body = getString(R.string.screensaver_body),
            items = items,
        )
    }

    private fun cardSizePage(): SidePanel.Page {
        val items = CARD_COLUMNS.map { columns ->
            SidePanel.Item(
                R.drawable.ic_grid, getString(cardSizeLabel(columns)),
                resources.getQuantityString(R.plurals.card_size_hint, columns, columns),
                checked = { prefs.gridColumns == columns }, radio = true,
            ) {
                setGridColumns(columns)
            }
        }
        return SidePanel.Page(title = getString(R.string.opt_card_size), items = items).apply {
            focusIndex = CARD_COLUMNS.indexOf(prefs.gridColumns).coerceAtLeast(0)
        }
    }

    private fun skyStylePage(): SidePanel.Page {
        val items = SkyStyle.values().map { style ->
            SidePanel.Item(
                R.drawable.ic_gradient, getString(style.label), getString(style.hint),
                checked = { sky.style == style }, radio = true,
            ) {
                setSkyStyle(style)
            }
        }
        return SidePanel.Page(title = getString(R.string.opt_sky_style), items = items).apply {
            focusIndex = SkyStyle.values().indexOf(sky.style)
        }
    }

    private fun backItem() = SidePanel.Item(R.drawable.ic_chevron_left, getString(R.string.back), keepOpen = true) {
        panel.back()
    }

    private fun weatherPage(): SidePanel.Page {
        val manual = prefs.weatherManualLocation
        val autoName = weatherData?.place ?: weather.autoPlaceName
        val cityHint = when {
            manual != null -> getString(R.string.opt_weather_city_manual, manual.name)
            autoName != null -> getString(R.string.opt_weather_city_auto, autoName)
            else -> getString(R.string.opt_weather_city_auto_unknown)
        }
        val items = ArrayList<SidePanel.Item>(7)
        items += SidePanel.Item(
            R.drawable.ic_weather, getString(R.string.opt_weather_show), checked = { prefs.weatherEnabled },
        ) {
            setWeatherEnabled(!prefs.weatherEnabled)
        }
        items += SidePanel.Item(
            R.drawable.ic_schedule, getString(R.string.opt_holidays_show), getString(R.string.opt_holidays_hint),
            checked = { prefs.holidaysEnabled },
        ) {
            prefs.holidaysEnabled = !prefs.holidaysEnabled
            weatherWidget.setDatesEnabled(prefs.holidaysEnabled)
        }
        items += SidePanel.Item(R.drawable.ic_search, getString(R.string.opt_weather_city), cityHint, keepOpen = true) {
            panel.push(citySearchPage())
        }
        if (manual != null) {
            items += SidePanel.Item(
                R.drawable.ic_location, getString(R.string.opt_weather_auto), getString(R.string.opt_weather_auto_hint),
            ) {
                setWeatherLocation(null)
            }
        }
        items += SidePanel.Item(
            R.drawable.ic_schedule, getString(R.string.opt_weather_interval),
            weatherIntervalLabel(prefs.weatherIntervalMin), keepOpen = true,
        ) {
            panel.push(weatherIntervalPage())
        }
        items += SidePanel.Item(R.drawable.ic_sync, getString(R.string.opt_weather_refresh)) {
            if (!prefs.weatherEnabled) setWeatherEnabled(true) else refreshWeather()
        }
        items += backItem()
        return SidePanel.Page(title = getString(R.string.opt_weather), items = items)
    }

    private fun weatherIntervalLabel(minutes: Int): String = when {
        minutes <= 0 -> getString(R.string.weather_interval_off)
        minutes < 60 -> resources.getQuantityString(R.plurals.weather_interval_minutes, minutes, minutes)
        else -> resources.getQuantityString(R.plurals.weather_interval_hours, minutes / 60, minutes / 60)
    }

    private fun weatherIntervalPage(): SidePanel.Page {
        val items = WEATHER_INTERVALS.map { minutes ->
            val label = weatherIntervalLabel(minutes)
            SidePanel.Item(
                R.drawable.ic_schedule,
                if (minutes == 30) getString(R.string.weather_interval_default, label) else label,
                if (minutes == 0) getString(R.string.weather_interval_off_hint) else null,
                checked = { prefs.weatherIntervalMin == minutes }, radio = true,
            ) {
                setWeatherInterval(minutes)
            }
        }
        return SidePanel.Page(title = getString(R.string.opt_weather_interval), items = items).apply {
            focusIndex = WEATHER_INTERVALS.indexOf(prefs.weatherIntervalMin).coerceAtLeast(0)
        }
    }

    /** Novo intervalo vale na hora: se o dado já passou do novo limite, busca agora. */
    private fun setWeatherInterval(minutes: Int) {
        prefs.weatherIntervalMin = minutes
        main.removeCallbacks(weatherTick)
        if (resumed) resumeWeather()
        panel.parentItem(getString(R.string.opt_weather_interval))?.hint = weatherIntervalLabel(minutes)
    }

    private fun citySearchPage(): SidePanel.Page {
        lateinit var page: SidePanel.Page
        val input = SidePanel.Input(getString(R.string.weather_search_hint)) { query ->
            panel.setBody(page, getString(R.string.weather_searching))
            weather.searchCity(query) { results ->
                if (panel.currentPage !== page) return@searchCity // usuário saiu da página
                when {
                    results == null -> panel.setBody(page, getString(R.string.weather_search_failed))
                    results.isEmpty() -> panel.setBody(page, getString(R.string.weather_search_empty))
                    else -> {
                        panel.setBody(page, getString(R.string.weather_search_body))
                        panel.push(cityResultsPage(query, results))
                    }
                }
            }
        }
        page = SidePanel.Page(
            title = getString(R.string.weather_search_title),
            body = getString(R.string.weather_search_body),
            input = input,
            items = listOf(backItem()),
        )
        return page
    }

    private fun cityResultsPage(query: String, results: List<WeatherLocation>): SidePanel.Page {
        val items = results.map { loc ->
            SidePanel.Item(R.drawable.ic_location, loc.name, loc.region) { setWeatherLocation(loc) }
        } + backItem()
        return SidePanel.Page(title = getString(R.string.weather_results_title), subtitle = query, items = items)
    }

    private fun aboutPage(): SidePanel.Page {
        lateinit var page: SidePanel.Page
        lateinit var checkItem: SidePanel.Item
        checkItem = SidePanel.Item(R.drawable.ic_sync, getString(R.string.update_check), keepOpen = true) {
            checkItem.hint = getString(R.string.update_checking)
            panel.refreshRows()
            updater.check { result ->
                if (result !is Updater.Check.Failed) {
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                    setUpdate((result as? Updater.Check.Available)?.release)
                }
                checkItem.hint = when (result) {
                    is Updater.Check.Available -> getString(R.string.update_item, result.release.version)
                    is Updater.Check.UpToDate -> getString(R.string.update_current)
                    is Updater.Check.Failed -> getString(R.string.update_check_failed)
                }
                if (panel.currentPage !== page) return@check // usuário saiu da página
                panel.refreshRows()
                if (result is Updater.Check.Available) panel.push(updatePage(result.release))
            }
        }
        update?.let { checkItem.hint = getString(R.string.update_item, it.version) }

        fun textPage(title: Int, text: Int) = SidePanel.Page(
            title = getString(title), body = getString(text), items = listOf(backItem()),
        )
        val items = listOf(
            checkItem,
            SidePanel.Item(
                R.drawable.ic_schedule, getString(R.string.update_auto), getString(R.string.update_auto_hint),
                checked = { prefs.autoUpdateCheck },
            ) {
                prefs.autoUpdateCheck = !prefs.autoUpdateCheck
            },
            SidePanel.Item(
                R.drawable.ic_star_border, getString(R.string.about_changelog),
                getString(R.string.about_changelog_hint), keepOpen = true,
            ) {
                panel.push(textPage(R.string.about_changelog, R.string.changelog_text))
            },
            SidePanel.Item(
                R.drawable.ic_visibility, getString(R.string.about_services),
                getString(R.string.about_services_hint), keepOpen = true,
            ) {
                panel.push(textPage(R.string.about_services, R.string.about_services_text))
            },
            SidePanel.Item(
                R.drawable.ic_home, getString(R.string.about_default),
                getString(R.string.about_default_hint), keepOpen = true,
            ) {
                panel.push(textPage(R.string.about_default, R.string.about_text))
            },
            backItem(),
        )
        page = SidePanel.Page(
            title = getString(R.string.app_name),
            subtitle = getString(R.string.opt_version, updater.installedVersion),
            icon = getDrawable(R.drawable.ic_launcher),
            items = items,
        )
        return page
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val SKY_TICK_MS = 60 * 1000L
        const val SKY_TICK_ANIM_MS = 1500L
        /** Mudança de clima/entrada: transição lenta, quase imperceptível como corte. */
        const val SKY_CHANGE_MS = 2500L
        const val SPIN_MS = 900L
        const val SKY_OFF_MS = 700L
        const val SKY_STYLE_MS = 900L
        /** Opções de atualização automática, em minutos (0 = desligada). */
        val WEATHER_INTERVALS = intArrayOf(15, 30, 60, 120, 180, 360, 0)
        const val WEATHER_RETRY_MS = 5 * 60 * 1000L
        /** Cards por linha: grandes, médios (padrão), pequenos. */
        val CARD_COLUMNS = intArrayOf(4, 5, 6)
        const val SEARCH_RESULTS = 8
        const val UPDATE_CHECK_MS = 24 * HOUR_MS
        /** Marcas de acento soltas pela decomposição NFD. */
        val DIACRITICS = Regex("\\p{Mn}+")
    }
}
