package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegraDasBarrasDoLeitorTest {

    private val regra = RegraDasBarrasDoLeitor

    // Leitura normal, sem nada forçado: o cabeçalho depende só do modo, de `aberto` e da seleção.
    private fun situacao(
        leitura: Boolean = true,
        leitorDeTela: Boolean = false,
        buscaAberta: Boolean = false,
        barraDoFluxoVisivel: Boolean = false,
        selecaoNoTopo: Boolean = false,
    ) = SituacaoDoLeitor(leitura, leitorDeTela, buscaAberta, barraDoFluxoVisivel, selecaoNoTopo)

    private val semBloqueio = BloqueiosDoTempo(
        selecao = false,
        janelaSemFoco = false,
        buscaAberta = false,
        leitorDeTela = false,
        focoOuToqueNasBarras = false,
        dicaAberta = false,
    )

    // --- cabecalho: os forçados, um a um ---

    @Test
    fun cabecalhoForcadoSemLeituraComTexto() {
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = false, paginas = false, s = situacao(leitura = false))
        assertEquals(EstadoDoCabecalho(visivel = true, reservado = true, setinha = Setinha.NENHUMA), e)
    }

    @Test
    fun cabecalhoForcadoComLeitorDeTela() {
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = false, paginas = false, s = situacao(leitorDeTela = true))
        assertEquals(EstadoDoCabecalho(visivel = true, reservado = true, setinha = Setinha.NENHUMA), e)
    }

    @Test
    fun cabecalhoForcadoComBuscaAberta() {
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = false, paginas = false, s = situacao(buscaAberta = true))
        assertEquals(EstadoDoCabecalho(visivel = true, reservado = true, setinha = Setinha.NENHUMA), e)
    }

    @Test
    fun cabecalhoForcadoNoModoSempre() {
        val e = regra.cabecalho(ModoDaBarra.SEMPRE, aberto = false, paginas = false, s = situacao())
        assertEquals(EstadoDoCabecalho(visivel = true, reservado = true, setinha = Setinha.NENHUMA), e)
    }

    // --- cabecalho: seleção no topo e reserva ---

    @Test
    fun selecaoNoTopoEsconde_mesmoAberto() {
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = true, paginas = false, s = situacao(selecaoNoTopo = true))
        assertEquals(EstadoDoCabecalho(visivel = false, reservado = false, setinha = Setinha.NENHUMA), e)
    }

    @Test
    fun selecaoNoTopoNaoEsconde_quandoForcado() {
        val e = regra.cabecalho(ModoDaBarra.SEMPRE, aberto = false, paginas = false, s = situacao(selecaoNoTopo = true))
        assertTrue(e.visivel)
        assertTrue(e.reservado)
    }

    @Test
    fun abertoSemBarraDoFluxo_naoReserva() {
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = true, paginas = false, s = situacao())
        assertTrue(e.visivel)
        assertFalse(e.reservado)
    }

    @Test
    fun abertoComBarraDoFluxo_reserva() {
        val s = situacao(barraDoFluxoVisivel = true)
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = true, paginas = false, s = s)
        assertTrue(e.visivel)
        assertTrue(e.reservado)
    }

    @Test
    fun fechadoComBarraDoFluxo_naoMostraNemReserva() {
        val s = situacao(barraDoFluxoVisivel = true)
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = false, paginas = false, s = s)
        assertFalse(e.visivel)
        assertFalse(e.reservado)
    }

    // --- cabecalho: setinha ---

    @Test
    fun setinhaMostraQuandoFechadoNoVertical() {
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = false, paginas = false, s = situacao())
        assertEquals(Setinha.MOSTRAR, e.setinha)
    }

    @Test
    fun setinhaRecolheQuandoAbertoNoVertical() {
        val e = regra.cabecalho(ModoDaBarra.TEMPO, aberto = true, paginas = false, s = situacao())
        assertEquals(Setinha.RECOLHER, e.setinha)
    }

    @Test
    fun setinhaNenhumaNoModoPaginas_abertoOuFechado() {
        val fechado = regra.cabecalho(ModoDaBarra.TEMPO, aberto = false, paginas = true, s = situacao())
        val aberto = regra.cabecalho(ModoDaBarra.TEMPO, aberto = true, paginas = true, s = situacao())
        assertEquals(Setinha.NENHUMA, fechado.setinha)
        assertEquals(Setinha.NENHUMA, aberto.setinha)
    }

    // --- depoisDoGesto: 3 modos × 5 gestos, com aberto true e false ---

    @Test
    fun depoisDoGestoNoModoSempreSegueAberto() {
        for (aberto in listOf(true, false)) {
            for (gesto in GestoDasBarras.values()) {
                assertEquals("$gesto aberto=$aberto", aberto, regra.depoisDoGesto(ModoDaBarra.SEMPRE, aberto, gesto))
            }
        }
    }

    @Test
    fun depoisDoGestoNoModoTempo() {
        for (aberto in listOf(true, false)) {
            assertTrue(regra.depoisDoGesto(ModoDaBarra.TEMPO, aberto, GestoDasBarras.MOSTRAR))
            assertFalse(regra.depoisDoGesto(ModoDaBarra.TEMPO, aberto, GestoDasBarras.RECOLHER))
            assertFalse(regra.depoisDoGesto(ModoDaBarra.TEMPO, aberto, GestoDasBarras.LEITURA_ANDOU))
            assertTrue(regra.depoisDoGesto(ModoDaBarra.TEMPO, aberto, GestoDasBarras.ROLOU_PARA_CIMA))
            assertFalse(regra.depoisDoGesto(ModoDaBarra.TEMPO, aberto, GestoDasBarras.TEMPO_ESGOTADO))
        }
    }

    @Test
    fun depoisDoGestoNoModoManual() {
        for (aberto in listOf(true, false)) {
            assertTrue(regra.depoisDoGesto(ModoDaBarra.MANUAL, aberto, GestoDasBarras.MOSTRAR))
            assertFalse(regra.depoisDoGesto(ModoDaBarra.MANUAL, aberto, GestoDasBarras.RECOLHER))
            assertEquals(aberto, regra.depoisDoGesto(ModoDaBarra.MANUAL, aberto, GestoDasBarras.LEITURA_ANDOU))
            assertEquals(aberto, regra.depoisDoGesto(ModoDaBarra.MANUAL, aberto, GestoDasBarras.ROLOU_PARA_CIMA))
            assertEquals(aberto, regra.depoisDoGesto(ModoDaBarra.MANUAL, aberto, GestoDasBarras.TEMPO_ESGOTADO))
        }
    }

    // --- podeRecolherPorTempo ---

    @Test
    fun semBloqueiosPodeRecolher() {
        assertTrue(regra.podeRecolherPorTempo(semBloqueio))
    }

    @Test
    fun cadaBloqueioSozinhoImpedeRecolher() {
        assertFalse(regra.podeRecolherPorTempo(semBloqueio.copy(selecao = true)))
        assertFalse(regra.podeRecolherPorTempo(semBloqueio.copy(janelaSemFoco = true)))
        assertFalse(regra.podeRecolherPorTempo(semBloqueio.copy(buscaAberta = true)))
        assertFalse(regra.podeRecolherPorTempo(semBloqueio.copy(leitorDeTela = true)))
        assertFalse(regra.podeRecolherPorTempo(semBloqueio.copy(focoOuToqueNasBarras = true)))
        assertFalse(regra.podeRecolherPorTempo(semBloqueio.copy(dicaAberta = true)))
    }

    // --- rolagem ---

    @Test
    fun rolagemAcumulaNoMesmoSentido() {
        assertEquals(RolagemAcumulada(10, 0), regra.rolagem(0, 10, 24, 48))
        assertEquals(RolagemAcumulada(20, 0), regra.rolagem(10, 10, 24, 48))
    }

    @Test
    fun rolagemParaBaixoDisparaRecolherAoPassarDoLimiarEZera() {
        assertEquals(RolagemAcumulada(0, -1), regra.rolagem(20, 10, 24, 48))
    }

    @Test
    fun rolagemParaCimaDisparaMostrarAoPassarDoLimiarEZera() {
        assertEquals(RolagemAcumulada(0, 1), regra.rolagem(-40, -10, 24, 48))
    }

    @Test
    fun rolagemInverteESomaDoMovimentoAtual() {
        // Acumulou 20 para baixo; um movimento de -5 zera a soma e conta só o próprio movimento.
        assertEquals(RolagemAcumulada(-5, 0), regra.rolagem(20, -5, 24, 48))
        assertEquals(RolagemAcumulada(10, 0), regra.rolagem(-40, 10, 24, 48))
    }

    @Test
    fun limiaresDeDescerESubirSaoDiferentes() {
        // 30 px bastam para recolher (limiar 24), mas não para mostrar (limiar 48).
        assertEquals(RolagemAcumulada(0, -1), regra.rolagem(0, 30, 24, 48))
        assertEquals(RolagemAcumulada(-30, 0), regra.rolagem(0, -30, 24, 48))
        assertEquals(RolagemAcumulada(0, 1), regra.rolagem(0, -50, 24, 48))
    }

    @Test
    fun movimentoZeroNaoMudaOAcumulado() {
        assertEquals(RolagemAcumulada(15, 0), regra.rolagem(15, 0, 24, 48))
    }

    // --- tempoParaRecolherMs ---

    @Test
    fun tempoSemRecomendacaoVaiPara4000() {
        assertEquals(RegraDasBarrasDoLeitor.TEMPO_PADRAO_MS, regra.tempoParaRecolherMs(null))
        assertEquals(4000, regra.tempoParaRecolherMs(null))
    }

    @Test
    fun tempoRecomendadoMenorQuePadraoFicaNoPadrao() {
        assertEquals(4000, regra.tempoParaRecolherMs(1000))
    }

    @Test
    fun tempoRecomendadoMaiorVence() {
        assertEquals(6000, regra.tempoParaRecolherMs(6000))
    }

    // --- ModoDaBarra.de ---

    @Test
    fun modoDeNuloOuDesconhecidoEhTempo() {
        assertEquals(ModoDaBarra.TEMPO, ModoDaBarra.de(null))
        assertEquals(ModoDaBarra.TEMPO, ModoDaBarra.de(""))
        assertEquals(ModoDaBarra.TEMPO, ModoDaBarra.de("lixo"))
    }

    @Test
    fun modoDeLeCadaNome() {
        assertEquals(ModoDaBarra.SEMPRE, ModoDaBarra.de("SEMPRE"))
        assertEquals(ModoDaBarra.TEMPO, ModoDaBarra.de("TEMPO"))
        assertEquals(ModoDaBarra.MANUAL, ModoDaBarra.de("MANUAL"))
    }

    // --- painelAberto, setaDoPainel ---

    @Test
    fun painelAbertoSempreNoSempreComTalkBackOuAberto() {
        assertTrue(regra.painelAberto(ModoDaBarra.SEMPRE, aberto = false, leitorDeTela = false))
        assertTrue(regra.painelAberto(ModoDaBarra.TEMPO, aberto = false, leitorDeTela = true))
        assertTrue(regra.painelAberto(ModoDaBarra.MANUAL, aberto = true, leitorDeTela = false))
        assertFalse(regra.painelAberto(ModoDaBarra.TEMPO, aberto = false, leitorDeTela = false))
    }

    @Test
    fun setaDoPainelSomenteForaDoSempreESemTalkBack() {
        assertFalse(regra.setaDoPainel(ModoDaBarra.SEMPRE, leitorDeTela = false))
        assertFalse(regra.setaDoPainel(ModoDaBarra.TEMPO, leitorDeTela = true))
        assertTrue(regra.setaDoPainel(ModoDaBarra.TEMPO, leitorDeTela = false))
        assertTrue(regra.setaDoPainel(ModoDaBarra.MANUAL, leitorDeTela = false))
    }

    // --- abertoAoTrocarDeModo ---

    @Test
    fun trocarParaSempreDeixaAberto() {
        assertTrue(regra.abertoAoTrocarDeModo(ModoDaBarra.TEMPO, ModoDaBarra.SEMPRE, visivelAgora = false))
        assertTrue(regra.abertoAoTrocarDeModo(ModoDaBarra.MANUAL, ModoDaBarra.SEMPRE, visivelAgora = false))
    }

    @Test
    fun sairDeSempreComecaAberto() {
        assertTrue(regra.abertoAoTrocarDeModo(ModoDaBarra.SEMPRE, ModoDaBarra.TEMPO, visivelAgora = true))
        assertTrue(regra.abertoAoTrocarDeModo(ModoDaBarra.SEMPRE, ModoDaBarra.MANUAL, visivelAgora = true))
    }

    @Test
    fun entreTempoEManualMantemOQueSeVe() {
        assertFalse(regra.abertoAoTrocarDeModo(ModoDaBarra.TEMPO, ModoDaBarra.MANUAL, visivelAgora = false))
        assertTrue(regra.abertoAoTrocarDeModo(ModoDaBarra.MANUAL, ModoDaBarra.TEMPO, visivelAgora = true))
    }
}
