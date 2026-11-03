package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.PreRenderBridge
import com.jonjonesbr.audiobookgen.service.PreRenderState
import java.io.File

/** Como a guiada começa ao escolher "Preparar e ouvir" (rb-25). */
internal enum class InicioDaEscuta { JA_TOCANDO, RETOMAR, TOCAR }

/**
 * Decide como a guiada começa ao escolher "Preparar e ouvir": tocando (ou carregando) não reinicia o parágrafo;
 * pausada retoma no meio dele; parada, ou sem leitura, toca do início da faixa.
 */
internal fun inicioDaEscuta(
    indiceGuiada: Int,
    pausada: Boolean,
    tocando: Boolean,
    carregando: Boolean,
): InicioDaEscuta = when {
    indiceGuiada >= 0 && pausada -> InicioDaEscuta.RETOMAR
    indiceGuiada >= 0 && (tocando || carregando) -> InicioDaEscuta.JA_TOCANDO
    else -> InicioDaEscuta.TOCAR
}

/** Preparo em curso: não terminou (nem concluído, cancelado ou com erro). Função pura, para o teste (rb-33). */
internal fun preparoEmCurso(estado: PreRenderState?): PreRenderState? = estado?.takeIf { !it.terminou }

/**
 * Texto do item "Pré-carregar" (⋮ e diálogo da guiada). Com preparo em curso vira
 * "Cancelar pré-carregamento (N de M)", que cancela pelo mesmo caminho da notificação (rb-33).
 * Sem preparo, devolve [normal].
 */
internal fun rotuloDoPreparo(context: Context, normal: String): String {
    val emCurso = preparoEmCurso(PreRenderBridge.state.value) ?: return normal
    return context.getString(R.string.prerender_cancel_action, emCurso.feitos, emCurso.total)
}

/**
 * "Pré-carregar capítulo" do ⋮ do leitor e do player (rb-25): confirmação com "Só preparar" e "Preparar e ouvir".
 * Extraído da [ReaderActivity] no mesmo padrão dos controllers: activity, viewModel e callbacks no construtor, sem
 * ler campos da Activity. A Activity só religa os callbacks.
 *
 * "Preparar e ouvir" liga a guiada antes do preparo, para o preparo já correr à frente dela (ver
 * [GuidedPlayerManager.prepararCapitulo]). A regra de quem começa tocando fica em [inicioDaEscuta].
 */
class ReaderPreparoController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val obterCaminhoArquivo: () -> String,
        val obterGuidedPlayerManager: () -> GuidedPlayerManager?,
        val obterParagrafoAtual: () -> Int?,
        /** Começa a guiada no parágrafo pela barra (playGuidedFrom: fecha o audiobook e volta a acompanhar). */
        val tocarGuiadaDesde: (Int) -> Unit,
    )

    private val guidedPlayerManager: GuidedPlayerManager? get() = callbacks.obterGuidedPlayerManager()

    private val dialogos = DialogosAbertos(activity)
    private fun AlertDialog.Builder.exibir(): AlertDialog = dialogos.mostrar(this)

    /**
     * Abre o pré-carregamento do capítulo atual. Com um preparo em curso, o mesmo item cancela (rb-33): é o
     * [PreRenderBridge.cancelar] que a notificação também usa. Sem guiada, não faz nada.
     */
    fun abrir() {
        val cancelar = PreRenderBridge.cancelar
        if (cancelar != null && preparoEmCurso(PreRenderBridge.state.value) != null) {
            cancelar()
            // Sem isso, quem toca não vê nada: o diálogo fecha e a notificação some (inclusive para o TalkBack).
            Toast.makeText(activity, R.string.prerender_cancelled, Toast.LENGTH_SHORT).show()
            return
        }
        val manager = guidedPlayerManager ?: return
        val texto = viewModel.textoEfetivo()
        if (texto != null && texto.paragrafos.isNotEmpty()) abrirConfirmacao(manager, texto)
    }

    /** Faixa do preparo a partir do ponto de leitura, com o título e as unidades; pede confirmação. */
    private fun abrirConfirmacao(manager: GuidedPlayerManager, texto: TextoExtraido) {
        // Do trecho em leitura (ou visível) até o fim do capítulo dele; antes partia sempre do início do livro.
        val emLeitura = manager.state.value.index.takeIf { it >= 0 }
        val (inicio, fim) = faixaDoPreparo(
            texto.paragrafos,
            texto.capitulos,
            emLeitura ?: callbacks.obterParagrafoAtual() ?: 0,
        )
        val capitulo = texto.capitulos.getOrNull(texto.paragrafos[inicio].indiceCapitulo)
        val titulo = capitulo?.titulo ?: File(callbacks.obterCaminhoArquivo()).nameWithoutExtension
        val unidades = manager.contarUnidadesParaPreparo(inicio, fim)
        if (unidades == 0) {
            Toast.makeText(activity, R.string.prerender_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        var mensagem = activity.getString(R.string.prerender_confirm_message, unidades, titulo)
        if (manager.preparoEsperaALeitura()) {
            mensagem += "\n\n" + activity.getString(R.string.prerender_confirm_lento)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.prerender_confirm_title)
            .setMessage(mensagem)
            .setPositiveButton(R.string.prerender_confirm_listen) { _, _ ->
                comecar(manager, inicio, fim, titulo, ouvir = true)
            }
            .setNeutralButton(R.string.prerender_confirm_start) { _, _ ->
                comecar(manager, inicio, fim, titulo, ouvir = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /**
     * Com [ouvir], liga a guiada antes do preparo: a ordem importa, porque o play() deixa o índice e o "carregando"
     * certos antes da primeira volta do preparo, que então cede a vez e já pula a janela da leitura.
     */
    private fun comecar(manager: GuidedPlayerManager, inicio: Int, fim: Int, titulo: String, ouvir: Boolean) {
        if (ouvir) {
            val estado = manager.state.value
            when (inicioDaEscuta(estado.index, manager.estaPausado, estado.isPlaying, estado.isLoading)) {
                InicioDaEscuta.TOCAR -> callbacks.tocarGuiadaDesde(inicio)
                InicioDaEscuta.RETOMAR -> manager.resume()
                InicioDaEscuta.JA_TOCANDO -> Unit
            }
        }
        manager.prepararCapitulo(inicio, fim, titulo)
        Toast.makeText(activity, R.string.prerender_started, Toast.LENGTH_SHORT).show()
    }
}
