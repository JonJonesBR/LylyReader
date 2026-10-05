package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.PackageInfoCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs

/**
 * Nota de novidades pós-atualização: mostra o que mudou/foi consertado na versão atual, uma
 * única vez, na primeira abertura depois de uma atualização (comparando o `versionCode`
 * instalado, via [PackageInfoCompat] — mesmo padrão de leitura de versão do
 * [CrashLogWriter][com.jonjonesbr.audiobookgen.util.CrashLogWriter] — contra o salvo em
 * [AppPrefs.ultimaVersaoNotasVista]). Instalação nova (nunca aberto antes) só registra o
 * versionCode atual, sem mostrar nada — não há "novidade" pra anunciar num app que o usuário
 * está abrindo pela 1ª vez.
 *
 * O texto (`release_notes_corpo`) descreve só a versão mais recente — é sobrescrito a cada
 * release (sem histórico acumulado dentro do app).
 */
object ReleaseNotesDialog {

    /** O diálogo de novidades está (ou vai estar) na tela: dicas esperam. */
    fun pendente(activity: Activity): Boolean {
        val pkgInfo = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        } catch (_: Exception) {
            return false
        }
        val versaoAtual = PackageInfoCompat.getLongVersionCode(pkgInfo).toInt()
        val ultimaVista = AppPrefs(activity).ultimaVersaoNotasVista
        return ultimaVista != 0 && versaoAtual > ultimaVista
    }

    fun mostrarSeNecessario(activity: Activity) {
        val pkgInfo = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        } catch (_: Exception) {
            null
        } ?: return
        val versaoNome = pkgInfo.versionName ?: return
        val versaoAtual = PackageInfoCompat.getLongVersionCode(pkgInfo).toInt()

        val prefs = AppPrefs(activity)
        val ultimaVista = prefs.ultimaVersaoNotasVista

        when {
            ultimaVista == 0 -> prefs.ultimaVersaoNotasVista = versaoAtual
            versaoAtual > ultimaVista ->
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.release_notes_titulo, versaoNome))
                    .setMessage(R.string.release_notes_corpo)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs.ultimaVersaoNotasVista = versaoAtual
                    }
                    .setCancelable(false)
                    .show()
        }
    }
}
