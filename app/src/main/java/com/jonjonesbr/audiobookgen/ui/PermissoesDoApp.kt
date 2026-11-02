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
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.ExplicacaoBateria
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

    /** O que um pedido abriu: um diálogo (o do Android, respondido pelo launcher, ou a explicação da bateria),
     *  uma tela do sistema (volta no onResume) ou nada (o aparelho não tem a tela). */
    enum class Pedido { DIALOGO, TELA, NADA }

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

    /** [dica] (opcional): frase com o que tocar na tela do sistema, mostrada quando ela abre. */
    fun pedirNotificacoes(
        activity: Activity,
        pedido: ActivityResultLauncher<String>,
        @StringRes dica: Int? = null
    ): Pedido {
        val prefs = AppPrefs(activity)
        val podePedir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (
            !prefs.notificationPermissionPrompted ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            )
        if (podePedir) {
            prefs.notificationPermissionPrompted = true
            pedido.launch(Manifest.permission.POST_NOTIFICATIONS)
            return Pedido.DIALOGO
        }
        val tela = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        return abrirTela(activity, tela, dica)
    }

    /** [dica] (opcional): frase com o que tocar na tela do sistema, mostrada quando ela abre. */
    fun pedirAudios(
        activity: Activity,
        pedido: ActivityResultLauncher<String>,
        @StringRes dica: Int? = null
    ): Pedido {
        val prefs = AppPrefs(activity)
        if (!prefs.permissaoAudiosPedida || activity.shouldShowRequestPermissionRationale(permissaoAudios)) {
            prefs.permissaoAudiosPedida = true
            pedido.launch(permissaoAudios)
            return Pedido.DIALOGO
        }
        val tela = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", activity.packageName, null))
        return abrirTela(activity, tela, dica)
    }

    /**
     * Explica a tela de bateria antes de abri-la: a pergunta do Android (quando ainda há restrição) ou a lista
     * geral (quando o app já aparece nela). Em Xiaomi, Redmi e POCO, acrescenta a economia de bateria do próprio
     * sistema, com um botão para os ajustes do app. [motivo] (opcional) diz por que o app pede isso, antes da
     * explicação da tela. [aoResolver] recebe true se uma tela abriu (a volta dela chega no onResume) e false se
     * a pessoa recusou ou nada abriu. Com a Activity já fechando, não mostra nada e não chama [aoResolver].
     */
    fun explicarBateria(
        activity: AppCompatActivity,
        motivo: Int? = null,
        aoResolver: (abriu: Boolean) -> Unit = {}
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val xiaomi = ExplicacaoBateria.ehXiaomi(Build.MANUFACTURER)
        val tela = ExplicacaoBateria.telaDoPedido(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(activity))
        val partes = listOfNotNull(
            motivo?.let { activity.getString(it) },
            activity.getString(
                if (tela == ExplicacaoBateria.Tela.PERGUNTA) {
                    R.string.bateria_explica_pergunta
                } else {
                    R.string.bateria_explica_lista
                }
            ),
            if (xiaomi) activity.getString(R.string.bateria_explica_xiaomi) else null
        )
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.perm_bateria_titulo)
            .setMessage(partes.joinToString("\n\n"))
            .setPositiveButton(R.string.bateria_btn_continuar) { _, _ -> aoResolver(abrirBateria(activity)) }
            .setNegativeButton(R.string.perm_agora_nao) { _, _ -> aoResolver(false) }
            .setOnCancelListener { _ -> aoResolver(false) }
        if (xiaomi) {
            dialogo.setNeutralButton(R.string.bateria_btn_ajustes_app) { _, _ ->
                aoResolver(abrirAjustesDoApp(activity))
            }
        }
        // Fecha o diálogo se a Activity for destruída (ex.: girar a tela), para não vazar a janela (WindowLeaked).
        DialogosAbertos(activity).mostrar(dialogo)
    }

    /** Abre a tela de bateria; sem ela no aparelho, avisa e devolve false. */
    private fun abrirBateria(activity: Activity): Boolean {
        if (BatteryOptimizationHelper.abrir(activity)) return true
        Toast.makeText(activity, R.string.toast_config_bateria_indisponivel, Toast.LENGTH_SHORT).show()
        return false
    }

    /** Página do app nos ajustes do Android (onde a economia de bateria do Xiaomi fica). */
    private fun abrirAjustesDoApp(activity: Activity): Boolean = try {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", activity.packageName, null))
        )
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(activity, R.string.toast_config_bateria_indisponivel, Toast.LENGTH_SHORT).show()
        false
    }

    // A dica aparece já na tela do sistema: só depois de ela abrir, para não mostrar frase de tela que não existe.
    private fun abrirTela(activity: Activity, intent: Intent, @StringRes dica: Int? = null): Pedido = try {
        activity.startActivity(intent)
        dica?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
        Pedido.TELA
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(activity, R.string.toast_config_notificacoes_indisponivel, Toast.LENGTH_SHORT).show()
        Pedido.NADA
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
