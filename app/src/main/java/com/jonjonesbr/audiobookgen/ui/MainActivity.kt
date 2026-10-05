package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.DocumentRepository
import com.jonjonesbr.audiobookgen.domain.ehFormatoSuportado
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import kotlinx.coroutines.launch
import java.io.File

/**
 * Porta de entrada do app, sem tela própria (a antiga tela de conversão foi aposentada pela Fase P):
 *  - ícone do app e demais aberturas → Biblioteca;
 *  - arquivo compartilhado/aberto de outro app → entra na biblioteca e abre a tela do livro;
 *  - notificação de conversão concluída → toca o audiobook (no app ou em outro player, conforme a preferência);
 *  - pedido para iniciar a fila → tela Conversões.
 * O nome da classe fica porque notificações, widget e atalhos já gravados apontam para ela.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        if (!rotear(intent)) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!rotear(intent)) finish()
    }

    /** Devolve true quando ainda há algo em andamento (diálogo ou importação) e a activity deve esperar para se fechar. */
    private fun rotear(intent: Intent): Boolean {
        intent.getStringExtra(EXTRA_ABRIR_AUDIOBOOK_CAMINHO)?.let { caminho ->
            val nome = intent.getStringExtra(EXTRA_ABRIR_AUDIOBOOK_NOME) ?: File(caminho).nameWithoutExtension
            intent.removeExtra(EXTRA_ABRIR_AUDIOBOOK_CAMINHO)
            return abrirAudiobook(caminho, nome)
        }
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            importarCompartilhado(uri)
            return true
        }
        if (intent.getBooleanExtra(EXTRA_INICIAR_FILA, false)) {
            intent.removeExtra(EXTRA_INICIAR_FILA)
            startActivity(Intent(this, ConversoesActivity::class.java).putExtra(ConversoesActivity.EXTRA_INICIAR_FILA, true))
            return false
        }
        startActivity(Intent(this, MeusLivrosActivity::class.java))
        return false
    }

    // ── Arquivo compartilhado: vira livro da biblioteca ──────────────────────

    private fun importarCompartilhado(uri: Uri) {
        val nome = DocumentRepository(this).extrairMetadadosArquivo(uri).nome
        if (!ehFormatoSuportado(nome)) {
            Toast.makeText(this, getString(R.string.status_unsupported_format, nome.substringAfterLast('.', "")), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MeusLivrosActivity::class.java))
            finish()
            return
        }
        Toast.makeText(this, R.string.bib_importando, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val livro = runCatching { BibliotecaStore.importarDeUri(applicationContext, uri, nome) }.getOrNull()
            val biblioteca = Intent(this@MainActivity, MeusLivrosActivity::class.java)
            if (livro == null) {
                Toast.makeText(this@MainActivity, R.string.status_file_read_error_detail, Toast.LENGTH_LONG).show()
                startActivity(biblioteca)
            } else {
                // Biblioteca embaixo (mantém o tour/novidades de quem abriu o app por aqui) e a tela do livro por cima.
                startActivities(
                    arrayOf(biblioteca, Intent(this@MainActivity, LivroActivity::class.java).putExtra(LivroActivity.EXTRA_ID, livro.id))
                )
            }
            finish()
        }
    }

    // ── Notificação de conversão concluída ───────────────────────────────────

    private fun abrirAudiobook(caminho: String, nome: String): Boolean {
        val appPrefs = AppPrefs(this)
        return when (appPrefs.playerConversaoConcluida) {
            "app" -> { tocarNoApp(caminho, nome); false }
            "sistema" -> { abrirComAppExterno(caminho); false }
            else -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_escolher_player_title)
                    .setMessage(R.string.dialog_escolher_player_msg)
                    .setPositiveButton(R.string.dialog_escolher_player_app) { _, _ ->
                        appPrefs.playerConversaoConcluida = "app"
                        tocarNoApp(caminho, nome)
                    }
                    .setNegativeButton(R.string.dialog_escolher_player_outro) { _, _ ->
                        appPrefs.playerConversaoConcluida = "sistema"
                        abrirComAppExterno(caminho)
                    }
                    .setCancelable(false)
                    .setOnDismissListener { finish() }
                    .show()
                true
            }
        }
    }

    private fun tocarNoApp(caminho: String, nome: String) {
        val servico = Intent(this, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_INICIAR
            putExtra(AudioPlayerService.EXTRA_CAMINHO, caminho)
            putExtra(AudioPlayerService.EXTRA_NOME, nome)
        }
        androidx.core.content.ContextCompat.startForegroundService(this, servico)
        startActivity(Intent(this, LibraryActivity::class.java))
    }

    private fun abrirComAppExterno(caminho: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(caminho))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/mpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.chooser_abrir_audiobook)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_nenhum_player_instalado, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** Pedido para iniciar a fila de conversão (o processamento roda na tela Conversões). */
        const val EXTRA_INICIAR_FILA = "extra_iniciar_fila"

        // O widget lê estes campos para mostrar o estado do player.
        @Volatile var widgetIsPlaying: Boolean = false
        @Volatile var widgetTrackName: String = ""

        const val EXTRA_ABRIR_AUDIOBOOK_CAMINHO = "extra_abrir_audiobook_caminho"
        const val EXTRA_ABRIR_AUDIOBOOK_NOME = "extra_abrir_audiobook_nome"
    }
}
