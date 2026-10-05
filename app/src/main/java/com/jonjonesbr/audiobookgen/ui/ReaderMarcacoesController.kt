package com.jonjonesbr.audiobookgen.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.BookmarkStore
import com.jonjonesbr.audiobookgen.data.HighlightStore

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

    fun mostrarDialogListarBookmarks() {
        val bookmarks = BookmarkStore.listarPara(activity, callbacks.obterCaminhoArquivo())
        if (bookmarks.isEmpty()) {
            Toast.makeText(activity, R.string.bookmark_sem_itens, Toast.LENGTH_SHORT).show()
            return
        }

        val nomes = bookmarks.map { it.nome }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.bookmark_listar)
            .setSingleChoiceItems(nomes, -1) { _, which ->
                val bm = bookmarks[which]
                callbacks.rolarParaParagrafo(bm.indiceParagrafo)
            }
            .setPositiveButton(R.string.btn_remover_marcador) { dialog, _ ->
                (dialog as? androidx.appcompat.app.AlertDialog)?.listView?.let { lv ->
                    val checkedItem = lv.checkedItemPosition
                    if (checkedItem >= 0 && checkedItem < bookmarks.size) {
                        BookmarkStore.remover(activity, bookmarks[checkedItem].id)
                        Toast.makeText(activity, R.string.toast_bookmark_removido, Toast.LENGTH_SHORT).show()
                        atualizarIconeBookmark(callbacks.obterParagrafoAtual())
                        mostrarDialogListarBookmarks()
                    }
                }
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
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
            .show()
    }

    fun mostrarDialogListarDestaques() {
        val destaques = HighlightStore.listarPara(activity, callbacks.obterCaminhoArquivo())
        if (destaques.isEmpty()) {
            Toast.makeText(activity, R.string.destaque_sem_itens, Toast.LENGTH_SHORT).show()
            return
        }
        val texto = viewModel.textoEfetivo()
        val rotulos = destaques.map { d ->
            val trecho = texto?.paragrafos?.getOrNull(d.indiceParagrafo)?.texto
                ?.substring(d.inicio.coerceAtMost(d.fim), d.fim)
                ?.take(BOOKMARK_NOME_MAX)
                ?: "…"
            if (d.nota.isNotBlank()) "${d.nota} — “$trecho”" else "“$trecho”"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.destaque_listar)
            .setSingleChoiceItems(rotulos, -1) { _, which ->
                callbacks.rolarParaParagrafo(destaques[which].indiceParagrafo)
            }
            .setPositiveButton(R.string.btn_remover_marcador) { dialog, _ ->
                (dialog as? androidx.appcompat.app.AlertDialog)?.listView?.let { lv ->
                    val checkedItem = lv.checkedItemPosition
                    if (checkedItem >= 0 && checkedItem < destaques.size) {
                        HighlightStore.remover(activity, destaques[checkedItem].id)
                        carregarDestaques()
                        Toast.makeText(activity, R.string.toast_destaque_removido, Toast.LENGTH_SHORT).show()
                        mostrarDialogListarDestaques()
                    }
                }
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }
}
