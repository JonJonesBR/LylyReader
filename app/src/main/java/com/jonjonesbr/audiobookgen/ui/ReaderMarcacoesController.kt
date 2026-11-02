package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.BookmarkStore
import com.jonjonesbr.audiobookgen.data.HighlightStore
import com.jonjonesbr.audiobookgen.domain.MarcacaoExportavel
import com.jonjonesbr.audiobookgen.domain.NavegacaoCapitulos
import com.jonjonesbr.audiobookgen.domain.TextoExportacaoMarcacoes
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.util.abrirExpandida
import com.jonjonesbr.audiobookgen.util.progressoLivroPct
import java.io.File

// Constante movida do companion de ReaderActivity (T2.2) — usada só no bloco de marcadores.
private const val BOOKMARK_NOME_MAX = 40

/**
 * Marcadores (bookmarks) e destaques do leitor — extraído de [ReaderActivity] pra reduzir o
 * tamanho do arquivo (T2.2). Padrão do projeto: activity + viewModel + callbacks no
 * construtor, sem acessar campos da Activity.
 */
class ReaderMarcacoesController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val callbacks: Callbacks,
) {
    private val dialogos = DialogosAbertos(activity)
    private fun androidx.appcompat.app.AlertDialog.Builder.exibir(): androidx.appcompat.app.AlertDialog = dialogos.mostrar(this)

    // A folha (BottomSheetDialog) não passa pelo DialogosAbertos: ela é fechada junto com a Activity (rotação, saída),
    // para não deixar a janela vazada (WindowLeaked).
    private var folhaAberta: BottomSheetDialog? = null

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                folhaAberta?.takeIf { it.isShowing }?.dismiss()
                folhaAberta = null
            }
        })
    }

    data class Callbacks(
        val obterCaminhoArquivo: () -> String,
        val obterParagrafoAtual: () -> Int?,
        val rolarParaParagrafo: (Int) -> Unit,
        val atualizarIconeBookmarkView: (temBookmark: Boolean, descricao: String) -> Unit,
        val atualizarDestaquesAdapter: (Map<Int, List<IntRange>>) -> Unit,
    )

    // ── Bookmarks (T4.2, revisado — bugfix e simplificação pra ficar intuitivo) ─────

    /**
     * Marca/desmarca a posição atual com um toque só: sem diálogo, sem risco de duplicar
     * (verifica se o parágrafo atual já tem marcador antes de decidir adicionar ou remover).
     * O nome é sugerido automaticamente do início do parágrafo — o mesmo texto que já
     * aparece na lista de marcadores, então digitar um nome não agregava o suficiente pra
     * justificar um diálogo extra no caminho mais comum ("marcar aqui rapidinho").
     */
    fun alternarBookmarkNaPosicaoAtual() {
        val texto = viewModel.textoEfetivo() ?: return
        val indice = callbacks.obterParagrafoAtual() ?: return
        val existente = BookmarkStore.listarPara(activity, callbacks.obterCaminhoArquivo())
            .find { it.indiceParagrafo == indice }
        if (existente != null) {
            BookmarkStore.remover(activity, existente.id)
            Toast.makeText(activity, R.string.toast_bookmark_removido, Toast.LENGTH_SHORT).show()
        } else {
            val nome = texto.paragrafos.getOrNull(indice)?.texto?.take(BOOKMARK_NOME_MAX)?.trim()
                ?.ifBlank { null } ?: activity.getString(R.string.bookmark_nome_padrao, indice + 1)
            BookmarkStore.adicionar(activity, callbacks.obterCaminhoArquivo(), nome, indice)
            Toast.makeText(activity, R.string.toast_bookmark_adicionado, Toast.LENGTH_SHORT).show()
        }
        atualizarIconeBookmark(indice)
    }

    /**
     * Ícone e descrição do botão de marcador refletem se a posição ATUAL já está marcada —
     * sem isso, o usuário não tinha como saber se um toque ia adicionar ou remover.
     */
    fun atualizarIconeBookmark(indice: Int?) {
        val temBookmark = indice != null &&
            BookmarkStore.listarPara(activity, callbacks.obterCaminhoArquivo()).any { it.indiceParagrafo == indice }
        callbacks.atualizarIconeBookmarkView(
            temBookmark,
            activity.getString(
                if (temBookmark) R.string.cd_bookmark_remover else R.string.cd_bookmark_adicionar
            )
        )
    }

    // ── Destaques (T3.5) ─────────────────────────────────────────────────────

    /** Recarrega os destaques salvos do livro atual para o adapter e força repintura. */
    fun carregarDestaques() {
        callbacks.atualizarDestaquesAdapter(
            HighlightStore.listarPara(activity, callbacks.obterCaminhoArquivo())
                .groupBy({ it.indiceParagrafo }, { it.inicio until it.fim })
        )
    }

    fun mostrarDialogDestacarTrecho(indiceParagrafo: Int, inicio: Int, fim: Int) {
        val input = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.destaque_nota_hint)
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.destaque_dialog_titulo)
            .setView(input)
            .setPositiveButton(R.string.bookmark_dialog_salvar) { _, _ ->
                HighlightStore.adicionar(
                    activity, callbacks.obterCaminhoArquivo(), indiceParagrafo, inicio until fim,
                    nota = input.text.toString().trim()
                )
                carregarDestaques()
                Toast.makeText(activity, R.string.toast_destaque_adicionado, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /**
     * Uma linha da folha: o que mostrar, se é destaque (ícone próprio e nota) e o que faz ao apagar.
     * A posição vem em duas partes (capítulo e porcentagem) para a porcentagem nunca sair da linha.
     */
    private class LinhaMarcacao(
        val indiceParagrafo: Int,
        val destaque: Boolean,
        val trecho: String,
        val nota: String,
        val capitulo: String,
        val percentual: String,
        val remover: () -> Unit,
    )

    /**
     * Folha "Marcadores e destaques" (M1): título com "Exportar" à direita e duas seções, marcadores e destaques,
     * cada uma na ordem do livro. Tocar numa linha fecha a folha e vai ao trecho. A lixeira pede confirmação e
     * atualiza a própria lista. Sem nada marcado, a folha mostra a dica de como marcar.
     */
    fun mostrarDialogListarMarcacoes() {
        val folha = BottomSheetDialog(activity)
        val raiz = activity.layoutInflater.inflate(R.layout.bottom_sheet_marcacoes, null)
        folha.setContentView(raiz)
        folha.abrirExpandida()
        raiz.findViewById<View>(R.id.acaoExportarMarcacoes).setOnClickListener { exportarMarcacoes() }
        preencherFolhaMarcacoes(folha, raiz)
        folhaAberta = folha
        folha.show()
    }

    /** Monta as duas seções a partir das lojas. Chamada de novo depois de apagar, para mostrar o que sobrou. */
    private fun preencherFolhaMarcacoes(folha: BottomSheetDialog, raiz: View) {
        val caminho = callbacks.obterCaminhoArquivo()
        val texto = viewModel.textoEfetivo()
        val marcadores = BookmarkStore.listarPara(activity, caminho).sortedBy { it.indiceParagrafo }.map { bm ->
            LinhaMarcacao(
                indiceParagrafo = bm.indiceParagrafo,
                destaque = false,
                trecho = bm.nome,
                nota = "",
                capitulo = capituloDoParagrafo(texto, bm.indiceParagrafo),
                percentual = percentualDoParagrafo(texto, bm.indiceParagrafo),
                remover = {
                    BookmarkStore.remover(activity, bm.id)
                    Toast.makeText(activity, R.string.toast_bookmark_removido, Toast.LENGTH_SHORT).show()
                    atualizarIconeBookmark(callbacks.obterParagrafoAtual())
                },
            )
        }
        val destaques = HighlightStore.listarPara(activity, caminho).sortedBy { it.indiceParagrafo }.map { d ->
            LinhaMarcacao(
                indiceParagrafo = d.indiceParagrafo,
                destaque = true,
                // Limites presos ao tamanho atual do parágrafo: uma edição pode tê-lo encurtado depois do destaque.
                trecho = trechoDoDestaque(texto, d.indiceParagrafo, d.inicio, d.fim),
                nota = d.nota,
                capitulo = capituloDoParagrafo(texto, d.indiceParagrafo),
                percentual = percentualDoParagrafo(texto, d.indiceParagrafo),
                remover = {
                    HighlightStore.remover(activity, d.id)
                    carregarDestaques()
                    Toast.makeText(activity, R.string.toast_destaque_removido, Toast.LENGTH_SHORT).show()
                },
            )
        }
        val temAlgum = marcadores.isNotEmpty() || destaques.isNotEmpty()
        raiz.findViewById<View>(R.id.vazioMarcacoes).visibility = if (temAlgum) View.GONE else View.VISIBLE
        raiz.findViewById<View>(R.id.acaoExportarMarcacoes).visibility = if (temAlgum) View.VISIBLE else View.GONE
        val aoAlterar = { preencherFolhaMarcacoes(folha, raiz) }
        preencherSecaoMarcacoes(
            folha, raiz.findViewById<View>(R.id.secaoMarcadores), raiz.findViewById<LinearLayout>(R.id.listaMarcadores),
            marcadores, aoAlterar,
        )
        preencherSecaoMarcacoes(
            folha, raiz.findViewById<View>(R.id.secaoDestaques), raiz.findViewById<LinearLayout>(R.id.listaDestaques),
            destaques, aoAlterar,
        )
    }

    /** Uma seção some quando não tem linhas; senão, recria as linhas dela. */
    private fun preencherSecaoMarcacoes(
        folha: BottomSheetDialog,
        secao: View,
        lista: LinearLayout,
        linhas: List<LinhaMarcacao>,
        aoAlterar: () -> Unit,
    ) {
        secao.visibility = if (linhas.isEmpty()) View.GONE else View.VISIBLE
        lista.removeAllViews()
        linhas.forEach { linha -> lista.addView(criarLinhaMarcacao(folha, lista, linha, aoAlterar)) }
    }

    private fun criarLinhaMarcacao(
        folha: BottomSheetDialog,
        pai: LinearLayout,
        linha: LinhaMarcacao,
        aoAlterar: () -> Unit,
    ): View {
        val view = activity.layoutInflater.inflate(R.layout.item_marcacao, pai, false)
        view.findViewById<ImageView>(R.id.iconeMarcacao).setImageResource(
            if (linha.destaque) R.drawable.ic_marca_texto else R.drawable.ic_bookmark_filled
        )
        view.findViewById<TextView>(R.id.trechoMarcacao).text = linha.trecho
        view.findViewById<TextView>(R.id.notaMarcacao).apply {
            text = linha.nota
            visibility = if (linha.nota.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.capituloMarcacao).apply {
            text = linha.capitulo
            visibility = if (linha.capitulo.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.percentualMarcacao).apply {
            text = linha.percentual
            visibility = if (linha.percentual.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<View>(R.id.linhaPosicaoMarcacao).visibility =
            if (linha.capitulo.isBlank() && linha.percentual.isBlank()) View.GONE else View.VISIBLE
        // Salto exato ao topo, como antes: fecha a folha e rola para o parágrafo.
        view.setOnClickListener {
            folha.dismiss()
            callbacks.rolarParaParagrafo(linha.indiceParagrafo)
        }
        view.findViewById<View>(R.id.removerMarcacao).setOnClickListener {
            confirmarRemocaoMarcacao {
                linha.remover()
                aoAlterar()
            }
        }
        return view
    }

    /** Confirmação curta antes de apagar: marcação apagada não volta, e a lixeira fica perto do toque da linha. */
    private fun confirmarRemocaoMarcacao(aoConfirmar: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setMessage(R.string.marcacoes_remover_confirma)
            .setPositiveButton(R.string.btn_remover_marcador) { _, _ -> aoConfirmar() }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /**
     * Exporta marcadores e destaques do livro em texto simples (título, depois cada marcação com
     * trecho, nota e capítulo) e compartilha pelo sistema. Na ordem do livro, como a lista.
     */
    fun exportarMarcacoes() {
        val caminho = callbacks.obterCaminhoArquivo()
        val texto = viewModel.textoEfetivo()
        val itens = BookmarkStore.listarPara(activity, caminho).map { bm ->
            bm.indiceParagrafo to MarcacaoExportavel(
                capitulo = capituloDoParagrafo(texto, bm.indiceParagrafo),
                trecho = bm.nome,
                nota = "",
            )
        } + HighlightStore.listarPara(activity, caminho).map { d ->
            d.indiceParagrafo to MarcacaoExportavel(
                capitulo = capituloDoParagrafo(texto, d.indiceParagrafo),
                trecho = trechoDoDestaque(texto, d.indiceParagrafo, d.inicio, d.fim),
                nota = d.nota,
            )
        }
        if (itens.isEmpty()) {
            Toast.makeText(activity, R.string.marcacoes_sem_itens, Toast.LENGTH_SHORT).show()
            return
        }

        val titulo = BibliotecaStore.tituloPorCaminho(activity, caminho)
            ?: File(caminho).nameWithoutExtension
        val conteudo = TextoExportacaoMarcacoes.montar(
            titulo,
            itens.sortedBy { it.first }.map { it.second },
            activity.getString(R.string.marcacoes_exportar_rotulo_nota),
        )
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, titulo)
            putExtra(Intent.EXTRA_TEXT, conteudo)
        }
        activity.startActivity(
            Intent.createChooser(envio, activity.getString(R.string.marcacoes_exportar_chooser))
        )
    }

    /** Quanto do livro foi lido até o parágrafo ("42%"); vazio se o texto ainda não estiver carregado. */
    private fun percentualDoParagrafo(texto: TextoExtraido?, indice: Int): String {
        if (texto == null) return ""
        val porcento = (progressoLivroPct(texto.paragrafos, indice, texto.totalChars) * 100).toInt()
        return activity.getString(R.string.marcacoes_percentual, porcento)
    }

    /** Capítulo (título legível) do parágrafo; vazio se o texto ou o capítulo não for conhecido. */
    private fun capituloDoParagrafo(texto: TextoExtraido?, indice: Int): String {
        if (texto == null) return ""
        val paragrafo = texto.paragrafos.getOrNull(indice) ?: return ""
        return texto.capitulos.getOrNull(paragrafo.indiceCapitulo)
            ?.let { NavegacaoCapitulos.tituloLimpo(it.titulo) }
            .orEmpty()
    }

    /** Trecho marcado como destaque; "…" se o parágrafo não estiver mais disponível. */
    private fun trechoDoDestaque(texto: TextoExtraido?, indice: Int, inicio: Int, fim: Int): String {
        if (texto == null) return "…"
        val paragrafo = texto.paragrafos.getOrNull(indice)?.texto ?: return "…"
        val ini = inicio.coerceIn(0, paragrafo.length)
        val fimLimpo = fim.coerceIn(ini, paragrafo.length)
        return paragrafo.substring(ini, fimLimpo).trim().ifBlank { "…" }
    }
}
