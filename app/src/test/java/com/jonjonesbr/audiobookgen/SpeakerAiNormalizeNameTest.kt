package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.SpeakerAiPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAiNormalizeNameTest {
    private val narrador = OfflineSpeakerAttributor.AI_NARRATOR

    @Test
    fun `NARRADOR em caixa alta vira narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("NARRADOR"))
    }

    @Test
    fun `narrador em caixa baixa e com inicial maiúscula vira narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("narrador"))
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("Narrador"))
    }

    @Test
    fun `narrator em inglês vira narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("narrator"))
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("Narrator"))
    }

    @Test
    fun `narradora no feminino vira narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("Narradora"))
    }

    @Test
    fun `desconhecido e unknown viram narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("desconhecido"))
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("unknown"))
    }

    @Test
    fun `vazio e só espaços viram narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName(""))
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("   "))
    }

    @Test
    fun `narrador com espaços nas pontas continua narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("  narrador  "))
    }

    @Test
    fun `narrador com aspas ou ponto final continua narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("\"Narrador\""))
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("Narrador."))
    }

    @Test
    fun `comparação ignora acento e vira narrador`() {
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("Narrâdor"))
        assertEquals(narrador, SpeakerAiPlanner.normalizeName("ninguém"))
    }

    @Test
    fun `nomes de personagem são preservados`() {
        assertEquals("Ana", SpeakerAiPlanner.normalizeName("Ana"))
        assertEquals("João", SpeakerAiPlanner.normalizeName("João"))
    }

    @Test
    fun `espaços nas pontas do nome são removidos`() {
        assertEquals("Beto", SpeakerAiPlanner.normalizeName("  Beto  "))
    }

    @Test
    fun `espaços dentro das aspas são removidos`() {
        assertEquals("Ana", SpeakerAiPlanner.normalizeName("\" Ana \""))
    }

    @Test
    fun `nome com acento que não é narrador não vira narrador`() {
        assertEquals("Inês", SpeakerAiPlanner.normalizeName("Inês"))
        assertEquals("Gonçalo", SpeakerAiPlanner.normalizeName("Gonçalo"))
    }

    @Test
    fun `títulos e abreviações são mantidos`() {
        assertEquals("S. Paulo", SpeakerAiPlanner.normalizeName("S. Paulo"))
        assertEquals("Dona Inês", SpeakerAiPlanner.normalizeName("Dona Inês"))
    }

    @Test
    fun `ponto final no fim do nome é removido`() {
        assertEquals("Ana", SpeakerAiPlanner.normalizeName("Ana."))
    }
}
