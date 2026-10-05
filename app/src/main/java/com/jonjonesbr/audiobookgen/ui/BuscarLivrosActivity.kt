package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.LivrosClient
import com.jonjonesbr.audiobookgen.domain.FonteLivros
import com.jonjonesbr.audiobookgen.domain.IdiomaBusca
import com.jonjonesbr.audiobookgen.domain.LivroEncontrado
import com.jonjonesbr.audiobookgen.domain.origemDaChave
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Busca de livros em domínio público / licença livre (Projeto Gutenberg, Wikisource e Internet Archive). Ao tocar em "Baixar e usar"
 * o EPUB é baixado e devolvido a quem abriu a tela (resultado com a URI), que o trata como um arquivo
 * escolhido no seletor. Fontes piratas ficam de fora por decisão do projeto.
 */
class BuscarLivrosActivity : AppCompatActivity() {

    private lateinit var etBusca: EditText
    private lateinit var rgIdioma: com.google.android.material.chip.ChipGroup
    private lateinit var rgFonte: com.google.android.material.chip.ChipGroup
    private lateinit var tvStatus: TextView
    private lateinit var btnMais: Button
    private lateinit var adapter: LivrosAdapter

    private val livros = mutableListOf<LivroEncontrado>()
    private var proximoInicio: Int? = null
    private var buscaAtual: Job? = null
    private var baixando: String? = null
    private var limiteBaixar = MAX_PARA_BAIXAR_TODOS

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buscar_livros)
        findViewById<View>(R.id.tvAvisoLivros).aplicarPaddingInferiorComNavigationBar()
        findViewById<ImageButton>(R.id.btnVoltarBuscarLivros).setOnClickListener { finish() }
        etBusca = findViewById(R.id.etBuscaLivro)
        rgIdioma = findViewById(R.id.rgIdiomaLivro)
        rgFonte = findViewById(R.id.rgFonteLivro)
        tvStatus = findViewById(R.id.tvStatusLivros)
        btnMais = findViewById(R.id.btnMaisLivros)

        adapter = LivrosAdapter(lifecycleScope, onBaixar = ::baixar)
        findViewById<RecyclerView>(R.id.recyclerLivros).apply {
            layoutManager = LinearLayoutManager(this@BuscarLivrosActivity)
            adapter = this@BuscarLivrosActivity.adapter
            itemAnimator = null
        }

        findViewById<Button>(R.id.btnBuscarLivro).setOnClickListener { buscar(reiniciar = true) }
        etBusca.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_SEARCH) buscar(reiniciar = true)
            acao == EditorInfo.IME_ACTION_SEARCH
        }
        rgIdioma.setOnCheckedStateChangeListener { _, _ -> buscar(reiniciar = true) }
        rgFonte.setOnCheckedStateChangeListener { _, _ ->
            ajustarParaFonte()
            buscar(reiniciar = true)
        }
        btnMais.setOnClickListener { buscar(reiniciar = false) }
        findViewById<Button>(R.id.btnBaixarTodosLivros).setOnClickListener { baixarTodosOsResultados() }

        // Lista inicial: os mais baixados (no idioma escolhido).
        buscar(reiniciar = true)

        // Só em build de depuração: baixa os N primeiros resultados sem tocar na tela (o MIUI bloqueia toque por adb).
        val depuravel = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val n = intent.getIntExtra("extra_debug_baixar_n", -1)
        if (depuravel && n > 0) {
            limiteBaixar = n
            findViewById<View>(R.id.btnBaixarTodosLivros).postDelayed({ baixarTodosOsResultados() }, 6_000)
        }
    }

    private fun fonteEscolhida() = when (rgFonte.checkedChipId) {
        R.id.rbFonteWikisource -> FonteLivros.WIKISOURCE
        R.id.rbFonteArchive -> FonteLivros.ARCHIVE
        else -> FonteLivros.GUTENBERG
    }

    /** O Wikisource é um site por idioma (não há "todos") e só busca com texto digitado. */
    private fun ajustarParaFonte() {
        val wiki = fonteEscolhida() == FonteLivros.WIKISOURCE
        findViewById<View>(R.id.rbIdiomaTodos).visibility = if (wiki) View.GONE else View.VISIBLE
        if (wiki && rgIdioma.checkedChipId == R.id.rbIdiomaTodos) rgIdioma.check(R.id.rbIdiomaPt)
        findViewById<TextView>(R.id.tvAvisoLivros).setText(
            when (fonteEscolhida()) {
                FonteLivros.WIKISOURCE -> R.string.livros_aviso_wikisource
                FonteLivros.ARCHIVE -> R.string.livros_aviso_archive
                FonteLivros.GUTENBERG -> R.string.livros_aviso
            }
        )
    }

    private fun idiomaEscolhido(): IdiomaBusca = when (rgIdioma.checkedChipId) {
        R.id.rbIdiomaPt -> IdiomaBusca.PT
        R.id.rbIdiomaEn -> IdiomaBusca.EN
        R.id.rbIdiomaEs -> IdiomaBusca.ES
        else -> IdiomaBusca.TODOS
    }

    @Suppress("TooGenericExceptionCaught")
    private fun buscar(reiniciar: Boolean) {
        buscaAtual?.cancel()
        if (reiniciar) {
            livros.clear()
            proximoInicio = null
            adapter.submitList(emptyList())
            btnMais.visibility = View.GONE
        }
        val fonte = fonteEscolhida()
        val inicio = if (reiniciar) null else proximoInicio ?: return
        if (fonte == FonteLivros.WIKISOURCE && etBusca.text.isBlank()) {
            tvStatus.setText(R.string.livros_digite_busca)
            return
        }
        tvStatus.setText(R.string.livros_buscando)
        buscaAtual = lifecycleScope.launch {
            try {
                val r = LivrosClient.buscar(fonte, etBusca.text.toString(), idiomaEscolhido(), inicio)
                livros += r.livros
                proximoInicio = r.proximoInicio
                adapter.submitList(livros.toList())
                marcarJaBaixados()
                btnMais.visibility = if (proximoInicio != null) View.VISIBLE else View.GONE
                findViewById<View>(R.id.btnBaixarTodosLivros).visibility = if (livros.isEmpty()) View.GONE else View.VISIBLE
                tvStatus.text = if (livros.isEmpty()) getString(R.string.livros_nada) else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvStatus.text = getString(R.string.livros_erro_busca, e.message ?: "")
            }
        }
    }

    /**
     * "Coleção completa": carrega as próximas páginas da busca atual (até [MAX_PARA_BAIXAR_TODOS] livros) e põe todos na fila de
     * downloads em segundo plano, com notificação. Ex.: buscar um autor e baixar a obra toda de uma vez.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun baixarTodosOsResultados() {
        val botao = findViewById<Button>(R.id.btnBaixarTodosLivros)
        botao.isEnabled = false
        buscaAtual?.cancel()
        lifecycleScope.launch {
            try {
                while (proximoInicio != null && livros.size < limiteBaixar) {
                    tvStatus.text = getString(R.string.livros_carregando_todos, livros.size)
                    val r = LivrosClient.buscar(fonteEscolhida(), etBusca.text.toString(), idiomaEscolhido(), proximoInicio)
                    livros += r.livros
                    proximoInicio = r.proximoInicio
                }
                adapter.submitList(livros.toList())
                val entraram = com.jonjonesbr.audiobookgen.service.DownloadCentral.baixarLivros(applicationContext, livros.take(limiteBaixar))
                tvStatus.text = ""
                val aviso = if (entraram > 0) getString(R.string.livros_na_fila, entraram) else getString(R.string.livros_nada_novo)
                com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), aviso, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction(R.string.conv_ver) { startActivity(Intent(this@BuscarLivrosActivity, DownloadCentralActivity::class.java)) }
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvStatus.text = getString(R.string.livros_erro_busca, e.message ?: "")
            } finally {
                botao.isEnabled = true
            }
        }
    }

    /** Livros desta lista que já estão no aparelho (abrem na hora, sem rede). */
    private fun marcarJaBaixados() {
        val lista = livros.toList()
        lifecycleScope.launch {
            val naBiblioteca = BibliotecaStore.listar(applicationContext).mapNotNull { it.chaveOrigem }.toSet()
            val chaves = withContext(Dispatchers.IO) {
                // Também vale o cache de buscas anteriores à biblioteca.
                lista.filter { it.chave in naBiblioteca || LivrosClient.jaBaixado(applicationContext, it) }
                    .map { it.chave }.toSet()
            }
            adapter.definirBaixados(chaves)
        }
    }

    /**
     * Livro já está na biblioteca → usa o arquivo dela (sem rede). Senão baixa, importa (título/autor da busca
     * valem se o EPUB não tiver) e descarta a cópia do cache.
     */
    private suspend fun guardarNaBiblioteca(livro: LivroEncontrado): java.io.File {
        val ctx = applicationContext
        BibliotecaStore.porChaveOrigem(ctx, livro.chave)?.let { return BibliotecaStore.arquivoDo(ctx, it) }
        val baixado = LivrosClient.baixar(ctx, livro)
        val noAcervo = BibliotecaStore.importarArquivo(
            ctx, baixado, baixado.name, origemDaChave(livro.chave), livro.chave,
            tituloPreferido = livro.titulo, autorPreferido = livro.autor
        )
        withContext(Dispatchers.IO) { baixado.parentFile?.deleteRecursively() }
        return BibliotecaStore.arquivoDo(ctx, noAcervo)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun baixar(livro: LivroEncontrado) {
        if (baixando != null) return
        baixando = livro.chave
        adapter.marcarBaixando(livro.chave)
        lifecycleScope.launch {
            try {
                val arquivo = guardarNaBiblioteca(livro)
                val uri = FileProvider.getUriForFile(
                    this@BuscarLivrosActivity, "$packageName.fileprovider", arquivo
                )
                setResult(RESULT_OK, Intent().setData(uri).putExtra(EXTRA_TITULO, livro.titulo))
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@BuscarLivrosActivity,
                    getString(R.string.livros_erro_download, e.message ?: ""), Toast.LENGTH_LONG
                ).show()
                baixando = null
                adapter.marcarBaixando(null)
            }
        }
    }

    companion object {
        const val EXTRA_TITULO = "titulo_livro"
        private const val MAX_PARA_BAIXAR_TODOS = 200
    }
}

