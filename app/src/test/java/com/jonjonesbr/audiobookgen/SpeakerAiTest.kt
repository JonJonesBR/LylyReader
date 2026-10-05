package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.GeminiSpeakerClient
import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.SpeakerAiPlanner
import com.jonjonesbr.audiobookgen.domain.SpeakerAiStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpeakerAiTest {
    private val livro = listOf(
        "A noite caiu sobre a cidade.",
        "— Vamos embora — disse Ana.",
        "— Ainda não. Espere mais um pouco.",
        "Ela olhou o relógio.",
        "— Tudo bem — concordou Beto. — Mas é a última vez."
    )

    @Test
    fun `separa as falas em partes numeradas a partir de 1`() {
        val partes = OfflineSpeakerAttributor.partsOf(livro[4])
        assertEquals(listOf(1, 2), partes.filter { it.speech }.map { it.ordinal })
        assertEquals(listOf("Tudo bem", "Mas é a última vez."), partes.filter { it.speech }.map { it.text })
    }

    @Test
    fun `so parágrafos com fala entram nos lotes e ganham contexto`() {
        assertEquals(listOf(1, 2, 4), SpeakerAiPlanner.dialogueParagraphs(livro))
        val lotes = SpeakerAiPlanner.planBatches(livro)
        assertEquals(1, lotes.size)
        assertEquals(listOf(1, 2, 4), lotes.single().classify)
        assertEquals(listOf(0), lotes.single().context)
    }

    @Test
    fun `lotes pulam parágrafos já analisados`() {
        val lotes = SpeakerAiPlanner.planBatches(livro, skip = setOf(1, 2))
        assertEquals(listOf(4), lotes.single().classify)
    }

    @Test
    fun `lotes grandes são divididos`() {
        val muitos = List(70) { "— Fala número $it — disse Ana." }
        assertTrue(SpeakerAiPlanner.planBatches(muitos).size >= 3)
    }

    @Test
    fun `texto marcado numera cada fala do parágrafo`() {
        val marcado = SpeakerAiPlanner.markedText(livro[4])
        assertTrue(marcado.contains("⟦1⟧Tudo bem⟦/1⟧"))
        assertTrue(marcado.contains("⟦2⟧Mas é a última vez.⟦/2⟧"))
    }

    @Test
    fun `lê a resposta da IA mesmo com cerca de código e normaliza narrador`() {
        val resposta = "```json\n[{\"p\":1,\"n\":1,\"quem\":\"Ana\"},{\"p\":2,\"n\":1,\"quem\":\"NARRADOR\"}," +
            "{\"p\":4,\"n\":2,\"quem\":\"Beto.\"},{\"p\":9,\"n\":0,\"quem\":\"X\"}]\n```"
        val lidas = SpeakerAiPlanner.parseResponse(resposta)
        assertEquals("Ana", lidas[1 to 1])
        assertEquals(OfflineSpeakerAttributor.AI_NARRATOR, lidas[2 to 1])
        assertEquals("Beto", lidas[4 to 2])
        assertEquals(3, lidas.size)
    }

    @Test
    fun `resposta quebrada não derruba`() {
        assertTrue(SpeakerAiPlanner.parseResponse("desculpe, não consegui").isEmpty())
        assertTrue(SpeakerAiPlanner.parseResponse("[{]").isEmpty())
    }

    @Test
    fun `correções da IA têm prioridade e criam personagens`() {
        val local = OfflineSpeakerAttributor.analyze(livro)
        assertTrue(local.paragraphs[2].segments.none { it.speakerId.startsWith("character:") })
        val comIa = OfflineSpeakerAttributor.analyze(
            livro,
            aiOverrides = mapOf((2 to 1) to "Beto", (1 to 1) to "Ana", (4 to 2) to OfflineSpeakerAttributor.AI_NARRATOR)
        )
        assertEquals("character:beto", comIa.paragraphs[2].segments.first().speakerId)
        assertTrue(comIa.speakers.any { it.name == "Ana" })
        assertEquals("narrator", comIa.paragraphs[4].segments.last().speakerId)
    }

    @Test
    fun `elenco dos próximos lotes junta nomes sem repetir`() {
        val elenco = SpeakerAiPlanner.castFrom(
            mapOf((1 to 1) to "Ana", (2 to 1) to "ana", (3 to 1) to OfflineSpeakerAttributor.AI_NARRATOR),
            seed = listOf("Beto")
        )
        assertEquals(listOf("Beto", "Ana"), elenco)
    }

    @Test
    fun `guarda e relê o andamento por livro e conteúdo`() {
        val pasta = File(System.getProperty("java.io.tmpdir"), "speaker-ai-test-${System.nanoTime()}")
        try {
            val store = SpeakerAiStore(pasta)
            val estado = SpeakerAiStore.State(mapOf((1 to 1) to "Ana"), setOf(1, 2))
            assertTrue(store.save("livro.epub", livro, estado))
            assertEquals(estado, store.load("livro.epub", livro))
            // outro texto → começa do zero
            assertTrue(store.load("livro.epub", livro + "novo").labels.isEmpty())
        } finally {
            pasta.deleteRecursively()
        }
    }

    @Test
    fun `modelo escolhido vai primeiro e sem duplicar o prefixo models`() {
        val cliente = GeminiSpeakerClient(listOf("k"), "models/meu-modelo")
        assertEquals("meu-modelo", cliente.modelOrder.first())
        assertTrue(cliente.modelOrder.containsAll(GeminiSpeakerClient.AUTO_MODELS))
        assertEquals(GeminiSpeakerClient.AUTO_MODELS, GeminiSpeakerClient(listOf("k")).modelOrder)
        val repetido = GeminiSpeakerClient(listOf("k"), GeminiSpeakerClient.AUTO_MODELS[2])
        assertEquals(GeminiSpeakerClient.AUTO_MODELS[2], repetido.modelOrder.first())
        assertEquals(GeminiSpeakerClient.AUTO_MODELS.size, repetido.modelOrder.size)
    }

    @Test
    fun `lista de modelos mostra lite primeiro, depois flash, pro por ultimo`() {
        val ordenada = GeminiSpeakerClient.sortModels(
            listOf("gemini-3.1-pro-preview", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash")
        )
        assertEquals(
            listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.1-pro-preview"),
            ordenada
        )
    }

    @Test
    fun `chaves são separadas por linha, vírgula ou ponto e vírgula`() {
        assertEquals(listOf("a", "b", "c"), GeminiSpeakerClient.parseKeys("a\nb, c;"))
    }
}
