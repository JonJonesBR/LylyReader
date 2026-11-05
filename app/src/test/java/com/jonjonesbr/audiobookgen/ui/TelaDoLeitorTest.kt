package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun voltarNarracaoFicaNoPainelSoNoModoPaginas() {
        // rb-26b (passo 4): sem o modo manual não há botão; com ele, o painel no modo páginas e o chip nos outros.
        assertEquals(LugarDoVoltar.NENHUM, lugarDoVoltarANarracao(voltar = false, paginas = false))
        assertEquals(LugarDoVoltar.NENHUM, lugarDoVoltarANarracao(voltar = false, paginas = true))
        assertEquals(LugarDoVoltar.CHIP, lugarDoVoltarANarracao(voltar = true, paginas = false))
        assertEquals(LugarDoVoltar.PAINEL, lugarDoVoltarANarracao(voltar = true, paginas = true))
    }

    @Test
    fun fabConverterSoComCapitulosMarcados() {
        // Com capítulos marcados e sem seleção, o FAB aparece com a contagem (rb-30).
        val doisCapitulos = FabsDoLeitor(voltarNarracao = false, converter = true, capitulosNoRotulo = 2)
        assertEquals(doisCapitulos, fabs(capitulosMarcados = 2))
        // O rótulo leva a contagem mesmo quando o FAB está escondido; por isso o esperado aqui não é semFabs().
        val ocultoComDois = FabsDoLeitor(voltarNarracao = false, converter = false, capitulosNoRotulo = 2)
        // Com seleção de texto, o FAB some: a seleção tem o próprio menu e a própria barra.
        assertEquals(ocultoComDois, fabs(temSelecao = true, capitulosMarcados = 2))
        assertEquals(semFabs(), fabs(temSelecao = true))
        // Com a guiada ativa (tocando ou pausada), o FAB não aparece.
        assertEquals(ocultoComDois, fabs(guiadaAtiva = true, capitulosMarcados = 2))
    }

    @Test
    fun paragrafosSelecionadosSoNaLeitura() {
        val selecao = ReaderUiState(selecaoInicio = 2, selecaoFim = 5)
        assertEquals(4, calcularTelaDoLeitor(selecao, pilulaJaMostrada = false).paragrafosSelecionados)
        val emEdicao = ReaderUiState(modoEdicao = true, selecaoInicio = 2, selecaoFim = 5)
        assertEquals(0, calcularTelaDoLeitor(emEdicao, pilulaJaMostrada = false).paragrafosSelecionados)
        assertEquals(0, calcularTelaDoLeitor(ReaderUiState(), pilulaJaMostrada = false).paragrafosSelecionados)
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

    @Test
    fun minutosLidosZeroSemInicioRegistrado() {
        assertEquals(0, minutosLidos(0L, 10_000_000L))
    }

    @Test
    fun minutosLidosNaoContaMinutoIncompleto() {
        assertEquals(0, minutosLidos(1_000L, 1_000L + 59_000L))
    }

    @Test
    fun minutosLidosContaMinutoCompletoEDescartaSobra() {
        assertEquals(1, minutosLidos(1_000L, 1_000L + 61_000L))
        assertEquals(2, minutosLidos(1_000L, 1_000L + 125_000L))
    }

    private val textoComDoisParagrafos = TextoExtraido(
        capitulos = emptyList(),
        paragrafos = listOf(Paragrafo("Primeiro.", 0, 0), Paragrafo("Segundo.", 9, 0)),
        totalChars = 18,
    )

    @Test
    fun guiadaEsperaOsParagrafosNoManager() {
        assertFalse(paragrafosProntosParaGuiada(null, textoComDoisParagrafos))
    }

    @Test
    fun listaDiferenteNaoServe() {
        // Mesmo conteúdo em outra instância não serve: a comparação é por identidade (rb-15).
        assertFalse(paragrafosProntosParaGuiada(textoComDoisParagrafos.paragrafos.toList(), textoComDoisParagrafos))
    }

    @Test
    fun mesmaListaLibera() {
        assertTrue(paragrafosProntosParaGuiada(textoComDoisParagrafos.paragrafos, textoComDoisParagrafos))
    }
}
