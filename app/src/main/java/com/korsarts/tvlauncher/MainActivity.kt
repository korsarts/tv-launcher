package com.korsarts.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var favoritesView: RecyclerView
    private lateinit var appsView: RecyclerView
    private lateinit var favoritesEmpty: TextView
    private lateinit var favoritesAdapter: AppAdapter
    private lateinit var appsAdapter: AppAdapter

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Último resultado do PackageManager (todos os apps, antes de filtros). */
    private var allApps: List<AppEntry> = emptyList()
    private var loadGeneration = 0
    private var firstLoad = true
    private var reloadPending = false
    private var resumed = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (resumed) loadApps() else reloadPending = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        repo = AppRepository(this)

        favoritesView = findViewById(R.id.favorites)
        appsView = findViewById(R.id.apps)
        favoritesEmpty = findViewById(R.id.favorites_empty)

        favoritesAdapter = AppAdapter(::launch, ::showAppMenu)
        appsAdapter = AppAdapter(::launch, ::showAppMenu)

        favoritesView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        favoritesView.adapter = favoritesAdapter
        favoritesView.itemAnimator = null

        appsView.layoutManager = GridLayoutManager(this, computeSpanCount())
        appsView.adapter = appsAdapter
        appsView.itemAnimator = null

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            safeStart(Intent(Settings.ACTION_SETTINGS))
        }
        findViewById<ImageButton>(R.id.btn_options).setOnClickListener { showLauncherOptions() }

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
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        executor.shutdownNow()
        super.onDestroy()
    }

    /** HOME pressionado com o launcher já aberto: volta ao início. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        appsView.scrollToPosition(0)
        focusFirstItem()
    }

    /** Launcher não deve fechar com BACK. */
    @Deprecated("Deprecated in Java")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        appsView.scrollToPosition(0)
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

        favoritesAdapter.hidden = hidden
        appsAdapter.hidden = if (showHidden) hidden else emptySet()

        // Se o app focado vai sumir (desinstalado/desabilitado), foca o vizinho em vez de
        // deixar o RecyclerView jogar o foco para os botões do topo.
        val refocus = focusTargetAfterRemoval(favorites, apps)
        favoritesAdapter.submitList(favorites)
        appsAdapter.submitList(apps) {
            when {
                firstLoad -> { firstLoad = false; focusFirstItem() }
                refocus != null -> focusPosition(refocus.first, refocus.second)
            }
        }
        favoritesView.visibility = if (favorites.isEmpty()) View.GONE else View.VISIBLE
        favoritesEmpty.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
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

    private fun focusFirstItem() {
        val target = if (favoritesAdapter.itemCount > 0) favoritesView else appsView
        focusPosition(target, 0)
    }

    /** Foca o item na posição após o próximo layout (antes disso a view pode não existir). */
    private fun focusPosition(rv: RecyclerView, position: Int) {
        rv.scrollToPosition(position)
        rv.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                (rv.layoutManager?.findViewByPosition(position) ?: rv.getChildAt(0))?.requestFocus()
            }
        })
        rv.requestLayout()
    }

    private fun computeSpanCount(): Int {
        val res = resources
        val dm = res.displayMetrics
        val available = dm.widthPixels - 2 * res.getDimensionPixelSize(R.dimen.screen_padding_h)
        val cell = res.getDimensionPixelSize(R.dimen.card_width) +
            2 * res.getDimensionPixelSize(R.dimen.card_margin)
        return (available / cell).coerceIn(2, 8)
    }

    // ---------------------------------------------------------------- ações

    private fun launch(app: AppEntry) {
        if (!safeStart(app.launchIntent())) {
            // Fallback: intent de launch padrão do pacote.
            val fallback = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: packageManager.getLaunchIntentForPackage(app.packageName)
            if (fallback == null || !safeStart(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) { // ActivityNotFoundException, SecurityException
        false
    }

    private fun showAppMenu(app: AppEntry) {
        val isFav = app.packageName in prefs.favorites
        val isHidden = app.packageName in prefs.hidden
        val actions = mutableListOf<Pair<Int, () -> Unit>>()

        if (isFav) {
            actions += R.string.action_remove_favorite to { prefs.toggleFavorite(app.packageName) }
            actions += R.string.action_move_left to { prefs.moveFavorite(app.packageName, -1) }
            actions += R.string.action_move_right to { prefs.moveFavorite(app.packageName, +1) }
        } else {
            actions += R.string.action_add_favorite to { prefs.toggleFavorite(app.packageName) }
        }
        actions += (if (isHidden) R.string.action_unhide else R.string.action_hide) to {
            prefs.toggleHidden(app.packageName)
        }
        actions += R.string.action_app_info to {
            safeStart(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        if (!app.isSystem) {
            actions += R.string.action_uninstall to {
                @Suppress("DEPRECATION")
                safeStart(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", app.packageName, null)))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
                actions[which].second()
                render()
                favoritesAdapter.notifyDataSetChanged()
                appsAdapter.notifyDataSetChanged()
            }
            .show()
    }

    private fun showLauncherOptions() {
        fun yn(b: Boolean) = getString(if (b) R.string.on else R.string.off)
        val items = arrayOf(
            getString(R.string.opt_system_settings),
            getString(R.string.opt_show_phone_apps, yn(prefs.showPhoneApps)),
            getString(R.string.opt_show_hidden, yn(prefs.showHidden)),
            getString(R.string.opt_redirect, yn(prefs.redirectStockLauncher)),
            getString(R.string.opt_accessibility),
            getString(R.string.opt_about),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.launcher_options)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> safeStart(Intent(Settings.ACTION_SETTINGS))
                    1 -> { prefs.showPhoneApps = !prefs.showPhoneApps; loadApps() }
                    2 -> {
                        prefs.showHidden = !prefs.showHidden
                        render(); appsAdapter.notifyDataSetChanged()
                    }
                    3 -> prefs.redirectStockLauncher = !prefs.redirectStockLauncher
                    4 -> if (!safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                        Toast.makeText(this, R.string.accessibility_missing, Toast.LENGTH_LONG).show()
                    }
                    5 -> AlertDialog.Builder(this)
                        .setTitle(R.string.app_name)
                        .setMessage(R.string.about_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .show()
    }
}
