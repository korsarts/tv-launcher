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
import java.util.TimeZone
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
    private val weatherTick = Runnable { refreshWeather() }

    /** Fundo dinâmico (hora do dia + clima). */
    private lateinit var sky: SkyDrawable
    private val skyTick = object : Runnable {
        override fun run() {
            updateSky(SKY_TICK_ANIM_MS)
            refreshWidgetForTime()
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
        sky = SkyDrawable(SkyState.BLANK)
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

        val res = resources
        val peekH = res.getDimensionPixelSize(R.dimen.card_width) / 2
        val peekV = res.getDimensionPixelSize(R.dimen.card_height) / 2
        spanCount = computeSpanCount()

        favoritesView.layoutManager = RevealLinearLayoutManager(this, peekH)
        favoritesView.adapter = favoritesAdapter
        // Animador só nos favoritos, para a reordenação deslizar. Sem crossfade de "change".
        favoritesView.itemAnimator = DefaultItemAnimator().apply {
            supportsChangeAnimations = false
            moveDuration = 180
            addDuration = 120
            removeDuration = 120
        }

        appsView.layoutManager = RevealGridLayoutManager(this, spanCount, peekV)
        appsView.adapter = appsAdapter
        appsView.itemAnimator = null
        appsView.setHasFixedSize(true)
        appsView.setItemViewCacheSize(spanCount * 2)

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
        weather.loadCached { cached ->
            if (cached != null && weatherData == null) {
                weatherData = cached
                showWeather(cached)
                updateSky(SKY_CHANGE_MS)
            } else if (cached == null && weatherData == null) {
                weatherWidget.showLoading()
            }
        }

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { openSystemSettings() }
        findViewById<ImageButton>(R.id.btn_options).setOnClickListener { showLauncherOptions() }
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
        updateSky(if (weatherData == null) SKY_CHANGE_MS else SKY_TICK_ANIM_MS)
        main.removeCallbacks(skyTick)
        main.postDelayed(skyTick, SKY_TICK_MS)
    }

    override fun onPause() {
        resumed = false
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

    /** Ao voltar para o launcher: busca se o dado venceu; senão só reagenda e redesenha. */
    private fun resumeWeather() {
        if (!prefs.weatherEnabled) return
        val data = weatherData
        val age = if (data == null) Long.MAX_VALUE else System.currentTimeMillis() - data.fetchedAt
        if (age >= WEATHER_REFRESH_MS || age < 0) {
            refreshWeather()
        } else {
            showWeather(data!!) // as "próximas horas" andam mesmo sem buscar de novo
            scheduleWeather(WEATHER_REFRESH_MS - age)
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
                    weatherData = result.data
                    showWeather(result.data)
                    updateSky(SKY_CHANGE_MS)
                    scheduleWeather(WEATHER_REFRESH_MS)
                }
                is WeatherRepository.Result.Failure -> {
                    val cached = weatherData
                    when {
                        cached != null -> showWeather(cached)
                        result.noLocation -> weatherWidget.showMessage(R.string.weather_no_location)
                        else -> weatherWidget.showMessage(R.string.weather_unavailable)
                    }
                    scheduleWeather(WEATHER_RETRY_MS)
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

    private val restoreStaticBackground = Runnable {
        sky.stop()
        if (!prefs.skyEnabled) window.setBackgroundDrawableResource(R.drawable.bg_window)
    }

    /** Recalcula o céu para agora (com o clima, se houver) e anima até ele. */
    private fun updateSky(durationMs: Long) {
        if (!prefs.skyEnabled) return
        val now = System.currentTimeMillis()
        val data = weatherData?.takeIf { prefs.weatherEnabled }
        val (rise, set) = data?.sunTimes(now) ?: localSunTimes(now)
        sky.setState(SkyState.compute(now, rise, set, data?.current?.code, data?.current?.cloudCover), durationMs)
    }

    /** Sem previsão: 6h/18h no fuso do aparelho. */
    private fun localSunTimes(now: Long): Pair<Long, Long> {
        val offset = TimeZone.getDefault().getOffset(now)
        val midnight = (now + offset).floorDiv(DAY_MS) * DAY_MS - offset
        return midnight + 6 * HOUR_MS to midnight + 18 * HOUR_MS
    }

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

    private fun computeSpanCount(): Int {
        val res = resources
        val available = res.displayMetrics.widthPixels - 2 * res.getDimensionPixelSize(R.dimen.row_padding_h)
        val cell = res.getDimensionPixelSize(R.dimen.card_width) +
            2 * res.getDimensionPixelSize(R.dimen.card_margin)
        return (available / cell).coerceIn(2, 8)
    }

    // ---------------------------------------------------------------- cards

    override fun onAppClick(app: AppEntry, card: View) = launch(app, card)

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

    private fun launch(app: AppEntry, card: View) {
        // Abre "crescendo" a partir do card: animação de janela do sistema, sem custo para nós.
        val options = ActivityOptions.makeScaleUpAnimation(card, 0, 0, card.width, card.height).toBundle()
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
        val items = listOf(
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
                R.drawable.ic_gradient, getString(R.string.opt_sky), getString(R.string.opt_sky_hint),
                checked = { prefs.skyEnabled },
            ) {
                setSkyEnabled(!prefs.skyEnabled)
            },
            SidePanel.Item(
                R.drawable.ic_weather, getString(R.string.opt_weather), getString(R.string.opt_weather_hint),
                keepOpen = true,
            ) {
                panel.push(weatherPage())
            },
            SidePanel.Item(R.drawable.ic_info, getString(R.string.opt_about), keepOpen = true) {
                panel.push(aboutPage())
            },
        )
        openPanel(SidePanel.Page(title = getString(R.string.launcher_options), items = items))
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
        val items = ArrayList<SidePanel.Item>(5)
        items += SidePanel.Item(
            R.drawable.ic_weather, getString(R.string.opt_weather_show), checked = { prefs.weatherEnabled },
        ) {
            setWeatherEnabled(!prefs.weatherEnabled)
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
        items += SidePanel.Item(R.drawable.ic_sync, getString(R.string.opt_weather_refresh)) {
            if (!prefs.weatherEnabled) setWeatherEnabled(true) else refreshWeather()
        }
        items += backItem()
        return SidePanel.Page(title = getString(R.string.opt_weather), items = items)
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
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        return SidePanel.Page(
            title = getString(R.string.app_name),
            subtitle = version?.let { getString(R.string.opt_version, it) },
            icon = getDrawable(R.drawable.ic_launcher),
            body = getString(R.string.about_text),
            items = listOf(backItem()),
        )
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS
        const val SKY_TICK_MS = 60 * 1000L
        const val SKY_TICK_ANIM_MS = 1500L
        /** Mudança de clima/entrada: transição lenta, quase imperceptível como corte. */
        const val SKY_CHANGE_MS = 2500L
        const val SPIN_MS = 900L
        const val SKY_OFF_MS = 700L
        const val WEATHER_REFRESH_MS = 30 * 60 * 1000L
        const val WEATHER_RETRY_MS = 5 * 60 * 1000L
    }
}
