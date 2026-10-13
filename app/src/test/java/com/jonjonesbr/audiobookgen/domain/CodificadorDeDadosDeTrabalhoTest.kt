package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodificadorDeDadosDeTrabalhoTest {

    @Test
    fun `ida e volta preserva texto, inteiros, longos e booleanos com o mesmo tipo`() {
        val original = mapOf<String, Any?>(
            "arquivo" to "/storage/livro.txt",
            "pausa_ms" to 600,
            "instante" to 9_000_000_000L,
            "legivel" to true
        )

        val voltou = CodificadorDeDadosDeTrabalho.deMapa(CodificadorDeDadosDeTrabalho.paraJson(original))

        assertEquals("/storage/livro.txt", voltou["arquivo"])
        assertEquals(600, voltou["pausa_ms"])
        assertEquals(9_000_000_000L, voltou["instante"])
        assertEquals(true, voltou["legivel"])
    }

    @Test
    fun `ida e volta preserva float e double sem perder precisão`() {
        val original = mapOf<String, Any?>("tom" to 1.1f, "velocidade" to 0.123456789012345)

        val voltou = CodificadorDeDadosDeTrabalho.deMapa(CodificadorDeDadosDeTrabalho.paraJson(original))

        assertEquals(1.1f, voltou["tom"])
        assertEquals(0.123456789012345, voltou["velocidade"])
    }

    @Test
    fun `valor nulo e ignorado na ida`() {
        val original = mapOf<String, Any?>("run_token" to null, "arquivo" to "a.txt")

        val voltou = CodificadorDeDadosDeTrabalho.deMapa(CodificadorDeDadosDeTrabalho.paraJson(original))

        assertFalse(voltou.containsKey("run_token"))
        assertEquals("a.txt", voltou["arquivo"])
    }

    @Test
    fun `tipo sem suporte e ignorado na ida`() {
        val original = mapOf<String, Any?>("lista" to listOf("x"), "arquivo" to "a.txt")

        val voltou = CodificadorDeDadosDeTrabalho.deMapa(CodificadorDeDadosDeTrabalho.paraJson(original))

        assertFalse(voltou.containsKey("lista"))
        assertEquals("a.txt", voltou["arquivo"])
    }

    @Test
    fun `semChavesDeSaida tira resultado e progresso de workers anteriores e mantem a entrada`() {
        val mapa = mapOf<String, Any?>(
            "result_caminho" to "/saida.mp3",
            "progress_pct" to 40,
            "voz" to "pf_dora",
            "run_token" to "abc"
        )

        val filtrado = CodificadorDeDadosDeTrabalho.semChavesDeSaida(mapa)

        assertEquals(mapOf<String, Any?>("voz" to "pf_dora", "run_token" to "abc"), filtrado)
    }

    @Test
    fun `json invalido devolve mapa vazio`() {
        assertTrue(CodificadorDeDadosDeTrabalho.deMapa("isto não é json {").isEmpty())
        assertTrue(CodificadorDeDadosDeTrabalho.deMapa("[1, 2]").isEmpty())
    }

    @Test
    fun `entrada com tipo desconhecido ou valor incompativel e descartada`() {
        val json = """{"a":{"t":"x","v":"1"},"b":{"t":"i","v":"nao-numero"},"c":{"t":"s","v":"ok"}}"""

        val voltou = CodificadorDeDadosDeTrabalho.deMapa(json)

        assertEquals(mapOf<String, Any>("c" to "ok"), voltou)
    }
}
