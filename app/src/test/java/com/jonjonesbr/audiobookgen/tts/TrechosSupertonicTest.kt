package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrechosSupertonicTest {

    private val longo = "Ela atravessou a praça devagar, olhando as vitrines apagadas, as portas fechadas, " +
        "os bancos vazios e a fonte que já não jorrava água havia anos; lembrou-se então do pai, " +
        "que a levava ali todos os domingos. Depois voltou para casa. Não disse nada a ninguém."

    @Test fun `nenhum trecho passa do limite e nenhum texto se perde`() {
        val trechos = TrechosSupertonic.dividir(longo)
        assertTrue(trechos.size > 1)
        assertTrue(trechos.all { it.length <= TrechosSupertonic.MAX_CHARS })
        assertEquals(longo.split(Regex("\\s+")), trechos.joinToString(" ").split(Regex("\\s+")))
    }

    @Test fun `frase longa e cortada depois de virgula ou ponto e virgula`() {
        val trechos = TrechosSupertonic.dividir(longo)
        assertTrue(trechos.first().last() in ",;")
    }

    @Test fun `texto curto fica num trecho so`() {
        assertEquals(listOf("Olá. Tudo bem?"), TrechosSupertonic.dividir("Olá. Tudo bem?"))
    }

    @Test fun `ritmo natural nao refaz`() {
        // 60 letras em 5 s = 12 letras/s
        assertNull(TrechosSupertonic.velocidadeParaRefazer("a".repeat(60), 5f, 1f))
    }

    @Test fun `fala comprimida refaz mais devagar`() {
        // 100 letras em 4 s = 25 letras/s: o modelo pulou palavras
        val nova = TrechosSupertonic.velocidadeParaRefazer("a".repeat(100), 4f, 1f)
        assertNotNull(nova)
        assertEquals(14f / 25f, nova!!, 0.001f)
    }

    @Test fun `velocidade escolhida pelo usuario e respeitada na medida`() {
        // Na velocidade 1.5, 27 letras/s equivalem a 18 na velocidade 1.0: ainda plausível
        assertNull(TrechosSupertonic.velocidadeParaRefazer("a".repeat(108), 4f, 1.5f))
    }

    @Test fun `nunca cai abaixo da metade da velocidade`() {
        val nova = TrechosSupertonic.velocidadeParaRefazer("a".repeat(200), 2f, 1f)
        assertEquals(0.5f, nova!!, 0.001f)
    }

    @Test fun `trecho curto ou audio vazio nao refaz`() {
        assertNull(TrechosSupertonic.velocidadeParaRefazer("Sim.", 0.1f, 1f))
        assertNull(TrechosSupertonic.velocidadeParaRefazer("a".repeat(60), 0f, 1f))
    }

    @Test fun `com ritmo tipico, compressao abaixo do teto fixo tambem refaz`() {
        // Voz que fala 13 letras/s: 80 letras em 5 s (16 letras/s) é 23% mais rápido que o habitual
        val nova = TrechosSupertonic.velocidadeParaRefazer("a".repeat(80), 5f, 1f, ritmoTipico = 13f)
        assertNotNull(nova)
        assertEquals(13f / 16f, nova!!, 0.001f)
    }

    @Test fun `com ritmo tipico, variacao pequena nao refaz`() {
        // 75 letras em 5 s = 15 letras/s, 15% acima de 13: dentro da folga
        assertNull(TrechosSupertonic.velocidadeParaRefazer("a".repeat(75), 5f, 1f, ritmoTipico = 13f))
    }

    @Test fun `ritmo tipico nunca afrouxa o teto fixo`() {
        // Voz rápida (17 letras/s): o limite fica em 18, não em 17 × 1,18
        assertNotNull(TrechosSupertonic.velocidadeParaRefazer("a".repeat(95), 5f, 1f, ritmoTipico = 17f))
    }

    @Test fun `ritmo da voz so vale depois de cinco amostras e usa a mediana`() {
        RitmoDaVoz.limpar()
        listOf(12f, 13f, 30f, 14f).forEach { RitmoDaVoz.registrar("supertonic-f4-pt", it) }
        assertNull(RitmoDaVoz.tipico("supertonic-f4-pt"))
        RitmoDaVoz.registrar("supertonic-f4-pt", 13.5f)
        assertEquals(13.5f, RitmoDaVoz.tipico("supertonic-f4-pt")!!, 0.001f)
        assertNull(RitmoDaVoz.tipico("supertonic-m1-pt"))
        RitmoDaVoz.limpar()
    }
}
