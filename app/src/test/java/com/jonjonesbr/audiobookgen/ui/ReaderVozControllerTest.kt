package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderVozControllerTest {

    private fun paragrafos(quantos: Int): List<Paragrafo> =
        List(quantos) { i -> Paragrafo(texto = "p$i", charOffset = i, indiceCapitulo = 0) }

    private fun comTextos(vararg textos: String): List<Paragrafo> =
        textos.mapIndexed { i, t -> Paragrafo(texto = t, charOffset = i, indiceCapitulo = 0) }

    // ── faixaDoPreparo ────────────────────────────────────────────────────────

    @Test
    fun semCapituloPreparaCentoEVinte() {
        assertEquals(10 to 130, faixaDoPreparo(paragrafos(500), emptyList(), 10))
    }

    @Test
    fun fimLimitadoAoUltimo() {
        assertEquals(0 to 49, faixaDoPreparo(paragrafos(50), emptyList(), 0))
    }

    @Test
    fun inicioForaDaListaVaiParaOsLimites() {
        assertEquals(4 to 4, faixaDoPreparo(paragrafos(5), emptyList(), 99))
        assertEquals(0 to 4, faixaDoPreparo(paragrafos(5), emptyList(), -3))
    }

    @Test
    fun capituloDefineOFim() {
        val capitulos = listOf(Capitulo("A", 0, 3), Capitulo("B", 4, 9))
        val lista = List(10) { i -> Paragrafo(texto = "p$i", charOffset = i, indiceCapitulo = if (i < 4) 0 else 1) }
        assertEquals(5 to 9, faixaDoPreparo(lista, capitulos, 5))
        assertEquals(0 to 3, faixaDoPreparo(lista, capitulos, 0))
    }

    // ── textoDaAmostra ────────────────────────────────────────────────────────

    @Test
    fun amostraJuntaTresParagrafos() {
        assertEquals("a b c", textoDaAmostra(comTextos("a", "b", "c", "d", "e"), inicio = 0))
    }

    @Test
    fun comecaNoTrechoAtual() {
        assertEquals("c d e", textoDaAmostra(comTextos("a", "b", "c", "d", "e"), inicio = 2))
    }

    @Test
    fun puloParagrafosEmBrancoAoComecar() {
        assertEquals("c d e", textoDaAmostra(comTextos("a", " ", "c", "", "d", "e"), inicio = 1))
    }

    @Test
    fun inicioNoFimDaListaJuntaSoOUltimo() {
        assertEquals("e", textoDaAmostra(comTextos("a", "b", "c", "d", "e"), inicio = 4))
    }

    @Test
    fun amostraInicioForaDaListaVaiParaOsLimites() {
        assertEquals("e", textoDaAmostra(comTextos("a", "b", "c", "d", "e"), inicio = 99))
        assertEquals("a b c", textoDaAmostra(comTextos("a", "b", "c", "d", "e"), inicio = -3))
    }

    @Test
    fun amostraVaziaDaNulo() {
        assertNull(textoDaAmostra(null, inicio = 0))
        assertNull(textoDaAmostra(emptyList(), inicio = 0))
        assertNull(textoDaAmostra(comTextos("  ", " "), inicio = 0))
        assertNull(textoDaAmostra(comTextos("a", "  "), inicio = 1))
    }

    // ── amostra curta (rb-40) ─────────────────────────────────────────────────

    @Test
    fun paragrafoCurtoVaiInteiro() {
        assertEquals("Capitulo primeiro", textoDaAmostra(comTextos("Capitulo primeiro"), inicio = 0))
        val exato = "a".repeat(300)
        assertEquals(exato, textoDaAmostra(comTextos(exato), inicio = 0))
    }

    @Test
    fun paragrafoLongoCortaNoFimDaFrase() {
        val s1 = "Esta e a primeira frase."
        val s2 = " Esta e a segunda frase, um pouco mais comprida."
        // O texto passa de 300: o corte cai no último ponto que cabe, antes das palavras longas.
        val s3 = " " + "b".repeat(240) + "."
        assertEquals(
            "Esta e a primeira frase. Esta e a segunda frase, um pouco mais comprida.",
            textoDaAmostra(comTextos(s1 + s2 + s3), inicio = 0),
        )
    }

    @Test
    fun semFimDeFraseCortaNoEspacoComReticencias() {
        val longo = "abc ".repeat(100)
        assertEquals("abc ".repeat(74) + "abc…", textoDaAmostra(comTextos(longo), inicio = 0))
    }

    @Test
    fun juntaParagrafosAteOLimite() {
        assertEquals("Um. Dois. Tres.", textoDaAmostra(comTextos("Um.", "Dois.", "Tres."), inicio = 0))
        // O terceiro parágrafo não cabe: a amostra fica com os dois primeiros, cortados no ponto final.
        val p1 = "Primeiro paragrafo."
        val p2 = "b".repeat(150) + "."
        val p3 = "c".repeat(200) + "."
        assertEquals(p1 + " " + p2, textoDaAmostra(comTextos(p1, p2, p3), inicio = 0))
    }

    // ── corte no fim de frase (rb-47) ──────────────────────────────────────

    @Test
    fun abreviacaoNaoFechaFrase() {
        // O corte de 12 caracteres cairia em "Dr.": o ponto não conta, então vai no espaço.
        assertEquals("O Dr. Silva…", cortarNoFimDeFrase("O Dr. Silva chegou. Depois disso.", 12))
        // Com janela maior, o ponto de "chegou." é o fim de frase, e o de "Dr." não corta antes.
        assertEquals("O Dr. Silva chegou.", cortarNoFimDeFrase("O Dr. Silva chegou. Depois disso.", 25))
    }

    @Test
    fun abreviacaoSemDiferencaDeCaixa() {
        assertEquals("O sr. Lima…", cortarNoFimDeFrase("O sr. Lima chegou. Fim", 15))
    }

    @Test
    fun pontoEntreDigitosNaoFechaFrase() {
        assertEquals("Use 3.5 litros…", cortarNoFimDeFrase("Use 3.5 litros de agua, nao mais que isso.", 15))
        assertEquals("Custa 1.000…", cortarNoFimDeFrase("Custa 1.000 reais e nao e pouco dinheiro", 12))
    }

    @Test
    fun reticenciasFechamFrase() {
        assertEquals("Parei…", cortarNoFimDeFrase("Parei… e fui embora", 9))
        assertEquals("Parei...", cortarNoFimDeFrase("Parei... e fui embora", 9))
    }

    @Test
    fun interrogacaoEExclamacaoFechamFrase() {
        assertEquals("Voce viu?", cortarNoFimDeFrase("Voce viu? Sim! Depois disso tudo mudou muito.", 12))
        assertEquals("Sim!", cortarNoFimDeFrase("Sim! Nao e assim que se faz", 5))
    }
}
