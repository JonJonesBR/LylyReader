package com.jonjonesbr.audiobookgen.domain

import kotlin.math.max

/**
 * Como uma barra do Leitor (cima ou baixo) se comporta (rb-66). SEMPRE deixa a barra à mostra; TEMPO a recolhe
 * sozinha depois de um tempo sem interação (é o padrão); MANUAL só muda quando a pessoa pede. Guardado pelo nome
 * em `AppPrefs`; nome ausente ou desconhecido vale TEMPO.
 */
enum class ModoDaBarra {
    SEMPRE,
    TEMPO,
    MANUAL;

    companion object {
        /** Lê o nome guardado. Nulo ou desconhecido vira TEMPO, o padrão do Leitor. */
        fun de(nome: String?): ModoDaBarra = values().firstOrNull { it.name == nome } ?: ModoDaBarra.TEMPO
    }
}

/**
 * O que aconteceu e pede que a barra mude (rb-66). LEITURA_ANDOU é a leitura avançando (virar a página com o dedo
 * ou rolar para baixo); ROLOU_PARA_CIMA é o pedido de voltar a ver as barras pela rolagem.
 */
enum class GestoDasBarras {
    MOSTRAR,
    RECOLHER,
    LEITURA_ANDOU,
    ROLOU_PARA_CIMA,
    TEMPO_ESGOTADO,
}

/** A setinha discreta de uma barra: nenhuma, a que mostra ou a que recolhe (rb-66). */
enum class Setinha {
    NENHUMA,
    MOSTRAR,
    RECOLHER,
}

/**
 * Situação do Leitor que decide o cabeçalho (rb-66). [leitura] é falso em erro, carregamento, edição ou
 * comparação, e aí o cabeçalho é obrigatório. [leitorDeTela] é o TalkBack. [barraDoFluxoVisivel] vale para a
 * barra do modo ou a de seleção de parágrafos, que pedem lugar. [selecaoNoTopo] esconde o cabeçalho para não
 * cobrir a seleção de texto.
 */
data class SituacaoDoLeitor(
    val leitura: Boolean,
    val leitorDeTela: Boolean,
    val buscaAberta: Boolean,
    val barraDoFluxoVisivel: Boolean,
    val selecaoNoTopo: Boolean,
)

/**
 * Resultado para o cabeçalho (rb-66): se aparece, se ocupa lugar no texto ([reservado]; sem isso ele fica por cima
 * do texto) e qual setinha mostra.
 */
data class EstadoDoCabecalho(
    val visivel: Boolean,
    val reservado: Boolean,
    val setinha: Setinha,
)

/**
 * Motivos que impedem recolher a barra pelo tempo (rb-66). Qualquer um verdadeiro segura o recolhimento: a pessoa
 * está selecionando, o app está em segundo plano, há busca ou menu aberto, o TalkBack está ligado, ou há foco ou
 * toque nas barras ou uma dica na tela.
 */
data class BloqueiosDoTempo(
    val selecao: Boolean,
    val janelaSemFoco: Boolean,
    val buscaAberta: Boolean,
    val leitorDeTela: Boolean,
    val focoOuToqueNasBarras: Boolean,
    val dicaAberta: Boolean,
)

/**
 * Rolagem acumulada até disparar um gesto (rb-66). [acumulado] é o que falta acumular no mesmo sentido; [sinal] é
 * -1 para recolher, 1 para mostrar, ou 0 quando nada disparou.
 */
data class RolagemAcumulada(val acumulado: Int, val sinal: Int)

/**
 * Regra pura das barras recolhíveis do Leitor (rb-66, passo 1). Decide o que aparece a partir do modo de cada barra,
 * do estado guardado (`aberto`, o que a pessoa ou o tempo pediu) e da situação do Leitor. Não sabe de Android: o
 * Leitor só liga os sensores (toque, rolagem, tempo, TalkBack) a estas funções. Sem Android.
 */
object RegraDasBarrasDoLeitor {

    /** Tempo sem interação até a barra de cima recolher (ms). Quatro segundos é o que a leitura pede. */
    const val TEMPO_PADRAO_MS = 4000

    /** Quanto rolar para baixo (em dp) para recolher a barra no modo vertical. Pouco, para a leitura não piscar. */
    const val LIMIAR_DESCER_DP = 24

    /** Quanto rolar para cima (em dp) para mostrar a barra no modo vertical. Mais que para descer, para não piscar. */
    const val LIMIAR_SUBIR_DP = 48

    /**
     * Estado do cabeçalho. Ele é obrigatório (reservado e à mostra) sem leitura com texto, com TalkBack, com busca
     * aberta, ou no modo SEMPRE. Com seleção no topo, esconde para não cobrir a seleção. No modo páginas não tem
     * setinha, porque o deslize já mostra e recolhe.
     */
    fun cabecalho(modo: ModoDaBarra, aberto: Boolean, paginas: Boolean, s: SituacaoDoLeitor): EstadoDoCabecalho {
        val forcado = !s.leitura || s.leitorDeTela || s.buscaAberta || modo == ModoDaBarra.SEMPRE
        val visivel = forcado || (aberto && !s.selecaoNoTopo)
        val reservado = visivel && (forcado || s.barraDoFluxoVisivel)
        val setinha = when {
            paginas || forcado || s.selecaoNoTopo -> Setinha.NENHUMA
            visivel -> Setinha.RECOLHER
            else -> Setinha.MOSTRAR
        }
        return EstadoDoCabecalho(visivel, reservado, setinha)
    }

