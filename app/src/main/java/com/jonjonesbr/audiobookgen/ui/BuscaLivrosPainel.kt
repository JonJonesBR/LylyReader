package com.jonjonesbr.audiobookgen.ui

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.LivrosClient
import com.jonjonesbr.audiobookgen.domain.FonteLivros
import com.jonjonesbr.audiobookgen.domain.IdiomaBusca
import com.jonjonesbr.audiobookgen.domain.LivroBiblioteca
import com.jonjonesbr.audiobookgen.domain.LivroEncontrado
import com.jonjonesbr.audiobookgen.domain.origemDaChave
import com.jonjonesbr.audiobookgen.service.DownloadCentral
import com.jonjonesbr.audiobookgen.util.mensagemDeErro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Busca de livros em domínio público / licença livre (Projeto Gutenberg, Wikisource e Internet Archive), sobre o
 * layout `view_busca_livros`. Usada na tela "Livros grátis" aberta pela Biblioteca ([BuscarLivrosActivity], que
 * devolve o livro e fecha) e na aba "Livros grátis" de Downloads (que guarda na Biblioteca e continua na tela).
 * Fontes piratas ficam de fora por decisão do projeto.
 *
 * @param aoGuardar chamado com o livro já na Biblioteca depois de "Baixar".
 * @param aoVerDownloads ação "Ver" do aviso de "Baixar todos"; null quando a fila já está na própria tela.
 */
