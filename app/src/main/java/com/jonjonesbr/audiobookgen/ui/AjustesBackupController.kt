package com.jonjonesbr.audiobookgen.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.AppPrefsBackup
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.BackupException
import com.jonjonesbr.audiobookgen.domain.BackupUseCase
import com.jonjonesbr.audiobookgen.domain.MotivoBackup
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import com.jonjonesbr.audiobookgen.util.mostrarErro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

// Indentação do JSON das configurações exportadas (legível para quem abrir o arquivo).
private const val JSON_INDENT = 4

/**
 * Backup dos Ajustes (rb-11): exportar e restaurar as configurações em JSON ou o backup completo em ZIP.
 * Extraído da [SettingsActivity]. Os lançadores de arquivo continuam na Activity (registrados antes do
 * STARTED): este controller só os chama por [Callbacks].
 */
class AjustesBackupController(
    private val activity: AppCompatActivity,
    private val pythonEngine: PythonEngineUseCase,
    private val callbacks: Callbacks,
) {
    /** Lançadores da Activity e a recarga da tela de configurações depois de importar. */
    data class Callbacks(
        val lancarExportarConfig: (String) -> Unit,
        val lancarExportarBackup: (String) -> Unit,
        val lancarRestaurar: (Array<String>) -> Unit,
        val aoImportarConfig: () -> Unit,
    )

    private val appPrefs by lazy { AppPrefs(activity) }
    private val dialogos = DialogosAbertos(activity)
    private val backupUseCase by lazy { BackupUseCase(activity) }

    /** "Fazer backup": tudo (recomendado) ou só as configurações. */
    fun escolherBackupAFazer() {
        val opcoes = arrayOf(
            activity.getString(R.string.aj_backup_tudo),
            activity.getString(R.string.aj_backup_so_config),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.aj_backup_fazer)
            .setItems(opcoes) { _, qual -> if (qual == 0) exportarBackupCompleto() else exportarConfiguracoes() }
            .setNegativeButton(android.R.string.cancel, null)
            .let { dialogos.mostrar(it) }
    }

    fun restaurarBackup() {
        try {
            callbacks.lancarRestaurar(
                arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain")
            )
        } catch (e: ActivityNotFoundException) {
            Log.w("SettingsActivity", "Erro ao iniciar restauracao", e)
            val msg = activity.getString(R.string.toast_erro_importar_backup, activity.getString(mensagemDeErro(e)))
            activity.mostrarErro(msg)
        }
    }

    /** ZIP (começa com "PK") é o backup completo; o resto é tratado como o JSON das configurações. */
    fun restaurarDeArquivo(uri: Uri) {
        activity.lifecycleScope.launch {
            val ehZip = withContext(Dispatchers.IO) {
                runCatching {
                    activity.contentResolver.openInputStream(uri)?.use {
                        it.read() == 'P'.code && it.read() == 'K'.code
                    }
                }.getOrNull() == true
            }
            if (ehZip) restaurarBackupCompleto(uri) else carregarConfigJson(uri)
        }
    }

    fun salvarConfigJson(uri: Uri) {
        try {
            val json = AppPrefsBackup.paraJson(appPrefs)
            val stream = activity.contentResolver.openOutputStream(uri)
            if (stream == null) {
                Log.w("SettingsActivity", "openOutputStream retornou null — sem permissão de escrita no provedor")
                activity.mostrarErro(R.string.toast_erro_exportar_null)
                return
            }
            stream.use { outputStream ->
                outputStream.write(json.toString(JSON_INDENT).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(activity, R.string.toast_backup_exportado, Toast.LENGTH_SHORT).show()
        } catch (e: java.io.IOException) {
            Log.w("SettingsActivity", "Erro ao salvar configuracoes", e)
            val msg = activity.getString(R.string.toast_erro_exportar, activity.getString(mensagemDeErro(e)))
            activity.mostrarErro(msg)
        } catch (e: JSONException) {
            Log.w("SettingsActivity", "Erro ao salvar configuracoes", e)
            val msg = activity.getString(R.string.toast_erro_exportar, activity.getString(mensagemDeErro(e)))
            activity.mostrarErro(msg)
        }
    }

    fun salvarBackupCompleto(uri: Uri) {
        activity.lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) { backupUseCase.exportar(uri) }
            resultado.onSuccess {
                val msg = R.string.toast_backup_completo_exportado
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Log.w("SettingsActivity", "Erro ao exportar backup completo", e)
                val msg = activity.getString(R.string.toast_erro_exportar_backup, activity.getString(mensagemDeErro(e)))
                activity.mostrarErro(msg)
            }
        }
    }

    private fun exportarConfiguracoes() {
        try {
            callbacks.lancarExportarConfig("lylyreader-config.json")
        } catch (e: ActivityNotFoundException) {
            Log.w("SettingsActivity", "Erro ao iniciar exportacao", e)
            val msg = activity.getString(R.string.toast_erro_exportar, activity.getString(mensagemDeErro(e)))
            activity.mostrarErro(msg)
        }
    }

    private fun exportarBackupCompleto() {
        try {
            callbacks.lancarExportarBackup("lylyreader-backup.zip")
        } catch (e: ActivityNotFoundException) {
            Log.w("SettingsActivity", "Erro ao iniciar exportacao de backup", e)
            val msg = activity.getString(R.string.toast_erro_exportar_backup, activity.getString(mensagemDeErro(e)))
            activity.mostrarErro(msg)
        }
    }

    private fun carregarConfigJson(uri: Uri) {
        activity.lifecycleScope.launch {
            val (json, erro) = withContext(Dispatchers.IO) { lerJsonDeUri(activity.contentResolver, uri) }
            if (erro != null) {
                val msg = activity.getString(R.string.toast_erro_importar, activity.getString(mensagemDeErro(erro)))
                activity.mostrarErro(msg)
                return@launch
            }
            if (json == null) return@launch

            AppPrefsBackup.aplicar(appPrefs, json)

            // Sincroniza com o motor Python
            launch {
                pythonEngine.sincronizarConfiguracoes(
                    appPrefs.motorTts,
                    SecurePreferences.getGeminiKeys(activity),
                    appPrefs.pausaMs
                )
            }

            // Recarrega configs na UI
            callbacks.aoImportarConfig()

            // Dialog de sucesso
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_importar_sucesso_titulo)
                .setMessage(R.string.dialog_importar_sucesso_mensagem)
                .setPositiveButton(R.string.dialog_ok, null)
                .let { dialogos.mostrar(it) }
        }
    }

    private fun restaurarBackupCompleto(uri: Uri) {
        activity.lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) { backupUseCase.importar(uri) }
            resultado.onSuccess { resumo -> mostrarResumoBackupImportado(resumo) }
                .onFailure { e ->
                    Log.w("SettingsActivity", "Erro ao importar backup completo", e)
                    val motivo = (e as? BackupException)?.motivo
                    val msg = when (motivo) {
                        MotivoBackup.ARQUIVO_INVALIDO -> activity.getString(R.string.backup_erro_arquivo_invalido)
                        MotivoBackup.VERSAO_MAIS_NOVA -> activity.getString(R.string.backup_erro_versao_mais_nova)
                        MotivoBackup.MUITO_GRANDE -> activity.getString(R.string.backup_erro_muito_grande)
                        null -> activity.getString(
                            R.string.toast_erro_importar_backup, activity.getString(mensagemDeErro(e))
                        )
                    }
                    activity.mostrarErro(msg)
                }
        }
    }

    private fun mostrarResumoBackupImportado(resumo: BackupUseCase.ImportSummary) {
        if (resumo.configImportado) {
            activity.lifecycleScope.launch {
                pythonEngine.sincronizarConfiguracoes(
                    appPrefs.motorTts,
                    SecurePreferences.getGeminiKeys(activity),
                    appPrefs.pausaMs
                )
            }
            callbacks.aoImportarConfig()
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_importar_backup_sucesso_titulo)
            .setMessage(
                activity.getString(
                    R.string.dialog_importar_backup_sucesso_mensagem,
                    resumo.bookmarks,
                    resumo.highlights,
                    resumo.progresso
                )
            )
            .setPositiveButton(R.string.dialog_ok, null)
            .let { dialogos.mostrar(it) }
    }
}

/**
 * Lê e parseia o JSON do Uri. Roda em IO; devolve (json, erro) para a main mostrar o erro.
 * Uma única saída (ReturnCount do detekt): o `try` já é a expressão devolvida.
 */
private fun lerJsonDeUri(resolver: ContentResolver, uri: Uri): Pair<JSONObject?, Exception?> {
    return try {
        val jsonStr = resolver.openInputStream(uri)?.use { inputStream ->
            inputStream.bufferedReader().use { it.readText() }
        } ?: error("Input stream is null")
        JSONObject(jsonStr) to null
    } catch (e: java.io.IOException) {
        Log.w("SettingsActivity", "Erro ao carregar configuracoes", e)
        null to e
    } catch (e: java.lang.IllegalStateException) {
        Log.w("SettingsActivity", "Erro ao carregar configuracoes", e)
        null to e
    } catch (e: JSONException) {
        Log.w("SettingsActivity", "Erro ao parsear JSON", e)
        null to e
    }
}
