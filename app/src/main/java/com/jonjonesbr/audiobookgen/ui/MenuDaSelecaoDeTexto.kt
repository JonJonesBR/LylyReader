package com.jonjonesbr.audiobookgen.ui

import android.annotation.SuppressLint
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R

// IDs dos itens de menu no ActionMode de seleção de texto — nomeados (1001/1002 eram débito aceito no baseline
// do detekt; 1003 a 1005 são os itens de antes, 1006 é o Gerar áudio do rb-30).
private const val MENU_ITEM_EXCLUIR_OCORRENCIAS = 1001
private const val MENU_ITEM_SELECIONAR_PARAGRAFO = 1002
private const val MENU_ITEM_DESTACAR = 1003
private const val MENU_ITEM_DEFINIR = 1004
private const val MENU_ITEM_PRONUNCIA = 1005
private const val MENU_ITEM_GERAR_AUDIO = 1006

/** Ações do menu da seleção de texto do Leitor. Só lambdas: o ParagrafoAdapter liga cada uma ao que ela faz. */
internal data class AcoesDaSelecaoDeTexto(
    val gerarAudio: (String) -> Unit,
    val selecionarVarios: (Int) -> Unit,
    val destacar: (Int, Int, Int) -> Unit,
    val pronuncia: (String) -> Unit,
    val definir: (String) -> Unit,
    val excluirOcorrencias: (String) -> Unit,
    // Recebem o menu que abriu ou fechou: o adapter sabe qual seleção está aberta (rb-38).
    val aoAbrir: (MenuDaSelecaoDeTexto) -> Unit,
    val aoFechar: (MenuDaSelecaoDeTexto) -> Unit,
)

/**
 * Menu da seleção nativa de um parágrafo (rb-30): a porta única de entrada para gerar áudio. "Gerar áudio" vem
 * primeiro e leva o trecho marcado dentro do parágrafo; "Selecionar vários parágrafos" entra no modo de parágrafos.
 * "Compartilhar" e "Copiar" são os do sistema, que vêm depois destes itens (ordem 0).
 */
internal class MenuDaSelecaoDeTexto(
    private val tv: TextView,
    private val posicao: () -> Int,
    private val comAudio: Boolean,
    private val acoes: AcoesDaSelecaoDeTexto,
) : ActionMode.Callback {

    /** Modo aberto agora. A barra do Leitor (rb-38) executa a ação por ele, como faria o item do menu. */
    private var modoAberto: ActionMode? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        modoAberto = mode
        acoes.aoAbrir(this)
        if (comAudio) {
            menu.add(0, MENU_ITEM_GERAR_AUDIO, 0, R.string.selecao_gerar_audio)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, MENU_ITEM_SELECIONAR_PARAGRAFO, 0, R.string.menu_selecionar_varios_paragrafos)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add(0, MENU_ITEM_DESTACAR, 0, R.string.menu_destacar_trecho)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (comAudio) {
            menu.add(0, MENU_ITEM_PRONUNCIA, 0, R.string.menu_pronuncia)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        // No ⋮. Apagar frase é destrutiva, por isso fica por último.
        menu.add(0, MENU_ITEM_DEFINIR, 0, R.string.menu_definir_palavra)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_ITEM_EXCLUIR_OCORRENCIAS, 0, R.string.btn_excluir_ocorrencias)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = executarItem(item.itemId, mode)

    /** Executa a ação da barra do Leitor, como se o item equivalente do menu tivesse sido tocado (rb-38). */
    fun executarPelaBarra(acao: AcaoDaBarraDeSelecao): Boolean = executarItem(idDoItem(acao), modoAberto)

    override fun onDestroyActionMode(mode: ActionMode) {
        modoAberto = null
        acoes.aoFechar(this)
    }

    private fun executarItem(id: Int, mode: ActionMode?): Boolean {
        // A seleção é lida antes de fechar o menu: ao fechar, o Editor a desfaz.
        val sel = trechoSelecionado(tv.selectionStart, tv.selectionEnd, tv.text)
        val acao = sel?.let { executar(id, it, posicao()) } ?: return false
        mode?.finish()
        acao()
        return true
    }

    private fun idDoItem(acao: AcaoDaBarraDeSelecao): Int = when (acao) {
        AcaoDaBarraDeSelecao.GERAR_AUDIO -> MENU_ITEM_GERAR_AUDIO
        AcaoDaBarraDeSelecao.DESTACAR -> MENU_ITEM_DESTACAR
        AcaoDaBarraDeSelecao.PRONUNCIA -> MENU_ITEM_PRONUNCIA
        AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS -> MENU_ITEM_SELECIONAR_PARAGRAFO
        AcaoDaBarraDeSelecao.DEFINIR -> MENU_ITEM_DEFINIR
    }

    /** A ação do item tocado, ou nulo quando ela não se aplica ao trecho (sem texto ou sem parágrafo). */
    private fun executar(id: Int, sel: TrechoSelecionado, pos: Int): (() -> Unit)? {
        val texto = sel.texto
        val comTexto = texto.isNotEmpty()
        val comParagrafo = pos != RecyclerView.NO_POSITION
        return when (id) {
            MENU_ITEM_GERAR_AUDIO -> if (comTexto) ({ acoes.gerarAudio(texto) }) else null
            MENU_ITEM_SELECIONAR_PARAGRAFO -> if (comParagrafo) ({ acoes.selecionarVarios(pos) }) else null
            MENU_ITEM_DESTACAR -> if (comParagrafo) ({ acoes.destacar(pos, sel.inicio, sel.fim) }) else null
            MENU_ITEM_PRONUNCIA -> if (comTexto) ({ acoes.pronuncia(texto) }) else null
            MENU_ITEM_DEFINIR -> if (comTexto) ({ acoes.definir(texto) }) else null
            MENU_ITEM_EXCLUIR_OCORRENCIAS -> if (comTexto) ({ acoes.excluirOcorrencias(texto) }) else null
            else -> null
        }
    }
}

/**
 * Avisa o início (ACTION_DOWN) e o fim (ACTION_UP) de cada toque, e devolve false para o Editor seguir como antes:
 * o clique continua no performClick. A duração do toque separa o toque curto do toque longo que falhou (rb-38).
 */
// Só observa DOWN e UP e devolve false: o clique continua no performClick.
@SuppressLint("ClickableViewAccessibility")
internal fun TextView.observarToque(aoDescer: () -> Unit, aoSoltar: (duracaoMs: Long) -> Unit) {
    setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> aoDescer()
            MotionEvent.ACTION_UP -> aoSoltar(ev.eventTime - ev.downTime)
        }
        false
    }
}

/**
 * Rearma a seleção de um TextView já anexado à janela. Quando o RecyclerView liga a view reciclada, ela ainda não
 * está na janela: o Editor então não monta os controles de seleção (prepareCursorControllers) e o toque longo não
 * seleciona nada. Alternar isEnabled com a view já anexada refaz esses controles, sem refazer o layout do texto.
 * Com foco não mexe, para não tirar a seleção que a pessoa está usando.
 */
internal fun TextView.rearmarSelecao() {
    if (!isTextSelectable || hasFocus()) return
    isEnabled = false
    isEnabled = true
}
