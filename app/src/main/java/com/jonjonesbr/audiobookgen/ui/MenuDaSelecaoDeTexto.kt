package com.jonjonesbr.audiobookgen.ui

import android.annotation.SuppressLint
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import com.jonjonesbr.audiobookgen.R
import kotlin.math.hypot

// IDs dos itens de menu no ActionMode de seleção de texto, nomeados. Os de ação da barra do Leitor (rb-38) não
// aparecem no menu do sistema (rb-49), mas o executarPelaBarra os chama pelo id, por isso as constantes ficam.
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
    // Chamado quando a seleção fica parada entre dois preparos: é o preparo que vem ao soltar a alça (rb-50).
    val aoSoltarAlca: (MenuDaSelecaoDeTexto) -> Unit = {},
)

/**
 * Menu da seleção nativa de um parágrafo (rb-30). Os itens de ação ficam na barra própria do Leitor (rb-38),
 * que abre junto com este menu (com áudio: Gerar áudio, Destacar, Pronúncia, Definir e Vários parágrafos; no Só ler:
 * Destacar e Definir). Aqui o ⋮ só ganha o que a barra não tem: Apagar frase (rb-49, rb-61).
 * Copiar, Compartilhar, Traduzir e Selecionar tudo são os do sistema, e vêm depois destes itens.
 */
internal class MenuDaSelecaoDeTexto(
    private val tv: TextView,
    private val posicao: () -> Int,
    private val acoes: AcoesDaSelecaoDeTexto,
) : ActionMode.Callback {

    /** Modo aberto agora. A barra do Leitor (rb-38) executa a ação por ele, como faria o item do menu. */
    private var modoAberto: ActionMode? = null

    /** Se o menu do sistema está aberto agora. Sem ele, a seleção do TextView não tem quem a feche (rb-54). */
    val aberto: Boolean get() = modoAberto != null

    /** O modo aberto agora, ou nulo. O adapter o usa para pedir o recálculo da barra (rb-50). */
    val modo: ActionMode? get() = modoAberto

    // Início e fim da seleção no preparo anterior, para saber se ela ficou parada (rb-50). -1 = sem preparo ainda.
    private var inicioNoUltimoPreparo = -1
    private var fimNoUltimoPreparo = -1

    // Início e fim da seleção quando a descida foi aplicada por último (rb-50). O ParagrafoAdapter os grava no depois
    // da descida: o preparo só refaz a descida se a seleção mudou desde então. -1 = nenhuma descida ainda.
    internal var inicioDaUltimaDescida = -1
    internal var fimDaUltimaDescida = -1

    // Seleção agora, lida do TextView, para gravar a última descida.
    internal val inicioDaSelecao: Int get() = tv.selectionStart
    internal val fimDaSelecao: Int get() = tv.selectionEnd

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        modoAberto = mode
        inicioNoUltimoPreparo = -1
        fimNoUltimoPreparo = -1
        inicioDaUltimaDescida = -1
        fimDaUltimaDescida = -1
        acoes.aoAbrir(this)
        // No ⋮. Apagar frase é destrutiva, por isso fica por último.
        menu.add(0, MENU_ITEM_EXCLUIR_OCORRENCIAS, 0, R.string.btn_excluir_ocorrencias)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    // rb-50: o preparo com a seleção igual à do preparo anterior vem ao soltar a alça (o UP refaz o preparo). Um passo
    // de arraste muda a seleção e não entra. Uma rolagem que começa sobre a seleção também repete a seleção, mas ela
    // já é a da última descida, e não refaz nada. Por isso o aviso é o único efeito: a descida é agendada por post,
    // nunca aqui dentro. Devolve false, como antes.
    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val inicio = tv.selectionStart
        val fim = tv.selectionEnd
        val refazer = refazerDescidaNoPreparo(
            inicioNoUltimoPreparo,
            fimNoUltimoPreparo,
            inicio,
            fim,
            // -1 = nenhuma descida ainda (rb-50); a faixa é a seleção que a descida usou.
            if (inicioDaUltimaDescida < 0) null else inicioDaUltimaDescida..fimDaUltimaDescida,
        )
        inicioNoUltimoPreparo = inicio
        fimNoUltimoPreparo = fim
        if (refazer) acoes.aoSoltarAlca(this)
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = executarItem(item.itemId, mode)

    /** Executa a ação da barra do Leitor, como se o item equivalente do menu tivesse sido tocado (rb-38). */
    fun executarPelaBarra(acao: AcaoDaBarraDeSelecao): Boolean = executarItem(idDoItem(acao), modoAberto)

    /**
     * Linha do início da seleção na tela, em px (rb-48): a barra desce a lista se essa linha ficar embaixo dela. Só
     * lê. Nulo sem seleção no TextView, sem layout ou fora da janela. getLocationOnScreen já desconta a rolagem da
     * página horizontal, e a translação da lista entra na conta.
     */
    fun linhaDoInicioDaSelecaoNaTela(): LinhaNaTela? {
        val layout = tv.layout
        if (!tv.hasSelection() || !tv.isAttachedToWindow || layout == null) return null
        val linha = layout.getLineForOffset(minOf(tv.selectionStart, tv.selectionEnd))
        val posTela = IntArray(2)
        tv.getLocationOnScreen(posTela)
        val base = posTela[1] + tv.totalPaddingTop - tv.scrollY
        return LinhaNaTela(base + layout.getLineTop(linha), base + layout.getLineBottom(linha))
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        modoAberto = null
        acoes.aoFechar(this)
    }

    private fun executarItem(id: Int, mode: ActionMode?): Boolean {
        // A seleção é lida antes de fechar o menu: ao fechar, o Editor a desfaz.
        val sel = trechoSelecionado(tv.selectionStart, tv.selectionEnd, tv.text)
        val acao = sel?.let { executar(id, it, posicao()) } ?: return false
        mode?.finish()
        // Com o modo, o Editor já desfez a seleção ao fechar. Sem o modo (barra própria, rb-54), é aqui que ela sai.
        tv.desfazerSelecao()
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
        val acao = AcaoDaBarraDeSelecao.entries.firstOrNull { idDoItem(it) == id }
        return when {
            id == MENU_ITEM_EXCLUIR_OCORRENCIAS ->
                sel.texto.takeIf { it.isNotEmpty() }?.let { texto -> ({ acoes.excluirOcorrencias(texto) }) }
            acao != null -> acoes.acaoDaBarra(acao, sel, pos)
            else -> null
        }
    }
}

