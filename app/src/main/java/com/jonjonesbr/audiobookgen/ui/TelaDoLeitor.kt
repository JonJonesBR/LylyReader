package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.MARGEM_NIVEL_PADRAO
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.TextoExtraido

// Cálculo puro do estado da tela do leitor (rb-8): sem nada de Android, testado na JVM.
// A aplicação nas views é do ReaderTelaController.

private const val MARGEM_ESTREITA_DP = 8
private const val MARGEM_MEDIA_DP = 20
private const val MARGEM_LARGA_DP = 36
// 0=estreita, 1=média, 2=larga.
private val MARGEM_NIVEL_DP = intArrayOf(MARGEM_ESTREITA_DP, MARGEM_MEDIA_DP, MARGEM_LARGA_DP)

/** Qual aviso de carregamento aparece: abrindo o livro ou preparando o texto (limpeza). */
internal enum class CarregandoDoLeitor { ABRINDO, PREPARANDO }

/** O que a pílula da limpeza faz agora: some, anima (uma vez por abertura) ou fica como está. */
internal enum class PilulaDaLimpeza { OCULTAR, ANIMAR, NAO_MEXER }

/** Rótulo da barra de modo ativo: edição ou comparação (nulo quando não há modo especial). */
internal enum class RotuloDoModo { EDICAO, COMPARACAO }

/** Decisão da tela para uma atualização de estado. Só usa o [ReaderUiState] recebido, sem ler o ViewModel. */
internal data class TelaDoLeitor(
    /** ABRINDO se `state.carregando`, senão nulo. */
    val carregandoAoAbrir: CarregandoDoLeitor?,
    /** `state.erro`. Com erro, a lista some. */
    val erro: String?,
    /**
     * Com texto na tela, depois do status: limpandoTexto -> PREPARANDO; textoLimpo != null -> nulo;
     * senão = carregandoAoAbrir.
     */
    val carregando: CarregandoDoLeitor?,
    /**
     * limpandoTexto -> OCULTAR; textoLimpo != null -> ANIMAR (se ainda não mostrada e há linhas removidas),
     * senão NAO_MEXER; senão OCULTAR.
     */
    val pilula: PilulaDaLimpeza,
    /** `state.relatorioLinhasRemovidas`, para o texto da pílula. */
    val linhasRemovidas: Int,
    /** modoEdicao -> EDICAO; mostrandoAlteracoes -> COMPARACAO; senão nulo. */
    val rotuloModo: RotuloDoModo?,
    /** modoEdicao -> EDICAO; mostrandoAlteracoes -> ALTERACOES; senão LEITURA. */
    val modoAdapter: AdapterModo,
    /**
     * LEITURA e `selecaoInicio` não nulo -> quantos parágrafos a seleção cobre; senão 0.
     * A barra de seleção do topo (rb-30) aparece com a guiada parada, tocando ou pausada.
     */
    val paragrafosSelecionados: Int,
)

/**
 * [pilulaJaMostrada]: a pílula da limpeza já apareceu nesta abertura do leitor (o ViewModel guarda isso).
 * Pura: não lê o ViewModel; o texto efetivo é lido depois, na Activity, e entra no controller por aplicarStatus.
 */
@Suppress("CyclomaticComplexMethod") // tabela de decisão: um ramo por estado da tela
internal fun calcularTelaDoLeitor(state: ReaderUiState, pilulaJaMostrada: Boolean): TelaDoLeitor {
    val carregandoAoAbrir = if (state.carregando) CarregandoDoLeitor.ABRINDO else null
    val carregando = when {
        state.limpandoTexto -> CarregandoDoLeitor.PREPARANDO
        state.textoLimpo != null -> null
        else -> carregandoAoAbrir
    }
    val pilula = when {
        state.limpandoTexto -> PilulaDaLimpeza.OCULTAR
        state.textoLimpo != null ->
            if (!pilulaJaMostrada && state.relatorioLinhasRemovidas > 0) PilulaDaLimpeza.ANIMAR
            else PilulaDaLimpeza.NAO_MEXER
        else -> PilulaDaLimpeza.OCULTAR
    }
    val rotuloModo = when {
        state.modoEdicao -> RotuloDoModo.EDICAO
        state.mostrandoAlteracoes -> RotuloDoModo.COMPARACAO
        else -> null
    }
    val modoAdapter = when {
        state.modoEdicao -> AdapterModo.EDICAO
        state.mostrandoAlteracoes -> AdapterModo.ALTERACOES
        else -> AdapterModo.LEITURA
    }
    return TelaDoLeitor(
        carregandoAoAbrir = carregandoAoAbrir,
        erro = state.erro,
        carregando = carregando,
        pilula = pilula,
        linhasRemovidas = state.relatorioLinhasRemovidas,
        rotuloModo = rotuloModo,
        modoAdapter = modoAdapter,
        paragrafosSelecionados = if (modoAdapter == AdapterModo.LEITURA) {
            paragrafosNaSelecao(state.selecaoInicio, state.selecaoFim)
        } else {
            0
        },
    )
}

