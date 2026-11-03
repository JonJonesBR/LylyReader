package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** rb-8: cálculo puro da tela do leitor (carregando, erro, pílula, modo, FABs e início da guiada). */
class TelaDoLeitorTest {

    private val textoVazio = TextoExtraido(emptyList(), emptyList(), 0)
    private val semInicio = InicioDaGuiada(abrirDialogo = false, continuar = false)
    private val pedidoTudo = InicioDaGuiada(abrirDialogo = true, continuar = true)

    @Test
    fun erroEscondeLista() {
        val tela = calcularTelaDoLeitor(ReaderUiState(erro = "x"), pilulaJaMostrada = false)
        assertEquals("x", tela.erro)
        val abrindo = calcularTelaDoLeitor(ReaderUiState(carregando = true), pilulaJaMostrada = false)
        assertEquals(CarregandoDoLeitor.ABRINDO, abrindo.carregandoAoAbrir)
    }

    @Test
    fun limpandoMostraPreparando() {
        val tela = calcularTelaDoLeitor(ReaderUiState(limpandoTexto = true), pilulaJaMostrada = false)
        assertEquals(CarregandoDoLeitor.PREPARANDO, tela.carregando)
        assertEquals(PilulaDaLimpeza.OCULTAR, tela.pilula)
    }

    @Test
    fun textoLimpoEscondeCarregando() {
        val tela = calcularTelaDoLeitor(
            ReaderUiState(carregando = true, textoLimpo = textoVazio), pilulaJaMostrada = false
        )
        assertNull(tela.carregando)
        assertEquals(CarregandoDoLeitor.ABRINDO, tela.carregandoAoAbrir)
    }

    @Test
    fun pilulaAnimaUmaVezSoComLinhasRemovidas() {
        val comLinhas = ReaderUiState(textoLimpo = textoVazio, relatorioLinhasRemovidas = 3)
        assertEquals(PilulaDaLimpeza.ANIMAR, calcularTelaDoLeitor(comLinhas, pilulaJaMostrada = false).pilula)
        assertEquals(PilulaDaLimpeza.NAO_MEXER, calcularTelaDoLeitor(comLinhas, pilulaJaMostrada = true).pilula)
        val semLinhas = ReaderUiState(textoLimpo = textoVazio, relatorioLinhasRemovidas = 0)
        assertEquals(PilulaDaLimpeza.NAO_MEXER, calcularTelaDoLeitor(semLinhas, pilulaJaMostrada = false).pilula)
    }

    @Test
    fun edicaoVenceComparacao() {
        val ambos = calcularTelaDoLeitor(
            ReaderUiState(modoEdicao = true, mostrandoAlteracoes = true), pilulaJaMostrada = false
        )
        assertEquals(RotuloDoModo.EDICAO, ambos.rotuloModo)
        assertEquals(AdapterModo.EDICAO, ambos.modoAdapter)

        val soComparacao = calcularTelaDoLeitor(ReaderUiState(mostrandoAlteracoes = true), pilulaJaMostrada = false)
        assertEquals(RotuloDoModo.COMPARACAO, soComparacao.rotuloModo)
        assertEquals(AdapterModo.ALTERACOES, soComparacao.modoAdapter)

        val nenhum = calcularTelaDoLeitor(ReaderUiState(), pilulaJaMostrada = false)
        assertNull(nenhum.rotuloModo)
        assertEquals(AdapterModo.LEITURA, nenhum.modoAdapter)
    }

    /** FABs com os parâmetros da regra; o que não importa para o caso fica no padrão. */
    private fun fabs(
        temManager: Boolean = true,
        guiadaAtiva: Boolean = false,
        scrollManual: Boolean = false,
        temSelecao: Boolean = false,
        capitulosMarcados: Int = 0,
    ): FabsDoLeitor? = estadoDosFabs(temManager, guiadaAtiva, scrollManual, temSelecao, capitulosMarcados)

    @Test
    fun fabVoltarSoComGuiadaEManual() {
        val comGuiadaEManual = FabsDoLeitor(voltarNarracao = true, converter = false, capitulosNoRotulo = 0)
        assertEquals(comGuiadaEManual, fabs(guiadaAtiva = true, scrollManual = true))
        assertEquals(semFabs(), fabs(guiadaAtiva = true, scrollManual = false))
        assertEquals(semFabs(), fabs(guiadaAtiva = false, scrollManual = true))
    }