/**
 * Ação da barra sobre o trecho marcado no parágrafo [pos] (rb-58). A mesma para a seleção com o menu do sistema e para
 * o trecho guardado, que pode estar com o parágrafo fora da tela. Nulo quando a ação não se aplica ao trecho (sem
 * texto, ou sem parágrafo). Função de topo pelo mesmo motivo das outras: não empurrar o menu acima do limite do detekt.
 */
internal fun AcoesDaSelecaoDeTexto.acaoDaBarra(
    acao: AcaoDaBarraDeSelecao,
    trecho: TrechoSelecionado,
    pos: Int,
): (() -> Unit)? {
    val texto = trecho.texto
    val comTexto = texto.isNotEmpty()
    val comParagrafo = pos >= 0
    return when (acao) {
        AcaoDaBarraDeSelecao.GERAR_AUDIO -> if (comTexto) ({ gerarAudio(texto) }) else null
        AcaoDaBarraDeSelecao.DESTACAR -> if (comParagrafo) ({ destacar(pos, trecho.inicio, trecho.fim) }) else null
        AcaoDaBarraDeSelecao.PRONUNCIA -> if (comTexto) ({ pronuncia(texto) }) else null
        AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS -> if (comParagrafo) ({ selecionarVarios(pos) }) else null
        AcaoDaBarraDeSelecao.DEFINIR -> if (comTexto) ({ definir(texto) }) else null
    }
}

/**
 * Desfaz a seleção do TextView, com o cursor onde ela terminava: o mesmo que o Editor faz ao fechar o modo (rb-54).
 * Função de topo pelo mesmo motivo das outras: não empurrar a classe do menu acima do limite de funções do detekt.
 */
internal fun TextView.desfazerSelecao() {
    val texto = text as? Spannable ?: return
    Selection.setSelection(texto, selectionEnd.coerceIn(0, texto.length))
}

/**
 * Desfaz a seleção que ficou sem dono: sem foco, sem menu aberto (rb-54). Devolve se desfez. Com o menu aberto, a
 * seleção é do menu, e ele mesmo a fecha.
 */
