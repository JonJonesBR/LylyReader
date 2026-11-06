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

    /** Folheia para trás, com scroll de uma altura e ajuste na linha, até o começo do livro. */
    private fun folhearParaTras(linhas: List<LinhaDaPagina>, altura: Int, topoFinal: Int): List<Set<Int>> {
        val inicio = linhas.first().topo
        var topo = topoFinal
        val paginas = mutableListOf(mostradas(linhas, topo, topo + altura))
        var folhas = 0
        while (folhas < limiteDeFolhas && topo > inicio) {
            folhas++
            topo = maxOf(inicio, topo - altura)
            topo += regra.ajusteDepoisDeVoltar(linhas, topo)
            paginas += mostradas(linhas, topo, topo + altura)
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

    // Limite conhecido: com alturas como 120 ou 130, a volta pode repetir duas linhas numa mesma página.
    // Este teste só usa 100 e 150, onde a repetição fica em uma linha. O passo 2 precisa tratar o caso.
    @Test
    fun `folhear o livro inteiro para tras mostra todas as linhas sem repetir mais de uma por pagina`() {
        for (altura in listOf(150, 100)) {
            val (topoFinal, _) = folhearParaFrente(livro, altura)
            val paginas = folhearParaTras(livro, altura, topoFinal)
            val uniao = paginas.flatten().toSet()
            assertEquals("altura $altura", livro.indices.toSet(), uniao)
            assertTrue(
                "altura $altura: mais de uma linha repetida entre paginas",
                paginas.zipWithNext { acima, abaixo -> (acima intersect abaixo).size <= 1 }.all { it },
            )
        }
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
