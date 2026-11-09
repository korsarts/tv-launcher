package com.korsarts.tvlauncher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Atualização do próprio launcher pelos releases do GitHub: consulta a última versão publicada e,
 * se for mais nova, baixa o APK direto para uma sessão do instalador do Android (que confere a
 * assinatura e pede a confirmação do usuário). Callbacks chegam na main thread.
 */
class Updater(private val context: Context) {

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long) {
        fun toJson(): String = JSONObject()
            .put("version", version).put("notes", notes).put("apkUrl", apkUrl).put("size", size)
            .toString()

        companion object {
            fun fromJson(json: String?): Release? = runCatching {
                val o = JSONObject(json ?: return null)
                Release(o.getString("version"), o.optString("notes"), o.getString("apkUrl"), o.optLong("size"))
            }.getOrNull()
        }
    }

    sealed class Check {
        class Available(val release: Release) : Check()
        object UpToDate : Check()
        object Failed : Check()
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    /** true enquanto um download está em andamento. */
    @Volatile var installing = false
        private set

    /** Versão instalada, sem o "v" das tags (ex.: "1.11.0"). */
    val installedVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().removePrefix("v")

    fun isNewer(release: Release): Boolean = compareVersions(release.version, installedVersion) > 0

    /** No Android 8+, o usuário precisa liberar este app como fonte de instalação. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun check(callback: (Check) -> Unit) {
        executor.execute {
            val result = runCatching {
                val release = latestRelease()
                if (release != null && isNewer(release)) Check.Available(release) else Check.UpToDate
            }.getOrDefault(Check.Failed)
            deliver { callback(result) }
        }
    }

    /**
     * Baixa e entrega o APK ao instalador. [onProgress] recebe 0..100; [onDone] recebe true quando
     * o download terminou e a confirmação do Android vai aparecer (o resultado da instalação em
     * si chega ao [UpdateReceiver]).
     */
    fun install(release: Release, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        if (installing) return
        installing = true
        executor.execute {
            val ok = runCatching { download(release, onProgress) }.isSuccess
            installing = false
            deliver { onDone(ok) }
        }
    }

    fun shutdown() {
        closed = true
        executor.shutdownNow()
    }

    private fun deliver(block: () -> Unit) {
        if (!closed) main.post { if (!closed) block() }
    }

    // ------------------------------------------------------------------ rede (worker thread)

    /** Último release publicado que traz um APK, ou null se não houver. */
    private fun latestRelease(): Release? {
        val conn = open(LATEST_URL)
        val json = try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url")
            // Só aceita APK hospedado no próprio repositório.
            if (a.optString("name").endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX)) {
                // As notas automáticas do GitHub trazem só um link de comparação: não servem na TV.
                val notes = o.optString("body").lines()
                    .filterNot { "Full Changelog" in it }
                    .joinToString("\n").trim().take(MAX_NOTES)
                return Release(o.getString("tag_name").removePrefix("v"), notes, url, a.optLong("size"))
            }
        }
        return null
    }

    private fun download(release: Release, onProgress: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (release.size > 0) params.setSize(release.size)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val conn = open(release.apkUrl) // o GitHub redireciona para o CDN; HTTPS → HTTPS é seguido
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = if (release.size > 0) release.size else conn.contentLength.toLong()
                var done = 0L
                var lastPercent = -1
                session.openWrite("tv-launcher.apk", 0, if (total > 0) total else -1).use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt().coerceAtMost(100) else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                deliver { onProgress(percent) }
                            }
                        }
                    }
                    session.fsync(out)
                }
                if (total > 0 && done != total) throw IOException("download incompleto: $done de $total bytes")
            } finally {
                conn.disconnect()
            }
            // O instalador responde ao UpdateReceiver: primeiro pedindo a confirmação do usuário,
            // depois com o resultado. Intent explícito + mutável (o sistema preenche os extras).
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(callback.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "TVLauncher (Android)")
        return conn
    }

    companion object {
        private const val REPO = "korsarts/tv-launcher"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
        private const val TIMEOUT_MS = 15000
        private const val MAX_NOTES = 1200

        /** Compara "1.10.0" com "1.9.2" número a número (> 0 se [a] é mais nova). Sufixos são ignorados. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.removePrefix("v").split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