internal fun TextView.desfazerSelecaoSemMenu(): Boolean {
    if (!hasSelection() || (customSelectionActionModeCallback as? MenuDaSelecaoDeTexto)?.aberto == true) return false
    desfazerSelecao()
    return true
}

/**
 * Toque curto e clique de um parágrafo (rb-42). Quem decide é o toque: o primeiro toque curto lê dali, mesmo sem o
 * foco. No Android, um TextView focável que ainda não tem foco só pega o foco no UP (requestFocus) e não roda o
 * performClick. Por isso o clique não decide nada sozinho: ele só vale quando não veio de um toque recente
 * (TalkBack, Switch, Enter ou D-pad). O toque é postado: o UP ainda termina dentro do TextView (foco, Editor, menu
 * que fecha), e a lista não pode mudar no meio do despacho do toque. O listener devolve false: o Editor e o
 * toque longo seguem como antes.
 */
internal class ToqueNoParagrafo(
    private val tv: TextView,
    private val selecaoAberta: () -> Boolean,
    private val aoToque: () -> Unit,
) : View.OnTouchListener, View.OnClickListener {

    private val slop = ViewConfiguration.get(tv.context).scaledTouchSlop.toFloat()
    private var x0 = 0f
    private var y0 = 0f
    // Maior afastamento do ponto do DOWN durante o gesto: acima do slop, é arraste e não toque.
    private var deslocamento = 0f
    // Falso depois de um segundo dedo ou de um CANCEL: o gesto não vale como toque.
    private var gestoValido = false
    // Havia seleção aberta no DOWN: o toque só fecha a seleção e não lê.
    private var selecaoNoDown = false
    // Verdadeiro do UP até o post que o desarma: o performClick desse toque cai nessa janela e é ignorado.
    // A janela vale pela ordem da fila, e não pelo relógio: um quadro atrasado não a deixa vencer (rb-42).
    private var cliqueDoToquePendente = false

    // Devolve false: o clique de acessibilidade é o onClick, e o Editor vê o toque como antes.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestoValido = true
                x0 = ev.x
                y0 = ev.y
                deslocamento = 0f
                selecaoNoDown = selecaoAberta() || tv.hasSelection()
            }
            MotionEvent.ACTION_MOVE -> deslocamento = maxOf(deslocamento, afastamento(ev))
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> gestoValido = false
            MotionEvent.ACTION_UP -> soltou(ev)
        }
        return false
    }

    /** Clique do performClick (toque) ou de acessibilidade. Se o UP acabou de decidir o toque, o clique é ignorado. */
    override fun onClick(v: View) {
        if (cliqueDoToquePendente) {
            cliqueDoToquePendente = false
        } else {
            aoToque()
        }
    }

    // hypot do kotlin.math só recebe Double: a distância do ponto do DOWN volta em Float.
    private fun afastamento(ev: MotionEvent): Float =
        hypot((ev.x - x0).toDouble(), (ev.y - y0).toDouble()).toFloat()

    private fun soltou(ev: MotionEvent) {
        deslocamento = maxOf(deslocamento, afastamento(ev))
        // hasSelection no UP pega o duplo toque rápido: o Editor já selecionou a palavra no 2º DOWN.
        // Esse UP não pode ler de novo.
        val selecao = selecaoNoDown || tv.hasSelection()
        val curto = gestoValido && ehToqueCurto(
            duracaoMs = ev.eventTime - ev.downTime,
            deslocamentoPx = deslocamento,
            slopPx = slop,
            limiteDoToqueLongoMs = ViewConfiguration.getLongPressTimeout().toLong(),
            selecaoAberta = selecao,
        )
        if (curto) gestoValido = false
        // Todo UP arma a janela. O performClick deste toque, se houver, entra na fila antes do post de dentro, então
        // o desarme só roda depois dele. O toque curto é postado: o isAttachedToWindow barra a view reciclada.
        cliqueDoToquePendente = true
        tv.post {
            if (curto && tv.isAttachedToWindow) aoToque()
            tv.post { cliqueDoToquePendente = false }
        }
    }
}

/** Liga o toque curto e o clique de um parágrafo (rb-42). Uma vez por item, no init do ViewHolder. */
internal fun TextView.ligarToqueNoParagrafo(selecaoAberta: () -> Boolean, aoToque: () -> Unit) {
    val toque = ToqueNoParagrafo(this, selecaoAberta, aoToque)
    setOnTouchListener(toque)
    setOnClickListener(toque)
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
