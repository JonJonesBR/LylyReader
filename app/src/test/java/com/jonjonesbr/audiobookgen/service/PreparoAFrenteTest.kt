package com.jonjonesbr.audiobookgen.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparoAFrenteTest {

    private val unidades = listOf(5, 5, 5, 6, 6, 7, 8)

    // ── proximaUnidade ────────────────────────────────────────────────────────

    @Test
    fun semLeituraDevolveOCursor() {
        assertEquals(0, PreparoAFrente.proximaUnidade(unidades, 0, -1))
        assertEquals(3, PreparoAFrente.proximaUnidade(unidades, 3, -1))
    }

    @Test
    fun leituraAntesDaFaixaDevolveOCursor() {
        // Leitura no parágrafo 2 não cobre o 5 (2 + 1 = 3 < 5): a primeira unidade já é a do cursor.
        assertEquals(0, PreparoAFrente.proximaUnidade(unidades, 0, 2))
    }

    @Test
    fun pulaAsUnidadesCobertasPelaLeitura() {
        // Leitura no 5: cobre até o 6. O 7 é o primeiro livre, que é a unidade de índice 5.
        assertEquals(5, PreparoAFrente.proximaUnidade(unidades, 0, 5))
    }

    @Test
    fun leituraNoSeguinteAvancaOCursor() {
        // Leitura no 6: cobre até o 7. O 8 é o primeiro livre, de índice 6.
        assertEquals(6, PreparoAFrente.proximaUnidade(unidades, 0, 6))
    }

    @Test
    fun leituraAlemDoFimDevolveOTamanho() {
        assertEquals(unidades.size, PreparoAFrente.proximaUnidade(unidades, 0, 100))
    }

    @Test
    fun cursorAlemDoLimiteDevolveOCursor() {
        assertEquals(9, PreparoAFrente.proximaUnidade(unidades, 9, 100))
    }

    @Test
    fun listaVaziaDevolveZero() {
        assertEquals(0, PreparoAFrente.proximaUnidade(emptyList(), 0, 0))
    }

    @Test
    fun naoVoltaParaTrasDoCursor() {
        // Pulo para trás da leitura (no 0) não faz o preparo voltar: segue do cursor 4.
        assertEquals(4, PreparoAFrente.proximaUnidade(unidades, 4, 0))
    }

    @Test
    fun semLeituraAoVivoNaoPulaOEmLeituraNemOSeguinte() {
        // Pausada em 5: o preparo começa no 5 (P) e não pula P nem P+1, que ninguém está sintetizando.
        assertEquals(0, PreparoAFrente.proximaUnidade(unidades, 0, 5, PreparoAFrente.janelaDaLeitura(false)))
    }

    @Test
    fun semLeituraAoVivoPulaSoOQueFicouParaTras() {
        // Pausada em 6 com cursor 0: a unidade do 5 ficou para trás; o 6 (P) já é do preparo.
        assertEquals(3, PreparoAFrente.proximaUnidade(unidades, 0, 6, PreparoAFrente.janelaDaLeitura(false)))
    }

    @Test
    fun janelaAoVivoEhAPadrao() {
        assertEquals(PreparoAFrente.JANELA_DA_LEITURA, PreparoAFrente.janelaDaLeitura(true))
        assertEquals(PreparoAFrente.JANELA_SEM_LEITURA, PreparoAFrente.janelaDaLeitura(false))
    }

    // ── ficouParaALeitura ─────────────────────────────────────────────────────

    @Test
    fun ficouParaALeituraNoLimiteDaJanela() {
        assertTrue(PreparoAFrente.ficouParaALeitura(5, 5))   // o próprio parágrafo em leitura
        assertTrue(PreparoAFrente.ficouParaALeitura(6, 5))   // um depois (janela)
        assertFalse(PreparoAFrente.ficouParaALeitura(7, 5))  // dois depois: fora da janela
    }

    @Test
    fun ficouParaALeituraSemLeituraEhFalso() {
        assertFalse(PreparoAFrente.ficouParaALeitura(0, -1))
    }

    // ── esperaALeitura ────────────────────────────────────────────────────────

    private fun espera(lento: Boolean, tocando: Boolean, sintetizando: Boolean, pausada: Boolean) =
        PreparoAFrente.esperaALeitura(lento, tocando, sintetizando, pausada)

    @Test
    fun pausadaNaoEsperaMesmoSintetizando() {
        assertFalse(espera(lento = true, tocando = false, sintetizando = true, pausada = true))
        assertFalse(espera(lento = false, tocando = false, sintetizando = true, pausada = true))
    }

    @Test
    fun leituraSintetizandoFazEsperar() {
        assertTrue(espera(lento = false, tocando = false, sintetizando = true, pausada = false))
    }

    @Test
    fun motorLentoEsperaEnquantoToca() {
        assertTrue(espera(lento = true, tocando = true, sintetizando = false, pausada = false))
    }

    @Test
    fun motorRapidoNaoEsperaSoPorTocar() {
        assertFalse(espera(lento = false, tocando = true, sintetizando = false, pausada = false))
    }

    @Test
    fun semNadaNaoEspera() {
        assertFalse(espera(lento = true, tocando = false, sintetizando = false, pausada = false))
    }
}
