package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.PoliticaDownloadSilencioso
import com.jonjonesbr.audiobookgen.service.DownloadCentral
import com.jonjonesbr.audiobookgen.util.NetworkStatus
import com.jonjonesbr.audiobookgen.util.ThemePrefs
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Primeiro acesso "Escolha seu livro": três caminhos (aparelho, biblioteca pública, link), o livro de exemplo e
 * "Agora não". A tela não importa nada: devolve a escolha a [MeusLivrosActivity], que já tem os seletores e as
 * ações (sem duplicar o fluxo). Também inicia, uma vez, o download offline do pacote recomendado em segundo plano.
 * A voz continua a padrão (Edge TTS); esta tela não pergunta voz.
 */
class EscolhaPrimeiroLivroActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_escolha_primeiro_livro)
        // Oferecida uma vez: ao abrir já conta como vista (sair por qualquer caminho não a traz de volta).
        AppPrefs(this).voiceDownloadOnboardingDone = true
        // O listener do helper de navigation bar substitui o fitsSystemWindows (título ficava sob a barra de status):
        // aqui somamos topo e base das barras do sistema ao padding da própria ScrollView.
        findViewById<View>(R.id.scrollEscolhaLivro).let { raiz ->
            val base = raiz.paddingBottom
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
                val barras = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, barras.top, v.paddingRight, base + barras.bottom)
                insets
            }
        }
        findViewById<Button>(R.id.btnEscolhaAparelho).setOnClickListener { escolher(ESCOLHA_APARELHO) }
        findViewById<Button>(R.id.btnEscolhaBiblioteca).setOnClickListener { escolher(ESCOLHA_BIBLIOTECA) }
        findViewById<Button>(R.id.btnEscolhaLink).setOnClickListener { escolher(ESCOLHA_LINK) }
        findViewById<Button>(R.id.btnEscolhaExemplo).setOnClickListener { escolher(ESCOLHA_EXEMPLO) }
        findViewById<Button>(R.id.btnEscolhaAgoraNao).setOnClickListener { finish() }
        iniciarDownloadSilencioso()
    }

    private fun escolher(escolha: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ESCOLHA, escolha))
        finish()
    }

    /**
     * Enfileira o pacote recomendado em segundo plano, uma vez só. Só age fora de rede tarifada (e com internet),
     * com espaço de sobra e se o pacote ainda não estiver baixado (ver [PoliticaDownloadSilencioso]).
     */
    private fun iniciarDownloadSilencioso() {
        val prefs = AppPrefs(this)
        if (prefs.downloadOfflineSilenciosoIniciado) return
        lifecycleScope.launch {
            val pacote = withContext(Dispatchers.IO) {
                VoiceCatalog.pacotesRegistrados()
                    .firstOrNull { it.id == PoliticaDownloadSilencioso.PACOTE_RECOMENDADO }
            } ?: return@launch
            val pronto = withContext(Dispatchers.IO) { pacote.isPronto(applicationContext) }
            if (pronto) {
                prefs.downloadOfflineSilenciosoIniciado = true
                return@launch
            }
            val redeTarifada = NetworkStatus.isMetered(applicationContext) || !NetworkStatus.isOnline(applicationContext)
            val tamanhoBytes = pacote.tamanhoDownloadMb.toLong() * BYTES_POR_MB
            if (!PoliticaDownloadSilencioso.deveIniciar(false, redeTarifada, filesDir.usableSpace, tamanhoBytes)) return@launch
            DownloadCentral.enfileirar(applicationContext, listOf(pacote.id))
            prefs.downloadOfflineSilenciosoIniciado = true
        }
    }

    companion object {
        /** Chave do resultado: qual caminho foi escolhido (ver as constantes ESCOLHA_*). */
        const val EXTRA_ESCOLHA = "extra_escolha_primeiro_livro"
        const val ESCOLHA_APARELHO = "aparelho"
        const val ESCOLHA_BIBLIOTECA = "biblioteca"
        const val ESCOLHA_LINK = "link"
        const val ESCOLHA_EXEMPLO = "exemplo"
        private const val BYTES_POR_MB = 1024L * 1024L
    }
}