/** Estado dos FABs do leitor. [capitulosNoRotulo] é a contagem de capítulos marcados (o FAB só existe com eles). */
internal data class FabsDoLeitor(val voltarNarracao: Boolean, val converter: Boolean, val capitulosNoRotulo: Int)

/**
 * Visibilidade e conteúdo dos FABs do leitor. O FAB de conversão é só dos capítulos marcados (rb-30): a seleção
 * de texto e de parágrafos tem o próprio menu e a própria barra. Com trecho selecionado o FAB some, para não haver
 * dois botões de gerar áudio na tela.
 * Escondido durante a leitura guiada, tocando ou pausada: a barra da guiada ocupa a mesma âncora na base da tela, com
 * elevação maior, e o FAB ficaria atrás dela. Some também em modos especiais (edição/diff).
 * O livro inteiro e o capítulo atual ficam em ⋮ › Gerar audiobook (antes o botão ficava sempre sobre o texto).
 * "Voltar à narração" ocupa o lugar do FAB de conversão só no modo manual da guiada (quem lê rolou).
 *
 * Nulo sem manager ("Ler sem áudio"): não mexe nos FABs. Nesse modo o controller já os escondeu.
 */
internal fun estadoDosFabs(
    temManager: Boolean,
    guiadaAtiva: Boolean,
    scrollManual: Boolean,
    temSelecao: Boolean,
    capitulosMarcados: Int,
): FabsDoLeitor? {
    if (!temManager) return null
    return FabsDoLeitor(
        voltarNarracao = guiadaAtiva && scrollManual,
        converter = capitulosMarcados > 0 && !temSelecao && !guiadaAtiva,
        capitulosNoRotulo = capitulosMarcados,
    )
}

/** Início da leitura guiada: abrir o diálogo de ponto de início e/ou continuar na posição salva. */
internal data class InicioDaGuiada(val abrirDialogo: Boolean, val continuar: Boolean)

/**
 * Decide o início automático da leitura guiada.
 * [pedidos] são os extras da intent; [jaResolvidos] são as flags da instância. Podem valer juntos (como hoje).
 *
 * "Ler sem áudio" nunca inicia. A limpeza espera: a posição salva e os capítulos valem para o texto limpo. Começar
 * no bruto tocava o parágrafo anterior ao marcado (o bruto tem lixo a mais antes), e o marcador ficava um à frente
 * do áudio.
 *
 * Não reabre o diálogo nem refaz o "Continuar" ao reconectar a uma leitura em andamento (rotação da tela): a intent
 * original ainda carrega o extra, e a flag da instância se perde. Ao reconectar, a leitura já está tocando.
 */
internal fun decidirInicioDaGuiada(
    somenteLeitura: Boolean,
    estado: ReaderUiState,
    pedidos: InicioDaGuiada,
    jaResolvidos: InicioDaGuiada,
    reconectando: Boolean,
): InicioDaGuiada {
    if (somenteLeitura || (estado.textoLimpo == null && estado.limpandoTexto)) return InicioDaGuiada(false, false)
    return InicioDaGuiada(
        abrirDialogo = pedidos.abrirDialogo && !jaResolvidos.abrirDialogo && !reconectando,
        continuar = pedidos.continuar && !jaResolvidos.continuar && !reconectando,
    )
}

/**
 * O início automático só vale quando a lista entregue ao manager (`setParagraphs`) é a do texto em vigor. Antes disso,
 * `play()` lê outra lista (marcador deslocado) ou uma lista vazia (não começa). A comparação é por identidade porque
 * roda a cada tick de progresso. Se as listas não forem a mesma, a próxima emissão do estado tenta de novo (rb-15).
 */
internal fun paragrafosProntosParaGuiada(entregues: List<Paragrafo>?, texto: TextoExtraido): Boolean =
    entregues != null && entregues === texto.paragrafos

/** Margem horizontal da lista de parágrafos, em dp, para o nível salvo (nível fora da faixa usa o padrão). */
internal fun margemDosParagrafosDp(nivel: Int): Int =
    MARGEM_NIVEL_DP.getOrElse(nivel) { MARGEM_NIVEL_DP[MARGEM_NIVEL_PADRAO] }

private const val MS_POR_MINUTO = 60_000L

/**
 * Minutos inteiros que a tela do leitor ficou visível, desde [desdeMs] até [agoraMs]. Zero quando não há início
 * registrado. Simplificação deliberada: conta a tela visível mesmo com a leitura guiada tocando, por isso um
 * mesmo intervalo também entra em "minutos ouvidos" (GuidedPlayerManager). É engajamento aproximado, não uma
 * partição exata do tempo da pessoa.
 */
internal fun minutosLidos(desdeMs: Long, agoraMs: Long): Int =
    if (desdeMs <= 0L) 0 else ((agoraMs - desdeMs) / MS_POR_MINUTO).toInt()
