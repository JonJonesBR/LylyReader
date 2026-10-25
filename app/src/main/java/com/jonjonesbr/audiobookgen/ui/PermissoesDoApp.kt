package com.jonjonesbr.audiobookgen.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.util.BatteryOptimizationHelper

/**
 * As permissões que o app oferece de saída (página "Para o app funcionar bem" das boas-vindas, aviso único na
 * Biblioteca e aviso ao iniciar uma conversão): notificações, áudios do aparelho e bateria sem restrição.
 * O microfone continua sendo pedido só na hora de gravar uma voz.
 *
 * Cada pedido usa o diálogo do Android enquanto ele ainda aparece; depois de recusado de vez (ou, nas
 * notificações, antes do Android 13), abre a tela do app nas configurações, onde dá para liberar à mão.
 */
object PermissoesDoApp {

    /** Ler os áudios do aparelho: lista os audiobooks da pasta pública na aba Audiobooks. */
    val permissaoAudios: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun notificacoesLiberadas(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun audiosLiberados(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, permissaoAudios) == PackageManager.PERMISSION_GRANTED

    fun bateriaLiberada(ctx: Context) = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx)

    fun pedirNotificacoes(activity: Activity, pedido: ActivityResultLauncher<String>) {
        val prefs = AppPrefs(activity)
        val podePedir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (
            !prefs.notificationPermissionPrompted ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            )
        if (podePedir) {
            prefs.notificationPermissionPrompted = true
            pedido.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val tela = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            abrirTela(activity, tela)
        }
    }

    fun pedirAudios(activity: Activity, pedido: ActivityResultLauncher<String>) {
        val prefs = AppPrefs(activity)
        if (!prefs.permissaoAudiosPedida || activity.shouldShowRequestPermissionRationale(permissaoAudios)) {
            prefs.permissaoAudiosPedida = true
            pedido.launch(permissaoAudios)
        } else {
            val tela = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", activity.packageName, null))
            abrirTela(activity, tela)
        }
    }

    fun pedirBateria(activity: Activity) {
        if (!BatteryOptimizationHelper.abrir(activity)) {
            Toast.makeText(activity, R.string.toast_config_bateria_indisponivel, Toast.LENGTH_SHORT).show()
        }
    }

    private fun abrirTela(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.toast_config_notificacoes_indisponivel, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Quem já usava o app não passa de novo pelas boas-vindas: com as notificações desligadas, pergunta uma vez.
     * @return true se o aviso apareceu.
     */
    fun oferecerNotificacoesUmaVez(activity: Activity, pedido: ActivityResultLauncher<String>): Boolean {
        val prefs = AppPrefs(activity)
        if (prefs.avisoNotificacoesVisto || notificacoesLiberadas(activity)) return false
        prefs.avisoNotificacoesVisto = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.perm_aviso_titulo)
            .setMessage(R.string.perm_aviso_msg)
            .setPositiveButton(R.string.perm_ativar) { _, _ -> pedirNotificacoes(activity, pedido) }
            .setNegativeButton(R.string.perm_agora_nao, null)
            .show()
        return true
    }

    /**
     * Ao iniciar uma conversão com as notificações desligadas (uma vez): sem elas, o andamento e o aviso de
     * audiobook pronto não aparecem fora do app. @return true se o aviso apareceu.
     */
    fun avisarNaConversaoUmaVez(activity: Activity, pedido: ActivityResultLauncher<String>): Boolean {
        val prefs = AppPrefs(activity)
        if (prefs.dicaNotificacaoConversaoVista || notificacoesLiberadas(activity)) return false
        prefs.dicaNotificacaoConversaoVista = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.perm_aviso_titulo)
            .setMessage(R.string.perm_conversao_msg)
            .setPositiveButton(R.string.perm_ativar) { _, _ -> pedirNotificacoes(activity, pedido) }
            .setNegativeButton(R.string.perm_agora_nao, null)
            .show()
        return true
    }
}
