package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Paragrafo
import kotlin.math.abs

// Regras puras da seleção de trecho no Leitor (rb-30): sem nada de Android, testadas na JVM.
// O menu e as views são do MenuDaSelecaoDeTexto e do ParagrafoAdapter.

/** Trecho marcado dentro de um parágrafo: offsets (fim exclusivo) e o texto, sem espaços nas pontas. */
internal data class TrechoSelecionado(val inicio: Int, val fim: Int, val texto: String)

/**
 * Trecho marcado pela seleção nativa de um TextView. Aceita a seleção invertida (início depois do fim), que o
 * Android pode devolver. Nulo sem seleção. O texto pode sair em branco: cada ação confere.
 */
internal fun trechoSelecionado(selStart: Int, selEnd: Int, texto: CharSequence): TrechoSelecionado? {
    val inicio = minOf(selStart, selEnd)
    val fim = maxOf(selStart, selEnd).coerceAtMost(texto.length)
    if (inicio < 0 || fim <= inicio) return null
    return TrechoSelecionado(inicio, fim, texto.subSequence(inicio, fim).toString().trim())
}

/**
 * Texto dos parágrafos de [inicio] a [fim] (os dois incluídos, em qualquer ordem), separados por linha em branco.
 * Nulo quando a faixa cai fora da lista ou quando todos os parágrafos da faixa estão em branco.
 */
internal fun textoDosParagrafos(paragrafos: List<Paragrafo>, inicio: Int, fim: Int): String? {
    val de = minOf(inicio, fim).coerceAtLeast(0)
    val ate = maxOf(inicio, fim).coerceAtMost(paragrafos.lastIndex)
    if (de > ate) return null
    return paragrafos.subList(de, ate + 1).joinToString("\n\n") { it.texto }.takeIf { it.isNotBlank() }
}

/** Quantos parágrafos a seleção cobre: 0 sem início; com início só, 1. Vale para a barra de seleção. */
internal fun paragrafosNaSelecao(selecaoInicio: Int?, selecaoFim: Int?): Int {
    val inicio = selecaoInicio ?: return 0
    return abs((selecaoFim ?: inicio) - inicio) + 1
}

/**
 * Se a lista deve atender o pedido "mostrar este trecho" de um parágrafo em foco (rb-55). Com o dedo (modo de
 * toque), o parágrafo tocado tem foco e não há seleção, o pedido é o setText da leitura guiada, que puxaria a lista
 * de volta ao parágrafo; então é ignorado. Com seleção, o pedido vem das alças e vale. Fora do modo de toque
 * (teclado, D-pad) o foco precisa continuar mostrando o que ele pega, e também vale.
 */
internal fun pedidoDeMostrarTrechoVale(modoDeToque: Boolean, comFoco: Boolean, comSelecao: Boolean): Boolean =
    !(modoDeToque && comFoco && !comSelecao)

// Regras da barra própria da seleção e das repinturas seguradas durante o toque (rb-38).

/** Ações da barra do Leitor que aparece enquanto há seleção nativa de texto, na ordem em que aparecem. */
internal enum class AcaoDaBarraDeSelecao { GERAR_AUDIO, DESTACAR, PRONUNCIA, VARIOS_PARAGRAFOS, DEFINIR }

/** Com áudio: as ações de geração e a pronúncia. Só ler: Destacar e Definir (sem o que toca o texto). */
internal fun acoesDaBarraDeSelecao(comAudio: Boolean): List<AcaoDaBarraDeSelecao> =
    if (comAudio) {
        listOf(AcaoDaBarraDeSelecao.GERAR_AUDIO, AcaoDaBarraDeSelecao.DESTACAR,
            AcaoDaBarraDeSelecao.PRONUNCIA, AcaoDaBarraDeSelecao.VARIOS_PARAGRAFOS)
    } else {
        listOf(AcaoDaBarraDeSelecao.DESTACAR, AcaoDaBarraDeSelecao.DEFINIR)
    }

/**
 * Ações da seleção que vão ao ⋮ do menu do sistema por não estarem na barra (rb-49). Com áudio, a barra não tem
 * Definir: ele fica no menu. Sem áudio, a barra já tem Destacar e Definir, e não sobra nada.
 */
