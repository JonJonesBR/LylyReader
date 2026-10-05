package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * Testes das funções puras do KokoroTtsEngine (V6 — Base).
 * O engine em si depende de android/nativo (sessão ORT) — não testável em JVM puro;
 * as regras de negócio puras (mapeamento voz→sid/lang, chunking, writer WAV) são.
 */
class KokoroTtsEngineTest {

    // ── RN-1: lang explícito por voz (nunca "pt" genérico) ───────────────────────────────

    @Test
    fun `voze pt-br mapeiam para sid e lang pt-BR explicitos`() {
        assertEquals(KokoroVoiceParams(sid = 42, lang = "pt-BR"), kokoroVoiceParams("kokoro-pf-dora"))
        assertEquals(KokoroVoiceParams(sid = 43, lang = "pt-BR"), kokoroVoiceParams("kokoro-pm-alex"))
        assertEquals(KokoroVoiceParams(sid = 44, lang = "pt-BR"), kokoroVoiceParams("kokoro-pm-santa"))
    }

    @Test
    fun `nenhuma voz pt usa o lang generico pt do espeak-ng`() {
        // Regressão do bug real: lang="pt" (default do espeak-ng) soa como português EUROPEU;
        // o brasileiro exige "pt-BR". Qualquer voz pt com "pt"/null está errada (RN-1).
        for (voz in listOf("kokoro-pf-dora", "kokoro-pm-alex", "kokoro-pm-santa")) {
            val params = kokoroVoiceParams(voz)
            assertEquals("voz $voz deve ter lang explícito pt-BR", "pt-BR", params?.lang)
            assertTrue("voz $voz não pode cair no lang europeu", params?.lang != "pt")
        }
    }

    @Test
    fun `voz desconhecida ou de outro motor retorna null`() {
        assertNull(kokoroVoiceParams("kokoro-inexistente"))
        assertNull(kokoroVoiceParams("supertonic-f1"))
        assertNull(kokoroVoiceParams("edge-pt-BR-ThalitaMultilingualNeural"))
        assertNull(kokoroVoiceParams(""))
    }