    @Test
    fun fabConverterComSelecaoSemGuiada() {
        // Com seleção e sem guiada, o rótulo é "seleção" (capitulosNoRotulo = 0).
        val selecao = FabsDoLeitor(voltarNarracao = false, converter = true, capitulosNoRotulo = 0)
        assertEquals(selecao, fabs(temSelecao = true))
        // Com capítulos marcados, o rótulo leva a contagem.
        val doisCapitulos = FabsDoLeitor(voltarNarracao = false, converter = true, capitulosNoRotulo = 2)
        assertEquals(doisCapitulos, fabs(capitulosMarcados = 2))
        // Com a guiada ativa, o FAB de conversão some mesmo com seleção.
        assertEquals(semFabs(), fabs(guiadaAtiva = true, temSelecao = true))
    }

    @Test
    fun semManagerNaoMexe() {
        assertNull(fabs(temManager = false, temSelecao = true, capitulosMarcados = 3))
    }

    private fun semFabs() = FabsDoLeitor(voltarNarracao = false, converter = false, capitulosNoRotulo = 0)

    @Test
    fun inicioEsperaALimpeza() {
        val decisao = decidirInicioDaGuiada(
            somenteLeitura = false, estado = ReaderUiState(limpandoTexto = true),
            pedidos = pedidoTudo, jaResolvidos = semInicio, reconectando = false,
        )
        assertEquals(InicioDaGuiada(abrirDialogo = false, continuar = false), decisao)
    }

    @Test
    fun limpezaTerminadaNaoEspera() {
        val decisao = decidirInicioDaGuiada(
            somenteLeitura = false, estado = ReaderUiState(textoLimpo = textoVazio),
            pedidos = pedidoTudo, jaResolvidos = semInicio, reconectando = false,
        )
        assertEquals(InicioDaGuiada(abrirDialogo = true, continuar = true), decisao)
    }

    @Test
    fun reconexaoNaoReabreDialogo() {
        val decisao = decidirInicioDaGuiada(
            somenteLeitura = false, estado = ReaderUiState(textoLimpo = textoVazio),
            pedidos = pedidoTudo, jaResolvidos = semInicio, reconectando = true,
        )
        assertEquals(InicioDaGuiada(abrirDialogo = false, continuar = false), decisao)
    }

    @Test
    fun continuarUmaVezSo() {
        val jaContinuou = InicioDaGuiada(abrirDialogo = false, continuar = true)
        val decisao = decidirInicioDaGuiada(
            somenteLeitura = false, estado = ReaderUiState(textoLimpo = textoVazio),
            pedidos = pedidoTudo, jaResolvidos = jaContinuou, reconectando = false,
        )
        assertEquals(InicioDaGuiada(abrirDialogo = true, continuar = false), decisao)
    }

    @Test
    fun somenteLeituraNuncaInicia() {
        val decisao = decidirInicioDaGuiada(
            somenteLeitura = true, estado = ReaderUiState(textoLimpo = textoVazio),
            pedidos = pedidoTudo, jaResolvidos = semInicio, reconectando = false,
        )
        assertEquals(InicioDaGuiada(abrirDialogo = false, continuar = false), decisao)
    }

    @Test
    fun dialogoEContinuarValemJuntos() {
        val decisao = decidirInicioDaGuiada(
            somenteLeitura = false, estado = ReaderUiState(textoLimpo = textoVazio),
            pedidos = pedidoTudo, jaResolvidos = semInicio, reconectando = false,
        )
        assertEquals(InicioDaGuiada(abrirDialogo = true, continuar = true), decisao)
    }

    @Test
    fun margemForaDaFaixaUsaPadrao() {
        // 0=estreita (8dp), 1=média (20dp, padrão), 2=larga (36dp). Nível fora da faixa cai no padrão.
        assertEquals(8, margemDosParagrafosDp(0))
        assertEquals(20, margemDosParagrafosDp(1))
        assertEquals(36, margemDosParagrafosDp(2))
        assertEquals(20, margemDosParagrafosDp(7))
    }
}