internal fun acoesDaSelecaoSoNoMenu(comAudio: Boolean): List<AcaoDaBarraDeSelecao> =
    if (comAudio) AcaoDaBarraDeSelecao.entries.filter { it !in acoesDaBarraDeSelecao(true) } else emptyList()

/**
 * A repintura de sincronia espera enquanto há dedo na lista, menu aberto ou texto selecionado com foco: trocar a view
 * cancela o toque, e trocar o texto sob uma seleção recém-feita a desloca.
 */
internal fun segurarRepinturaDeSincronia(
    toqueEmAndamento: Boolean,
    selecoesAbertas: Int,
    textoComSelecao: Boolean,
): Boolean = toqueEmAndamento || selecoesAbertas > 0 || textoComSelecao

/**
 * Se o bind de um item pode manter o texto que já está na tela (rb-53). Com o dedo sobre o parágrafo, a repintura
 * pendente de sincronia reescreveria o texto, e o Editor perderia a posição do toque: o toque longo não selecionaria
 * nada. Texto de outro parágrafo (item reaproveitado) sempre é refeito. Com seleção já feita, o bind segue o caminho
 * dela, que só troca o fundo.
 */
internal fun manterTextoNoBind(textoJaNaTela: Boolean, sobODedo: Boolean, selecaoAtiva: Boolean): Boolean =
    textoJaNaTela && sobODedo && !selecaoAtiva

/**
 * Toque curto (rb-42): rápido, quase parado e sem seleção nativa aberta no DOWN nem criada pelo próprio toque.
 * Só ele lê a partir do parágrafo, já no 1º toque. Toque longo, arraste ou toque que fecha a seleção não valem.
 */
internal fun ehToqueCurto(
    duracaoMs: Long,
    deslocamentoPx: Float,
    slopPx: Float,
    limiteDoToqueLongoMs: Long,
    selecaoAberta: Boolean,
): Boolean = !selecaoAberta && duracaoMs < limiteDoToqueLongoMs && deslocamentoPx < slopPx

/** O que a barra da seleção de texto faz agora, sem o modo do sistema (rb-54). */
internal enum class PassoDaBarraDaSelecao { NADA, ESPERAR, MOSTRAR_SEM_MENU, ESCONDER }

/**
 * Decide o passo da barra da seleção de texto (rb-54). Seleção viva é a do parágrafo com foco e com seleção.
 * - Com o menu do sistema aberto, a barra é dele: NADA.
 * - Sem seleção viva, a barra some, mesmo no meio do arraste (como o fechar do menu já fazia).
 * - Com a lista ou o dedo em movimento, NADA: a conferência volta no fim do toque ou da rolagem.
 * - Viva e sem menu, espera a janela do duplo toque: o Editor pode reabrir o modo sozinho nesse intervalo.
 * - Se o menu não voltou, a barra aparece sem menu (as ações dela desfazem a seleção).
 */
internal fun passoDaBarraDaSelecao(
    menuAberto: Boolean,
    selecaoViva: Boolean,
    emMovimento: Boolean,
    jaEsperou: Boolean,
): PassoDaBarraDaSelecao = when {
    menuAberto -> PassoDaBarraDaSelecao.NADA
    !selecaoViva -> PassoDaBarraDaSelecao.ESCONDER
    emMovimento -> PassoDaBarraDaSelecao.NADA
    !jaEsperou -> PassoDaBarraDaSelecao.ESPERAR
    else -> PassoDaBarraDaSelecao.MOSTRAR_SEM_MENU
}

/**
 * Repinturas de sincronia que esperam o toque ou o menu terminar (rb-38). Só na main thread: o adapter e a lista
 * chamam daí. Guarda cada índice uma vez, na ordem em que chegou.
 */
internal class RepinturasPendentes {
    private val pendentes = LinkedHashSet<Int>()

    /** Segurando, guarda os índices e devolve vazio. Sem segurar, devolve os guardados mais os novos e esvazia. */
    fun pedir(indices: Collection<Int>, segurar: Boolean): List<Int> {
        pendentes.addAll(indices)
        return if (segurar) emptyList() else liberar()
    }

    /** Devolve os índices guardados, na ordem, e esvazia. */
    fun liberar(): List<Int> = pendentes.toList().also { pendentes.clear() }

    /** Esquece o que estava guardado (lista ou aparência trocada: a repintura inteira já cobre tudo). */
    fun descartar() = pendentes.clear()
}