    // ── Chunking ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `texto vazio ou curto nao quebra em chunks`() {
        assertEquals(emptyList<String>(), dividirEmChunks("   "))
        assertEquals(listOf("texto curto"), dividirEmChunks("texto curto"))
    }

    @Test
    fun `texto longo corta em fim de frase dentro do limite`() {
        val texto = "Primeira frase curta. " + "Segunda frase média com mais palavras aqui. ".repeat(20)
        val chunks = dividirEmChunks(texto)
        assertTrue("deveria quebrar em vários chunks", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("chunk ${chunk.length} chars > limite", chunk.length <= 450)
        }
        // Corte preferencial em fim de frase: os chunks (exceto o último) terminam com ponto.
        for (chunk in chunks.dropLast(1)) {
            assertTrue("chunk deveria terminar em fim de frase: '$chunk'", chunk.endsWith("."))
        }
    }

    @Test
    fun `texto sem pontuacao corta em espaco e sem espaco corta duro`() {
        val semPonto = "palavra ".repeat(200).trimEnd()
        val chunksEspaco = dividirEmChunks(semPonto)
        assertTrue(chunksEspaco.size > 1)
        assertTrue(chunksEspaco.all { it.length <= 450 })

        val semQuebra = "x".repeat(1000)
        val chunksDuro = dividirEmChunks(semQuebra)
        assertTrue(chunksDuro.size > 1)
        assertTrue(chunksDuro.all { it.length <= 450 })
        // Reconstrução preserva o conteúdo (sem perda).
        assertEquals(semQuebra, chunksDuro.joinToString(""))
    }

    // ── Divisão por oração (BYOM/Parte A — pausa artificial pra pacotes que ignoram pontuação) ─

    @Test
    fun `dividirParaByom quebra por oracao em vez de tamanho bruto`() {
        // Frases longas o bastante (>= 45 chars cada) pra NÃO disparar o agrupamento de orações
        // curtas do agruparOracoesCurta (que juntaria frases pequenas numa unidade só) — aqui
        // o objetivo é provar que a quebra respeita fim de frase, não o agrupamento em si.
        val f1 = "Esta é a primeira frase de teste, com bastante conteúdo."
        val f2 = "Esta é a segunda frase, também bem longa pra não ser agrupada."
        val chunks = dividirParaByom("$f1 $f2")
        assertEquals(listOf(f1, f2), chunks)
    }

    @Test
    fun `dividirParaByom agrupa oracoes curtas ate o teto (mesmo comportamento da leitura guiada)`() {
        val texto = "Primeira frase. Segunda frase. Terceira frase."
        val chunks = dividirParaByom(texto)
        // As 3 orações são curtas (< 45 chars cada) — agruparOracoesCurta as junta numa unidade
        // só, até ~110 chars, pra evitar uma rajada de chamadas nativas minúsculas.
        assertEquals(listOf("Primeira frase. Segunda frase. Terceira frase."), chunks)
    }

    @Test
    fun `dividirParaByom sem pontuacao cai no corte por tamanho`() {
        val semPonto = "palavra ".repeat(200).trimEnd()
        val chunks = dividirParaByom(semPonto)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 450 })
    }

    @Test
    fun `dividirParaByom preserva o conteudo sem perda`() {
        val texto = "Olá! Tudo bem? Isso é um teste. Mais uma frase aqui."
        val chunks = dividirParaByom(texto)
        assertEquals(texto.replace(" ", ""), chunks.joinToString("") { it.replace(" ", "") })
    }

    @Test
    fun `dividirParaByom oracao anormalmente longa ainda respeita o limite`() {
        val oracaoGigante = "palavra ".repeat(200).trimEnd() + "."
        val chunks = dividirParaByom(oracaoGigante)
        assertTrue("deveria quebrar a oração gigante em vários chunks", chunks.size > 1)
        assertTrue(chunks.all { it.length <= 450 })
    }

    @Test
    fun `dividirParaByom texto vazio nao quebra`() {
        assertEquals(emptyList<String>(), dividirParaByom(""))
    }

    // ── Writer WAV ────────────────────────────────────────────────────────────────────────

    @Test
    fun `writer wav gera header PCM 16-bit mono valido`() {
        val tmp = Files.createTempFile("kokoro_wav_test", ".wav").toFile()
        try {
            val samples = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)
            escreverWav16BitMono(samples, sampleRate = 24000, output = tmp)

            val bytes = tmp.readBytes()
            assertTrue(bytes.size >= 44 + samples.size * 2)
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val riff = ByteArray(4)
            header.get(riff)
            assertEquals("RIFF", String(riff))
            header.getInt() // tamanho do arquivo - 8
            val wave = ByteArray(4)
            header.get(wave)
            assertEquals("WAVE", String(wave))
            header.position(22)
            assertEquals(1, header.short.toInt())          // mono
            assertEquals(24000, header.int)                // sample rate
            header.position(34)
            assertEquals(16, header.short.toInt())         // bits por amostra

            // Amostras em 16-bit (mesma semântica do writer do OnnxTtsEngine):
            // 0.5 → 16383 (0.5*32767), 1.0 → 32767, -1.0 → -32767 ((-1.0*32767).toInt()).
            header.position(44)
            assertEquals(0, header.short.toInt())
            assertEquals(16383, header.short.toInt())
            assertEquals(-16383, header.short.toInt())
            assertEquals(32767, header.short.toInt())
            assertEquals(-32767, header.short.toInt())
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `writer wav grava fora de range sem estourar`() {
        val tmp = Files.createTempFile("kokoro_wav_clamp", ".wav").toFile()
        try {
            val samples = floatArrayOf(2.0f, -2.0f)
            escreverWav16BitMono(samples, sampleRate = 24000, output = tmp)
            val bytes = tmp.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            header.position(44)
            assertEquals(32767, header.short.toInt())   // clamp superior
            assertEquals(-32767, header.short.toInt())  // clamp inferior (-1.0*32767)
            assertTrue(tmp.length() == 44L + 4L)
        } finally {
            tmp.delete()
        }
    }
}
