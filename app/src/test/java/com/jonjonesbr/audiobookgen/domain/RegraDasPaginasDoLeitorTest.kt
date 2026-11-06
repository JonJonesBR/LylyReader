package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegraDasPaginasDoLeitorTest {

    private val regra = RegraDasPaginasDoLeitor

    // Três parágrafos de linhas de 30 e 40 px, com espaço vazio entre eles.
    private val livro = listOf(
        LinhaDaPagina(0, 40), LinhaDaPagina(40, 80), LinhaDaPagina(80, 120),
        LinhaDaPagina(150, 190), LinhaDaPagina(190, 230),
        LinhaDaPagina(260, 300), LinhaDaPagina(300, 340), LinhaDaPagina(340, 380), LinhaDaPagina(380, 420),
        LinhaDaPagina(450, 490), LinhaDaPagina(490, 520), LinhaDaPagina(520, 560),
        LinhaDaPagina(600, 640), LinhaDaPagina(640, 680), LinhaDaPagina(680, 720),
        LinhaDaPagina(750, 790), LinhaDaPagina(790, 830), LinhaDaPagina(830, 870),
        LinhaDaPagina(900, 940), LinhaDaPagina(940, 980),
    )

    // Três linhas de 40 px de um parágrafo, e uma quarta de outro parágrafo, depois de um espaço vazio de 30 px.
    private val quatroLinhas = listOf(
        LinhaDaPagina(0, 40), LinhaDaPagina(40, 80), LinhaDaPagina(80, 120), LinhaDaPagina(150, 190),
    )

    private val limiteDeFolhas = 1000

    /** Linhas (pelo índice) que a página mostra inteiras: começam em [topo] ou depois e terminam até o corte. */
    private fun mostradas(linhas: List<LinhaDaPagina>, topo: Int, base: Int): Set<Int> {
        val corte = regra.corte(linhas, base)
        return linhas.indices.filter { linhas[it].topo >= topo && linhas[it].base <= corte }.toSet()
    }

    /** Folheia para a frente até o fim do livro. Devolve o topo da última página e as linhas de cada página. */
    private fun folhearParaFrente(linhas: List<LinhaDaPagina>, altura: Int): Pair<Int, List<Set<Int>>> {
        var topo = linhas.first().topo
        val paginas = mutableListOf<Set<Int>>()
        var folhas = 0
        while (folhas < limiteDeFolhas) {
            folhas++
            paginas += mostradas(linhas, topo, topo + altura)
            val passo = regra.passoParaFrente(linhas, topo, topo + altura)
            if (passo == 0) break
            topo += passo
        }
        return topo to paginas
    }

    /** Topos por onde a ida passa, uma página de cada vez, começando no início do livro. */
    private fun toposDaIda(linhas: List<LinhaDaPagina>, altura: Int): List<Int> {
        var topo = linhas.first().topo
        val tops = mutableListOf(topo)
        var folhas = 0
        while (folhas < limiteDeFolhas) {
            folhas++
            val passo = regra.passoParaFrente(linhas, topo, topo + altura)
            if (passo == 0) break
            topo += passo
            tops += topo
        }
        return tops
    }

    /** Uma página: o topo, a base até onde ela desenha (antes do corte) e as linhas que mostra. */
    private data class Pagina(val topo: Int, val base: Int, val linhas: Set<Int>)

    /**
     * Volta página a página a partir de [topoFinal], como o modo páginas: a página da volta vai até a linha de onde
     * se voltou ([fimDaVolta]), e o ajuste leva o topo à primeira linha inteira. A primeira página é a de [topoFinal].
     */
    private fun folhearParaTrasComFim(linhas: List<LinhaDaPagina>, altura: Int, topoFinal: Int): List<Pagina> {
        val inicio = linhas.first().topo
        var topo = topoFinal
        val paginas = mutableListOf(Pagina(topo, topo + altura, mostradas(linhas, topo, topo + altura)))
        var folhas = 0
        while (folhas < limiteDeFolhas && topo > inicio) {
            folhas++
            val raw = topo - altura
            val novoTopo = raw + regra.ajusteDepoisDeVoltar(linhas, raw)
            val fim = regra.fimDaVolta(novoTopo + altura, topo)
            paginas += Pagina(novoTopo, fim, mostradas(linhas, novoTopo, fim))
            topo = novoTopo
        }
        return paginas
    }

    @Test
    fun `corte acompanha a base da ultima linha inteira dentro do limite`() {
        val linhas = quatroLinhas
        assertEquals(120, regra.corte(linhas, 170))
        assertEquals(80, regra.corte(linhas, 100))
        assertEquals(120, regra.corte(linhas, 120))
    }

    @Test
    fun `corte sem nenhuma linha inteira devolve a propria base`() {
        val linhas = listOf(LinhaDaPagina(0, 40), LinhaDaPagina(40, 80))
        assertEquals(30, regra.corte(linhas, 30))
        assertEquals(30, regra.corte(emptyList(), 30))
    }

    @Test
    fun `passo para frente vai ate o topo da primeira linha escondida`() {
        val linhas = quatroLinhas
        assertEquals(150, regra.passoParaFrente(linhas, 0, 160))
    }

    @Test
    fun `passo para frente no fim do livro e zero`() {
        assertEquals(0, regra.passoParaFrente(livro, 0, 1000))
        assertEquals(0, regra.passoParaFrente(emptyList(), 0, 100))
    }

    @Test
    fun `passo para frente com linha maior que a pagina avanca uma altura`() {
        val linhas = listOf(LinhaDaPagina(0, 40), LinhaDaPagina(40, 260), LinhaDaPagina(270, 300))
        // Janela de 50 a 200: a linha de 40 a 260 começa antes do topo e não cabe, então avança a altura (150).
        assertEquals(150, regra.passoParaFrente(linhas, 50, 200))
    }

    @Test
    fun `folhear o livro inteiro para a frente mostra todas as linhas`() {
        for (altura in listOf(150, 100)) {
            val (topoFinal, paginas) = folhearParaFrente(livro, altura)
            val uniao = paginas.flatten().toSet()
            assertEquals("altura $altura", livro.indices.toSet(), uniao)
            assertTrue("altura $altura: o fim do livro chegou", topoFinal > livro.first().topo)
        }
    }

    // Volta a partir de cada página da ida, com alturas que antes repetiam linhas (120 e 130). A página da volta
    // termina na linha antes da que estava no topo; nada se repete entre vizinhas; a ida depois da volta devolve
    // o topo.
    @Test
    fun `voltar a partir de cada pagina mostra a anterior inteira, sem repetir nem perder linha`() {
        for (altura in listOf(100, 120, 130, 150)) {
            for (topoDaIda in toposDaIda(livro, altura)) {
                val paginas = folhearParaTrasComFim(livro, altura, topoDaIda)
                val ultimaVista = paginas.first().linhas.maxOrNull() ?: -1
                val uniao = paginas.flatMap { it.linhas }.toSet()
                assertEquals("altura $altura, topo $topoDaIda: o livro sem buracos", (0..ultimaVista).toSet(), uniao)
                paginas.zipWithNext { acima, abaixo ->
                    val contexto = "altura $altura, topo $topoDaIda, volta de ${acima.topo} para ${abaixo.topo}"
                    assertTrue("$contexto: linha repetida", (acima.linhas intersect abaixo.linhas).isEmpty())
                    val linhaDeOndeSeVolta = livro.indexOfFirst { it.topo == acima.topo }
                    assertEquals("$contexto: termina na linha antes", linhaDeOndeSeVolta - 1, abaixo.linhas.maxOrNull())
                    assertEquals(
                        "$contexto: a ida depois da volta devolve o topo",
                        acima.topo - abaixo.topo,
                        regra.passoParaFrente(livro, abaixo.topo, abaixo.base),
                    )
                }
            }
        }
    }

    @Test
    fun `fim da volta para na linha de onde se voltou ou fica no fundo`() {
        assertEquals(150, regra.fimDaVolta(base = 200, topoDaSeguinte = 150))
        assertEquals(200, regra.fimDaVolta(base = 200, topoDaSeguinte = 300))
        assertEquals(200, regra.fimDaVolta(base = 200, topoDaSeguinte = null))
    }

    @Test
    fun `ajuste depois de voltar alinha o topo na primeira linha que comeca nele ou depois`() {
        val linhas = quatroLinhas
        assertEquals(20, regra.ajusteDepoisDeVoltar(linhas, 20))
        assertEquals(0, regra.ajusteDepoisDeVoltar(linhas, 40))
        assertEquals(0, regra.ajusteDepoisDeVoltar(linhas, 0))
        assertEquals(50, regra.ajusteDepoisDeVoltar(linhas, 100))
        assertEquals(0, regra.ajusteDepoisDeVoltar(emptyList(), 100))
    }

    @Test
    fun `ajuste do topo com linha cruzando devolve o delta negativo que a mostra inteira`() {
        val linhas = listOf(LinhaDaPagina(0, 40), LinhaDaPagina(40, 80), LinhaDaPagina(80, 120))
        assertEquals(-20, regra.ajusteDoTopo(linhas, 100, alturaDaPagina = 1000))
    }

    @Test
    fun `ajuste do topo sem linha cruzando e zero`() {
        val linhas = listOf(LinhaDaPagina(0, 40), LinhaDaPagina(40, 80), LinhaDaPagina(80, 120))
        assertEquals(0, regra.ajusteDoTopo(linhas, 80, alturaDaPagina = 1000))
        assertEquals(0, regra.ajusteDoTopo(linhas, 40, alturaDaPagina = 1000))
        assertEquals(0, regra.ajusteDoTopo(linhas, 0, alturaDaPagina = 1000))
    }

    @Test
    fun `ajuste do topo nao alinha linha tao alta quanto a pagina (virada sobre linha gigante)`() {
        // Linha de 40 a 400 (360 px) cruza o topo 100. Com página de 300 px, ela é maior que a página: fica como está.
        val linhas = listOf(LinhaDaPagina(0, 40), LinhaDaPagina(40, 400))
        assertEquals(0, regra.ajusteDoTopo(linhas, 100, alturaDaPagina = 300))
        // Com página de 500 px, a linha cabe: alinha como uma linha comum (40 - 100).
        assertEquals(-60, regra.ajusteDoTopo(linhas, 100, alturaDaPagina = 500))
    }

    @Test
    fun `passo da guiada vira quando a linha narrada esta escondida`() {
        // Linha de 300 a 340, corte em 320: escondida, então vira até ela ficar no topo (200).
        assertEquals(100, regra.passoDaGuiada(300, 340, 200, 320, podeSubir = true))
    }

    @Test
    fun `passo da guiada sobe quando a linha esta acima do topo e pode subir`() {
        // Linha de 150 a 190 acima do topo 200, e o corte (400) não a esconde: sobe 50.
        assertEquals(-50, regra.passoDaGuiada(150, 190, 200, 400, podeSubir = true))
    }

    @Test
    fun `passo da guiada nao sobe sem permissao (histerese)`() {
        assertEquals(0, regra.passoDaGuiada(150, 190, 200, 400, podeSubir = false))
    }

    @Test
    fun `passo da guiada sem nada a fazer e zero`() {
        assertEquals(0, regra.passoDaGuiada(250, 290, 200, 400, podeSubir = true))
    }

    @Test
    fun `deslize vertical passa do limiar para baixo e para cima`() {
        assertEquals(1, regra.deslizeVertical(0f, 200f, 100f))
        assertEquals(-1, regra.deslizeVertical(0f, -200f, 100f))
    }

    @Test
    fun `deslize vertical abaixo do limiar nao conta`() {
        assertEquals(0, regra.deslizeVertical(0f, 99f, 100f))
        assertEquals(1, regra.deslizeVertical(0f, 100f, 100f))
    }

    @Test
    fun `deslize mais horizontal que vertical nao conta`() {
        assertEquals(0, regra.deslizeVertical(300f, 200f, 100f))
        assertEquals(0, regra.deslizeVertical(150f, 150f, 100f))
        assertEquals(-1, regra.deslizeVertical(-50f, -150f, 100f))
    }
}