class BuscaLivrosPainel(
    private val activity: AppCompatActivity,
    private val raiz: View,
    private val aoGuardar: (LivroEncontrado, LivroBiblioteca) -> Unit,
    private val aoVerDownloads: (() -> Unit)? = null
) {
    private val etBusca: EditText = raiz.findViewById(R.id.etBuscaLivro)
    private val rgIdioma: ChipGroup = raiz.findViewById(R.id.rgIdiomaLivro)
    private val chipFonte: Chip = raiz.findViewById(R.id.chipFonteLivro)
    private val tvStatus: TextView = raiz.findViewById(R.id.tvStatusLivros)
    private val pbBusca: LinearProgressIndicator = raiz.findViewById(R.id.pbBuscaLivros)
    private val adapter = LivrosAdapter(activity.lifecycleScope, onBaixar = ::baixar)

    // O LayoutManager vem antes de inflar o rodapé com a lista como pai (sem ele a RecyclerView lança exceção).
    private val recycler: RecyclerView = raiz.findViewById<RecyclerView>(R.id.recyclerLivros).apply {
        layoutManager = LinearLayoutManager(activity)
    }

    /** Fim da lista: rola junto com os livros, para eles ficarem com a tela quase toda. */
    private val rodape: View =
        LayoutInflater.from(activity).inflate(R.layout.view_busca_livros_rodape, recycler, false)
    private val btnMais: Button = rodape.findViewById(R.id.btnMaisLivros)
    private val btnBaixarTodos: Button = rodape.findViewById(R.id.btnBaixarTodosLivros)
    private var fonteAtual = FonteLivros.GUTENBERG

    private val livros = mutableListOf<LivroEncontrado>()
    private var proximoInicio: Int? = null
    private var buscaAtual: Job? = null
    private var baixando: String? = null
    private var jaBuscou = false

    /** Limite de "Baixar todos" (a tela da Biblioteca reduz em build de depuração). */
    var limiteBaixar = MAX_PARA_BAIXAR_TODOS

    init {
        recycler.adapter = ConcatAdapter(adapter, CabecalhoFixoAdapter(rodape))
        recycler.itemAnimator = null
        raiz.findViewById<Button>(R.id.btnBuscarLivro).setOnClickListener { buscar(reiniciar = true) }
        etBusca.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_SEARCH) buscar(reiniciar = true)
            acao == EditorInfo.IME_ACTION_SEARCH
        }
        rgIdioma.setOnCheckedStateChangeListener { _, _ -> buscar(reiniciar = true) }
        chipFonte.setOnClickListener { escolherFonte() }
        chipFonte.setOnCloseIconClickListener { escolherFonte() }
        btnMais.setOnClickListener { buscar(reiniciar = false) }
        btnBaixarTodos.setOnClickListener { baixarTodosOsResultados() }
    }

    /** Primeira lista: os mais baixados no idioma escolhido. Só busca uma vez (a aba de Downloads chama ao abrir). */
    fun iniciar(idiomaInicial: IdiomaBusca? = null) {
        if (jaBuscou) return
        jaBuscou = true
        val chip = when (idiomaInicial) {
            IdiomaBusca.PT -> R.id.rbIdiomaPt
            IdiomaBusca.EN -> R.id.rbIdiomaEn
            IdiomaBusca.ES -> R.id.rbIdiomaEs
            else -> null
        }
        // Marcar o chip já dispara a busca pelo listener; sem chip para marcar, busca direto.
        if (chip != null && rgIdioma.checkedChipId != chip) rgIdioma.check(chip) else buscar(reiniciar = true)
    }

    /** Acervo: Gutenberg, Wikisource ou Internet Archive (antes três chips numa linha só deles). */
    private fun escolherFonte() {
        val fontes = listOf(FonteLivros.GUTENBERG, FonteLivros.WIKISOURCE, FonteLivros.ARCHIVE)
        val nomes = intArrayOf(
            R.string.livros_fonte_gutenberg, R.string.livros_fonte_wikisource, R.string.livros_fonte_archive
        )
        val rotulos = nomes.map { activity.getString(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.livros_acervo)
            .setSingleChoiceItems(rotulos, fontes.indexOf(fonteAtual)) { d, i ->
                d.dismiss()
                if (fontes[i] == fonteAtual) return@setSingleChoiceItems
                fonteAtual = fontes[i]
                chipFonte.setText(nomes[i])
                ajustarParaFonte()
                buscar(reiniciar = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Linha de estado (buscando, nada encontrado, erro): some quando não tem o que dizer. */
    private fun status(texto: CharSequence) {
        tvStatus.text = texto
        tvStatus.visibility = if (texto.isBlank()) View.GONE else View.VISIBLE
    }

    /** O Wikisource é um site por idioma (não há "todos") e só busca com texto digitado. */
    private fun ajustarParaFonte() {
        val wiki = fonteAtual == FonteLivros.WIKISOURCE
        raiz.findViewById<View>(R.id.rbIdiomaTodos).visibility = if (wiki) View.GONE else View.VISIBLE
        if (wiki && rgIdioma.checkedChipId == R.id.rbIdiomaTodos) rgIdioma.check(R.id.rbIdiomaPt)
        rodape.findViewById<TextView>(R.id.tvAvisoLivros).setText(
            when (fonteAtual) {
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
        jaBuscou = true
        buscaAtual?.cancel()
        pbBusca.visibility = View.GONE
        if (reiniciar) {
            livros.clear()
            proximoInicio = null
            adapter.submitList(emptyList())
            btnMais.visibility = View.GONE
        }
        val fonte = fonteAtual
        val inicio = if (reiniciar) null else proximoInicio ?: return
        if (fonte == FonteLivros.WIKISOURCE && etBusca.text.isBlank()) {
            status(activity.getString(R.string.livros_digite_busca))
            return
        }
        status(activity.getString(R.string.livros_buscando))
        pbBusca.visibility = View.VISIBLE
        buscaAtual = activity.lifecycleScope.launch {
            try {
                val r = LivrosClient.buscar(fonte, etBusca.text.toString(), idiomaEscolhido(), inicio)
                livros += r.livros
                proximoInicio = r.proximoInicio
                adapter.submitList(livros.toList())
                marcarJaBaixados()
                btnMais.visibility = if (proximoInicio != null) View.VISIBLE else View.GONE
                btnBaixarTodos.visibility = if (livros.isEmpty()) View.GONE else View.VISIBLE
                status(if (livros.isEmpty()) activity.getString(R.string.livros_nada) else "")
                pbBusca.visibility = View.GONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha na busca de livros", e)
                status(activity.getString(R.string.livros_erro_busca, activity.getString(mensagemDeErro(e))))
                pbBusca.visibility = View.GONE
            }
        }
    }

    /**
     * "Coleção completa": carrega as próximas páginas da busca atual (até [limiteBaixar] livros) e põe todos na fila
     * de downloads em segundo plano, com notificação. Ex.: buscar um autor e baixar a obra toda de uma vez.
     */
    @Suppress("TooGenericExceptionCaught")
    fun baixarTodosOsResultados() {
        btnBaixarTodos.isEnabled = false
        buscaAtual?.cancel()
        pbBusca.visibility = View.GONE
        activity.lifecycleScope.launch {
            try {
                while (proximoInicio != null && livros.size < limiteBaixar) {
                    status(activity.getString(R.string.livros_carregando_todos, livros.size))
                    val r = LivrosClient.buscar(
                        fonteAtual, etBusca.text.toString(), idiomaEscolhido(), proximoInicio
                    )
                    livros += r.livros
                    proximoInicio = r.proximoInicio
                }
                adapter.submitList(livros.toList())
                val entraram = DownloadCentral.baixarLivros(activity.applicationContext, livros.take(limiteBaixar))
                status("")
                val aviso = if (entraram > 0) {
                    activity.getString(R.string.livros_na_fila, entraram)
                } else {
                    activity.getString(R.string.livros_nada_novo)
                }
                val barra = Snackbar.make(raiz, aviso, Snackbar.LENGTH_LONG)
                aoVerDownloads?.let { ver -> barra.setAction(R.string.conv_ver) { ver() } }
                barra.show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha na busca de livros", e)
                status(activity.getString(R.string.livros_erro_busca, activity.getString(mensagemDeErro(e))))
            } finally {
                btnBaixarTodos.isEnabled = true
            }
        }
    }

    /** Livros desta lista que já estão no aparelho (abrem na hora, sem rede). */
    private fun marcarJaBaixados() {
        val lista = livros.toList()
        val ctx = activity.applicationContext
        activity.lifecycleScope.launch {
            val naBiblioteca = BibliotecaStore.listar(ctx).mapNotNull { it.chaveOrigem }.toSet()
            val chaves = withContext(Dispatchers.IO) {
                // Também vale o cache de buscas anteriores à biblioteca.
                lista.filter { it.chave in naBiblioteca || LivrosClient.jaBaixado(ctx, it) }
                    .map { it.chave }.toSet()
            }
            adapter.definirBaixados(chaves)
        }
    }

    /**
     * Livro já está na biblioteca → usa o que está lá (sem rede). Senão baixa, importa (título/autor da busca
     * valem se o EPUB não tiver) e descarta a cópia do cache.
     */
    private suspend fun guardarNaBiblioteca(livro: LivroEncontrado): LivroBiblioteca {
        val ctx = activity.applicationContext
        BibliotecaStore.porChaveOrigem(ctx, livro.chave)?.let { return it }
        val baixado = LivrosClient.baixar(ctx, livro)
        val noAcervo = BibliotecaStore.importarArquivo(
            ctx, baixado, baixado.name, origemDaChave(livro.chave), livro.chave,
            tituloPreferido = livro.titulo, autorPreferido = livro.autor
        )
        withContext(Dispatchers.IO) { baixado.parentFile?.deleteRecursively() }
        return noAcervo
    }

    @Suppress("TooGenericExceptionCaught")
    private fun baixar(livro: LivroEncontrado) {
        if (baixando != null) return
        baixando = livro.chave
        adapter.marcarBaixando(livro.chave)
        activity.lifecycleScope.launch {
            try {
                val noAcervo = guardarNaBiblioteca(livro)
                adapter.marcarBaixado(livro.chave)
                aoGuardar(livro, noAcervo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao baixar livro", e)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.livros_erro_download, activity.getString(mensagemDeErro(e))),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                baixando = null
                adapter.marcarBaixando(null)
            }
        }
    }

    private companion object {
        const val TAG = "BuscaLivrosPainel"
        const val MAX_PARA_BAIXAR_TODOS = 200
    }
}

private class LivrosAdapter(
    private val scope: CoroutineScope,
    private val onBaixar: (LivroEncontrado) -> Unit
) : ListAdapter<LivroEncontrado, LivrosAdapter.VH>(DIFF) {

    private var emDownload: String? = null
    private var baixados: Set<String> = emptySet()

    fun definirBaixados(chaves: Set<String>) {
        baixados = chaves
        notifyItemRangeChanged(0, itemCount)
    }

    fun marcarBaixado(chave: String) = definirBaixados(baixados + chave)

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
