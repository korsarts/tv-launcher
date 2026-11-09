package com.korsarts.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Indicadores do topo: rede (Wi-Fi com nível de sinal, cabo ou sem internet) no botão fixo
 * [netIcon], e espaço livre e RAM livre no [group], que pode ser escondido. Só observa a rede entre
 * [start] e [stop] (launcher na tela).
 *
 * [onBackOnline] roda quando a internet volta depois de ter caído.
 */
class SystemStatus(
    private val group: View,
    private val netIcon: ImageView,
    private val onBackOnline: () -> Unit,
) {

    enum class Net { WIFI, ETHERNET, OTHER, OFFLINE }

    /** Fotografia do estado, também usada pela página "Sistema" das opções. */
    class Snapshot(
        val net: Net,
        /** Nível do Wi-Fi, de 0 a 2 (só com [Net.WIFI]). */
        val wifiLevel: Int,
        val storageFree: Long,
        val storageTotal: Long,
        val ramFree: Long,
        val ramTotal: Long,
    )

    private val context: Context = group.context
    private val netLabel: TextView = group.findViewById(R.id.status_offline)
    private val text: TextView = group.findViewById(R.id.status_text)
    private val main = Handler(Looper.getMainLooper())

    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    private var started = false
    /** null = ainda não sabemos (primeira leitura não conta como "voltou"). */
    private var wasOnline: Boolean? = null

    @Suppress("DEPRECATION") // getColorStateList(int)/getColor(int): únicas versões na API 21
    private val netNormalTint: ColorStateList = context.resources.getColorStateList(R.color.top_button_tint)
    @Suppress("DEPRECATION")
    private val netAlertTint: ColorStateList = context.resources.getColorStateList(R.color.top_button_alert_tint)
    @Suppress("DEPRECATION")
    private val normalColor = context.resources.getColor(R.color.text_tertiary)
    @Suppress("DEPRECATION")
    private val alertColor = context.resources.getColor(R.color.alert_text)

    private val update = Runnable { refresh() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()

        /** Chega em outra thread e em rajadas: junta tudo numa leitura só, na main. */
        private fun changed() {
            main.removeCallbacks(update)
            main.postDelayed(update, DEBOUNCE_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connectivity?.registerNetworkCallback(request, callback)
        }
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(update)
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    fun setVisible(visible: Boolean) {
        group.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refresh()
    }

    /** Lê tudo de novo e atualiza o topo. Barato: chamado a cada minuto e a cada mudança de rede. */
    fun refresh() {
        val s = snapshot()
        val online = s.net != Net.OFFLINE
        if (online && wasOnline == false) onBackOnline()
        wasOnline = online

        netIcon.setImageResource(
            when (s.net) {
                Net.OFFLINE -> R.drawable.ic_wifi_off
                Net.ETHERNET -> R.drawable.ic_ethernet
                Net.WIFI -> when (s.wifiLevel) {
                    0 -> R.drawable.ic_wifi_1
                    1 -> R.drawable.ic_wifi_2
                    else -> R.drawable.ic_wifi_3
                }
                Net.OTHER -> R.drawable.ic_wifi_3
            }
        )
        netIcon.imageTintList = if (online) netNormalTint else netAlertTint
        netIcon.contentDescription = context.getString(netLabel(s.net))

        if (group.visibility != View.VISIBLE) return
        netLabel.visibility = if (online) View.GONE else View.VISIBLE

        text.text = context.getString(
            R.string.status_summary,
            Formatter.formatShortFileSize(context, s.storageFree),
            Formatter.formatShortFileSize(context, s.ramFree),
        )
        text.setTextColor(if (s.storageFree < LOW_STORAGE_BYTES) alertColor else normalColor)
    }

    fun snapshot(): Snapshot {
        val (net, level) = network()
        var free = 0L
        var total = 0L
        runCatching {
            val fs = StatFs(Environment.getDataDirectory().path)
            free = fs.availableBytes
            total = fs.totalBytes
        }
        val mem = ActivityManager.MemoryInfo()
        runCatching { activity?.getMemoryInfo(mem) }
        return Snapshot(net, level, free, total, mem.availMem, mem.totalMem)
    }

    /** Rede em uso e, no Wi-Fi, o nível do sinal. */
    private fun network(): Pair<Net, Int> = runCatching {
        val cm = connectivity ?: return@runCatching Net.OFFLINE to 0
        val net = if (Build.VERSION.SDK_INT >= 23) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                // VALIDATED: o Android confirmou que há saída para a internet, não só o roteador.
                caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Net.OFFLINE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Net.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net.ETHERNET
                else -> Net.OTHER
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            when {
                info == null || !info.isConnected -> Net.OFFLINE
                info.type == ConnectivityManager.TYPE_WIFI -> Net.WIFI
                info.type == ConnectivityManager.TYPE_ETHERNET -> Net.ETHERNET
                else -> Net.OTHER
            }
        }
        @Suppress("DEPRECATION") // connectionInfo/calculateSignalLevel: os substitutos são API 30+
        val level = if (net == Net.WIFI) WifiManager.calculateSignalLevel(wifi?.connectionInfo?.rssi ?: -100, WIFI_LEVELS) else 0
        net to level
    }.getOrDefault(Net.OTHER to 0) // sem conseguir ler, não acusa "sem internet" à toa

    companion object {
        private const val DEBOUNCE_MS = 400L
        private const val WIFI_LEVELS = 3
        const val LOW_STORAGE_BYTES = 500L * 1024 * 1024

        fun netLabel(net: Net): Int = when (net) {
            Net.WIFI -> R.string.status_net_wifi
            Net.ETHERNET -> R.string.status_net_ethernet
            Net.OTHER -> R.string.status_net_other
            Net.OFFLINE -> R.string.status_offline
        }
    }
}
