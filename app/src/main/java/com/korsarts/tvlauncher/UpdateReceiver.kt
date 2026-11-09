package com.korsarts.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Respostas do instalador do Android à sessão criada pelo [Updater]. Não exportado: só chega aqui
 * o PendingIntent que nós mesmos entregamos ao instalador.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // O Android quer a confirmação do usuário: abre a tela dele.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") // a versão com classe é API 33+
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            // Instalou: o sistema encerra e reabre o launcher já na versão nova.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Usuário cancelou na tela de confirmação: nada a avisar.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, context.getString(R.string.update_install_failed, detail), Toast.LENGTH_LONG).show()
            }
        }
    }
}