private class LivrosAdapter(
    private val scope: CoroutineScope,
    private val onBaixar: (LivroEncontrado) -> Unit
) :
    ListAdapter<LivroEncontrado, LivrosAdapter.VH>(DIFF) {

    private var emDownload: String? = null
    private var baixados: Set<String> = emptySet()

    fun definirBaixados(chaves: Set<String>) {
        baixados = chaves
        notifyItemRangeChanged(0, itemCount)
    }

    fun marcarBaixando(id: String?) {
        emDownload = id
        notifyItemRangeChanged(0, itemCount)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val titulo: TextView = view.findViewById(R.id.tvLivroTitulo)
        val detalhe: TextView = view.findViewById(R.id.tvLivroDetalhe)
        val barra: ProgressBar = view.findViewById(R.id.pbLivro)
        val botao: Button = view.findViewById(R.id.btnLivroBaixar)
        val capa: ImageView = view.findViewById(R.id.ivLivroCapa)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_livro, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val livro = getItem(position)
        holder.titulo.text = livro.titulo
        val jaBaixado = livro.chave in baixados
        val ctx = holder.itemView.context
        holder.detalhe.text = listOfNotNull(
            livro.autor.ifBlank { null }, livro.idioma, livro.ano,
            if (jaBaixado) ctx.getString(R.string.livros_ja_baixado) else null
        ).joinToString(" · ")
        holder.botao.setText(if (jaBaixado) R.string.livros_usar else R.string.livros_baixar_usar)
        carregarCapa(holder, livro)
        val esteBaixando = emDownload == livro.chave
        holder.barra.visibility = if (esteBaixando) View.VISIBLE else View.GONE
        holder.botao.isEnabled = emDownload == null
        holder.botao.setOnClickListener { onBaixar(livro) }
    }

    /** A marca na view evita pôr a capa de outro livro quando a linha é reaproveitada na rolagem. */
    private fun carregarCapa(holder: VH, livro: LivroEncontrado) {
        holder.capa.setImageDrawable(null)
        holder.capa.tag = livro.chave
        val url = livro.capaUrl ?: return
        CapaLoader.emCache(url)?.let { holder.capa.setImageBitmap(it); return }
        scope.launch {
            val imagem = CapaLoader.carregar(url) ?: return@launch
            if (holder.capa.tag == livro.chave) holder.capa.setImageBitmap(imagem)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<LivroEncontrado>() {
            override fun areItemsTheSame(a: LivroEncontrado, b: LivroEncontrado) = a.chave == b.chave
            override fun areContentsTheSame(a: LivroEncontrado, b: LivroEncontrado) = a == b
        }
    }
}
