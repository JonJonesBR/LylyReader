package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationAiTest {

    private val livro = listOf(
        "Kvothe olhou para a estrada. Depois Kvothe falou com Denna.",
        "— Você viu Denna? — perguntou Kvothe.",
        "A Terra girava. Ele amava a terra e a Escola.",
        "Bast riu. Escola de magia, disse Bast. E Kvothe sorriu para Bast."
    )

    @Test
    fun `candidatos sao nomes frequentes no meio de frases`() {
        val nomes = PronunciationAiPlanner.candidates(livro, PronunciationDictionary.EMPTY).map { it.word }
        assertTrue("Kvothe" in nomes)
        assertTrue("Denna" in nomes)
        assertTrue("Bast" in nomes)
    }

    @Test
    fun `palavras que tambem aparecem em minuscula nao sao candidatas`() {
        val nomes = PronunciationAiPlanner.candidates(livro, PronunciationDictionary.EMPTY).map { it.word }
        assertFalse("Terra" in nomes)
    }

    @Test
    fun `nome ja no dicionario nao volta como candidato`() {
        val dic = PronunciationDictionary(listOf(PronunciationEntry("Kvothe", "Cuvouth")))
        val nomes = PronunciationAiPlanner.candidates(livro, dic).map { it.word }
        assertFalse("Kvothe" in nomes)
        assertTrue("Denna" in nomes)
    }

    @Test
    fun `mais frequente vem primeiro`() {
        val nomes = PronunciationAiPlanner.candidates(livro, PronunciationDictionary.EMPTY)
        assertEquals("Kvothe", nomes.first().word)
        assertEquals(3, nomes.first().count)
    }

    @Test
    fun `resposta em lista com cerca de codigo`() {
        val raw = "```json\n[{\"nome\":\"Kvothe\",\"falado\":\"Cuvouth\"},{\"nome\":\"Denna\",\"falado\":\"Dena\"}]\n```"
        assertEquals(mapOf("Kvothe" to "Cuvouth", "Denna" to "Dena"), PronunciationAiPlanner.parseResponse(raw))
    }

    @Test
    fun `resposta em objeto`() {
        assertEquals(mapOf("Bast" to "Bést"), PronunciationAiPlanner.parseResponse("{\"Bast\": \"Bést\"}"))
    }

    @Test
    fun `resposta lixo vira vazio`() {
        assertTrue(PronunciationAiPlanner.parseResponse("não consegui").isEmpty())
    }

    @Test
    fun `sugestao igual ou estranha nao serve`() {
        assertFalse(PronunciationAiPlanner.isUsable("Pedro", "pedro"))
        assertFalse(PronunciationAiPlanner.isUsable("Bast", "Bast é um personagem muito longo que a IA explicou"))
        assertFalse(PronunciationAiPlanner.isUsable("Bast", "/bæst/"))
        assertTrue(PronunciationAiPlanner.isUsable("Kvothe", "Cuvouth"))
    }

    @Test
    fun `lotes de ate 40 nomes`() {
        val lotes = PronunciationAiPlanner.batches((1..95).map { "N$it" })
        assertEquals(listOf(40, 40, 15), lotes.map { it.size })
    }

    @Test
    fun `importa varios formatos de linha`() {
        val texto = """
            # comentário
            Kvothe=Cuvouth
            Denna;Dena
            Bast, Bést
            Tehlu → Tê-lu
            Sheerin	Chirin
        """.trimIndent()
        val entradas = PronunciationTransfer.parse(texto)
        assertEquals(
            listOf("Kvothe" to "Cuvouth", "Denna" to "Dena", "Bast" to "Bést", "Tehlu" to "Tê-lu", "Sheerin" to "Chirin"),
            entradas.map { it.word to it.spoken }
        )
    }

    @Test
    fun `importa json em lista e em objeto`() {
        assertEquals(
            listOf("A" to "a1"),
            PronunciationTransfer.parse("[{\"word\":\"A\",\"spoken\":\"a1\"}]").map { it.word to it.spoken }
        )
        assertEquals(
            listOf("B" to "b1"),
            PronunciationTransfer.parse("{\"B\":\"b1\"}").map { it.word to it.spoken }
        )
    }

    @Test
    fun `exporta e reimporta sem perder nada`() {
        val dic = PronunciationDictionary(listOf(PronunciationEntry("Kvothe", "Cuvouth"), PronunciationEntry("Denna", "Dena")))
        val volta = PronunciationTransfer.parse(PronunciationTransfer.export(dic))
        assertEquals(dic.entries.map { it.word to it.spoken }.toSet(), volta.map { it.word to it.spoken }.toSet())
    }

    @Test
    fun `importar substitui a mesma palavra`() {
        val atual = PronunciationDictionary(listOf(PronunciationEntry("Kvothe", "velho")))
        val junto = PronunciationTransfer.merge(atual, listOf(PronunciationEntry("kvothe", "novo")))
        assertEquals("novo", junto.find("Kvothe")?.spoken)
    }

    @Test
    fun `endereco compativel com openai`() {
        assertEquals("https://x.dev/v1/chat/completions", OpenAiStyleClient.endpointFromBase("https://x.dev/v1/"))
        assertEquals("https://x.dev/v1/chat/completions", OpenAiStyleClient.endpointFromBase("https://x.dev/v1/chat/completions"))
    }

    @Test
    fun `resposta vazia do pollinations conta como falha temporaria`() {
        val erro = runCatching { OpenAiStyleClient.extractContent("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}") }
            .exceptionOrNull() as AiApiException
        assertFalse(erro.fatal)
        assertEquals("ok", OpenAiStyleClient.extractContent("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"))
    }
}