    /**
     * O painel de baixo da guiada aparece? Com TalkBack ele fica sempre aberto, porque a pessoa precisa alcançar os
     * controles. No modo SEMPRE também. Nos outros modos segue o estado guardado.
     */
    fun painelAberto(modo: ModoDaBarra, aberto: Boolean, leitorDeTela: Boolean): Boolean =
        modo == ModoDaBarra.SEMPRE || leitorDeTela || aberto

    /**
     * A tira do painel de baixo mostra a setinha para abrir e fechar? Não no modo SEMPRE (sempre aberto) nem com
     * TalkBack (o painel não fecha).
     */
    fun setaDoPainel(modo: ModoDaBarra, leitorDeTela: Boolean): Boolean =
        modo != ModoDaBarra.SEMPRE && !leitorDeTela

    /**
     * Novo estado guardado de `aberto` depois de um gesto ou do tempo. SEMPRE não muda (fica como estava, mas o
     * cabeçalho ignora). Mostrar sempre abre; recolher sempre fecha. A leitura andando e o tempo recolhem só no modo
     * TEMPO, e não mexem no modo MANUAL, que só muda quando a pessoa pede. Rolar para cima mostra só no modo TEMPO.
     */
    fun depoisDoGesto(modo: ModoDaBarra, aberto: Boolean, gesto: GestoDasBarras): Boolean {
        if (modo == ModoDaBarra.SEMPRE) return aberto
        return when (gesto) {
            GestoDasBarras.MOSTRAR -> true
            GestoDasBarras.RECOLHER -> false
            GestoDasBarras.ROLOU_PARA_CIMA -> modo == ModoDaBarra.TEMPO || aberto
            GestoDasBarras.LEITURA_ANDOU, GestoDasBarras.TEMPO_ESGOTADO -> modo != ModoDaBarra.TEMPO && aberto
        }
    }

    /**
     * O tempo pode recolher a barra agora? Só quando nenhum bloqueio vale: seleção, janela sem foco, busca,
     * TalkBack, foco ou toque nas barras, e dica na tela. Com qualquer um deles a barra fica onde está.
     */
    fun podeRecolherPorTempo(b: BloqueiosDoTempo): Boolean =
        !b.selecao && !b.janelaSemFoco && !b.buscaAberta && !b.leitorDeTela && !b.focoOuToqueNasBarras && !b.dicaAberta

    /**
     * Acumula a rolagem do modo vertical (em pixels). Rolar no mesmo sentido soma; ao inverter o sentido, a soma
     * recomeça do movimento atual, para o tremor do dedo não contar. Ao passar do limiar, devolve o sinal (-1 para
     * recolher quando rola para baixo, dy > 0; 1 para mostrar quando rola para cima, dy < 0) e zera o acumulado.
     * Movimento zero não muda nada.
     */
    fun rolagem(acumulado: Int, dy: Int, limiarDescer: Int, limiarSubir: Int): RolagemAcumulada {
        if (dy == 0) return RolagemAcumulada(acumulado, 0)
        val base = if (acumulado != 0 && (acumulado > 0) != (dy > 0)) 0 else acumulado
        val total = base + dy
        return when {
            dy > 0 && total >= limiarDescer -> RolagemAcumulada(0, -1)
            dy < 0 && -total >= limiarSubir -> RolagemAcumulada(0, 1)
            else -> RolagemAcumulada(total, 0)
        }
    }

    /**
     * Tempo (em ms) até recolher a barra. Respeita o tempo recomendado pelo sistema (o ajuste "Tempo para agir" de
     * acessibilidade), mas nunca menos que [TEMPO_PADRAO_MS]. Sem recomendação, vale o padrão.
     */
    fun tempoParaRecolherMs(recomendado: Int?): Int = max(TEMPO_PADRAO_MS, recomendado ?: TEMPO_PADRAO_MS)

    /**
     * O estado de `aberto` ao trocar o modo de uma barra nos Ajustes. Ir para SEMPRE deixa aberta. Sair de SEMPRE
     * começa aberta, e o tempo (ou a pessoa) decide o resto, o que já mostra a opção funcionando. Nos outros casos
     * mantém o que a pessoa vê agora.
     */
    fun abertoAoTrocarDeModo(anterior: ModoDaBarra, novo: ModoDaBarra, visivelAgora: Boolean): Boolean = when {
        novo == ModoDaBarra.SEMPRE -> true
        anterior == ModoDaBarra.SEMPRE -> true
        else -> visivelAgora
    }
}
