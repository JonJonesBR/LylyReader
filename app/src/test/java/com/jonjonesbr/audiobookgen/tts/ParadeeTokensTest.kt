package com.jonjonesbr.audiobookgen.tts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParadeeTokensTest {

    private fun golden(): JSONArray {
        val fluxo = javaClass.classLoader!!.getResourceAsStream("paradee/golden.json")!!
        return JSONArray(fluxo.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    /** Fonemizador falso: devolve o `ipa_cru` gravado pelo bench para cada trecho. */
    private fun fonemizarDoGolden(caso: JSONObject): (String) -> String {
        val mapa = HashMap<String, String>()
        val partes = caso.getJSONArray("partes")
        for (i in 0 until partes.length()) {
            val p = partes.getJSONObject(i)
            if (p.getString("tipo") == "trecho") mapa[p.getString("texto")] = p.getString("ipa_cru")
        }
        return { trecho -> mapa[trecho] ?: error("trecho fora do golden: '$trecho'") }
    }

    private fun idsDoJson(arr: JSONArray): LongArray = LongArray(arr.length()) { arr.getLong(it) }

    @Test
    fun `blocos batem com o golden do bench`() {
        val casos = golden()
        assertTrue(casos.length() > 0)
        for (i in 0 until casos.length()) {
            val caso = casos.getJSONObject(i)
            val blocos = ParadeeTokens.blocos(caso.getString("texto"), fonemizarDoGolden(caso))
            assertEquals("caso $i", 1, blocos.size)
            assertArrayEquals("caso $i", idsDoJson(caso.getJSONArray("ids")), blocos[0].ids)
        }
    }

    @Test
    fun `pecas concatenadas sem espacos das pontas batem com o golden`() {
        val casos = golden()
        for (i in 0 until casos.length()) {
            val caso = casos.getJSONObject(i)
            val concatenado = ParadeeTokens.pecas(caso.getString("texto"), fonemizarDoGolden(caso))
                .flatMap { it.ids }
                .dropWhile { it == 16L }
                .dropLastWhile { it == 16L }
            val esperado = idsDoJson(caso.getJSONArray("ids")).drop(1).dropLast(1)
            assertEquals("caso $i", esperado, concatenado)
        }
    }

    @Test
    fun `aplicarE2M troca o digrafo por ʧ e tira o circunflexo`() {
        assertEquals("ʧa", ParadeeTokens.aplicarE2M("t^ʃa"))
        assertEquals("I", ParadeeTokens.aplicarE2M("a^ɪ"))
        assertEquals("ab", ParadeeTokens.aplicarE2M("a^b"))
    }

    @Test
    fun `limparSaidaEspeak remove marca de idioma e normaliza espacos`() {
        assertEquals("olˈa b", ParadeeTokens.limparSaidaEspeak("olˈa (en) b\r\n"))
        assertEquals("ab", ParadeeTokens.limparSaidaEspeak("a(pt-br)b"))
    }

    @Test
    fun `frase gigante vira blocos de no maximo 512 com pads`() {
        val texto = "casa ".repeat(400) + "fim."
        val blocos = ParadeeTokens.blocos(texto, { it })
        assertTrue(blocos.size > 1)
        for (b in blocos) {
            assertTrue(b.ids.size <= 512)
            assertEquals(0L, b.ids.first())
            assertEquals(0L, b.ids.last())
        }
    }

    @Test
    fun `pausa de 100 ms dentro da frase e 300 ms no fim da frase`() {
        // Limite 8: "casa" (4), "," (1), " casa" (5), "," (1), " casa" (5), "." (1) não cabem numa só leva.
        val blocos = ParadeeTokens.blocos("casa, casa, casa.", { it }, limite = 8)
        assertEquals(listOf(100, 100, 300), blocos.map { it.pausaDepoisMs })
    }

    @Test
    fun `so pontuacao devolve lista vazia`() {
        assertTrue(ParadeeTokens.blocos("...", { it }).isEmpty())
        assertTrue(ParadeeTokens.blocos("?!", { it }).isEmpty())
    }

    @Test
    fun `juntarComPausa soma audios e silencios`() {
        val juntado = ParadeeTokens.juntarComPausa(
            listOf(FloatArray(100) { 0.5f }, FloatArray(50) { 0.25f }),
            listOf(300),
            24000,
        )
        // 300 ms a 24 kHz = 7200 amostras entre os dois áudios.
        assertEquals(100 + 7200 + 50, juntado.size)
        assertEquals(0f, juntado[100], 0f)
        assertEquals(0.25f, juntado.last(), 0f)
    }
}
