package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar

/**
 * "Livros grátis" aberta pela Biblioteca: a busca ([BuscaLivrosPainel]) e, ao tocar em "Baixar e usar", o livro
 * guardado na Biblioteca é devolvido a quem abriu a tela (resultado com a URI), que o trata como um arquivo
 * escolhido no seletor. A mesma busca fica na aba "Livros grátis" de Downloads, que não fecha ao baixar.
 */
class BuscarLivrosActivity : AppCompatActivity() {

    private lateinit var painel: BuscaLivrosPainel
    private val baixarTodosDebug = Runnable { painel.baixarTodosOsResultados() }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buscar_livros)
        // O fim da lista (aviso do acervo) fica acima da barra de gestos.
        findViewById<View>(R.id.recyclerLivros).aplicarPaddingInferiorComNavigationBar()
        findViewById<ImageButton>(R.id.btnVoltarBuscarLivros).setOnClickListener { finish() }

        painel = BuscaLivrosPainel(
            this,
            findViewById(R.id.painelBuscaLivros),
            aoGuardar = { livro, noAcervo ->
                val arquivo = BibliotecaStore.arquivoDo(applicationContext, noAcervo)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", arquivo)
                setResult(RESULT_OK, Intent().setData(uri).putExtra(EXTRA_TITULO, livro.titulo))
                finish()
            },
            aoVerDownloads = {
                startActivity(
                    Intent(this, DownloadCentralActivity::class.java)
                        .putExtra(DownloadCentralActivity.EXTRA_ABA_LIVROS, true)
                )
            }
        )
        painel.iniciar()

        // Só em build de depuração: baixa os N primeiros resultados sem tocar na tela (o MIUI bloqueia toque por adb).
        val depuravel = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val n = intent.getIntExtra("extra_debug_baixar_n", -1)
        if (depuravel && n > 0) {
            painel.limiteBaixar = n
            findViewById<View>(R.id.painelBuscaLivros).postDelayed(baixarTodosDebug, ESPERA_DEBUG_MS)
        }
    }

    override fun onDestroy() {
        findViewById<View>(R.id.painelBuscaLivros).removeCallbacks(baixarTodosDebug)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITULO = "titulo_livro"
        private const val ESPERA_DEBUG_MS = 6_000L
    }
}
