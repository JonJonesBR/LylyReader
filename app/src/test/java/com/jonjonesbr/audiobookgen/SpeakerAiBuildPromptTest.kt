package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.SpeakerAiPlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerAiBuildPromptTest {
    private val livro = listOf(
        "A noite caiu sobre a cidade.",
        "— Vamos embora — disse Ana.",
        "— Ainda não. Espere mais um pouco.",
        "Ela olhou o relógio.",
        "— Tudo bem — concordou Beto. — Mas é a última vez."
    )

    @Test
    fun `lote traz os parágrafos com fala marcados como CLASSIFICAR e numerados`() {
        val lote = SpeakerAiPlanner.planBatches(livro).single()
        val prompt = SpeakerAiPlanner.buildPrompt(livro, lote, emptyList())
        assertTrue(prompt.contains("[1] (CLASSIFICAR) "))
        assertTrue(prompt.contains("⟦1⟧Vamos embora⟦/1⟧ disse Ana."))
        assertTrue(prompt.contains("[2] (CLASSIFICAR)"))
        assertTrue(prompt.contains("⟦1⟧Ainda não."))
        assertTrue(prompt.contains("[4] (CLASSIFICAR)"))
        assertTrue(prompt.contains("⟦2⟧Mas é a última vez.⟦/2⟧"))
    }

    @Test
    fun `parágrafo de contexto aparece como contexto e sem marcação de fala`() {
        val lote = SpeakerAiPlanner.planBatches(livro).single()
        val prompt = SpeakerAiPlanner.buildPrompt(livro, lote, emptyList())
        assertTrue(prompt.contains("[0] (contexto) A noite caiu sobre a cidade."))
    }

    @Test
    fun `parágrafo sem fala fora do lote não entra no prompt`() {
        val lote = SpeakerAiPlanner.planBatches(livro).single()
        val prompt = SpeakerAiPlanner.buildPrompt(livro, lote, emptyList())
        assertFalse(prompt.contains("Ela olhou o relógio."))
        assertFalse(prompt.contains("[3]"))
    }

    @Test
    fun `só os parágrafos do lote entram, não os de fora`() {
        val comFora = livro.toMutableList().apply { add(3, "Frase FORA DO LOTE sem diálogo.") }
        val lote = SpeakerAiPlanner.Batch(classify = listOf(1), context = emptyList())
        val prompt = SpeakerAiPlanner.buildPrompt(comFora, lote, emptyList())
        assertTrue(prompt.contains("[1] (CLASSIFICAR)"))
        assertFalse(prompt.contains("FORA DO LOTE"))
        assertFalse(prompt.contains("[2]"))
        assertFalse(prompt.contains("[4]"))
    }

    @Test
    fun `inclui o elenco conhecido quando existe`() {
        val lote = SpeakerAiPlanner.Batch(classify = listOf(1), context = emptyList())
        val prompt = SpeakerAiPlanner.buildPrompt(livro, lote, listOf("Ana", "Beto"))
        assertTrue(prompt.contains("Personagens já conhecidos: Ana, Beto."))
        assertFalse(prompt.contains("(ainda nenhum)"))
    }

    @Test
    fun `elenco vazio diz que ainda não há personagens conhecidos`() {
        val lote = SpeakerAiPlanner.Batch(classify = listOf(1), context = emptyList())
        val prompt = SpeakerAiPlanner.buildPrompt(livro, lote, emptyList())
        assertTrue(prompt.contains("Personagens já conhecidos: (ainda nenhum)."))
    }

    @Test
    fun `lote com um único parágrafo e sem contexto`() {
        val lote = SpeakerAiPlanner.Batch(classify = listOf(1), context = emptyList())
        val prompt = SpeakerAiPlanner.buildPrompt(livro, lote, emptyList())
        assertTrue(prompt.contains("[1] (CLASSIFICAR)"))
        assertFalse(prompt.contains("] (contexto) "))
    }

    @Test
    fun `diálogo com travessão e aspas marca só a fala`() {
        val paragrafos = listOf("— Bom dia, disse S. Paulo.")
        val lote = SpeakerAiPlanner.Batch(classify = listOf(0), context = emptyList())
        val prompt = SpeakerAiPlanner.buildPrompt(paragrafos, lote, emptyList())
        assertTrue(prompt.contains("⟦1⟧Bom dia"))
        assertTrue(prompt.contains("[0] (CLASSIFICAR)"))
        assertTrue(prompt.contains("S. Paulo."))
    }

    @Test
    fun `nomes com acento aparecem intactos no elenco`() {
        val lote = SpeakerAiPlanner.Batch(classify = listOf(1), context = emptyList())
        val prompt = SpeakerAiPlanner.buildPrompt(livro, lote, listOf("Inês", "Gonçalo"))
        assertTrue(prompt.contains("Personagens já conhecidos: Inês, Gonçalo."))
    }
}
