package com.jonjonesbr.audiobookgen.tts

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sin

class VoiceReferenceAudioTest {
    private val rate = PocketCustomVoices.SAMPLE_RATE

    /** Bloco de saída do decodificador de mentira: 4800 bytes = 2400 amostras = 0,1 s a 24 kHz. */
    private val bloco = 4_800

    /** Tamanho do buffer devolvido pelo decodificador de mentira. */
    private val tamanhoDoBuffer = 16 * 1024

    private fun sinal(segundos: Int, fala: IntRange): ShortArray = ShortArray(segundos * rate) { i ->
        val t = i / rate
        if (t in fala) (8_000 * sin(i * 0.05)).toInt().toShort() else (20 * sin(i * 0.3)).toInt().toShort()
    }

    @Test
    fun `audio curto fica como esta`() {
        val curto = sinal(6, 0..5)
        assertEquals(curto.size, VoiceReferenceAudio.melhorTrecho(curto).size)
    }

    @Test
    fun `audio longo vira 10 segundos e cai na parte falada`() {
        val longo = sinal(40, 18..33)
        val trecho = VoiceReferenceAudio.melhorTrecho(longo)
        assertEquals(PocketCustomVoices.MAX_SECONDS * rate, trecho.size)
        val fracaoComFala = trecho.count { kotlin.math.abs(it.toInt()) > 1_000 } / trecho.size.toDouble()
        assertTrue("o trecho devia ser quase todo fala, mas foi $fracaoComFala", fracaoComFala > 0.6)
    }

    @Test
    fun `wav antigo de 30 segundos e reduzido ao abrir`() {
        val arquivo = File.createTempFile("ref", ".wav")
        try {
            VoiceReferenceAudio.escreverWav(arquivo, sinal(30, 5..28), rate)
            VoiceReferenceAudio.aparar(arquivo)
            assertEquals(44L + PocketCustomVoices.MAX_SECONDS * rate * 2L, arquivo.length())
        } finally {
            arquivo.delete()
        }
    }

    @Test
    fun `excecao no meio do laco para e libera uma vez e sobe`() {
        var saidas = 0
        val falso = DecodificadorFalso { info ->
            saidas++
            if (saidas == 1) {
                info.size = bloco
                0
            } else {
                error("falha no meio do laco")
            }
        }

        val erro = assertThrows(IllegalStateException::class.java) {
            VoiceReferenceAudio.decodificarESalvar(
                decodificacao = Decodificacao(falso, fonteDeTeste(), rate, 1),
                destino = arquivoTemporario(),
                relogio = { 0L }
            )
        }

        assertEquals("falha no meio do laco", erro.message)
        assertEquals(1, falso.paradas)
        assertEquals(1, falso.liberacoes)
    }

    @Test
    fun `decodificador sem progresso sai pelo prazo e libera uma vez`() {
        // Só responde TRY_AGAIN_LATER: sem prazo, este laço não terminaria nunca.
        val falso = DecodificadorFalso { MediaCodec.INFO_TRY_AGAIN_LATER }
        var agora = 0L
        val erro = assertThrows(IllegalStateException::class.java) {
            VoiceReferenceAudio.decodificarESalvar(
                decodificacao = Decodificacao(falso, fonteDeTeste(), rate, 1),
                destino = arquivoTemporario(),
                relogio = { agora.also { agora += 5_000L } }
            )
        }

        assertEquals("O decodificador não devolveu nenhum trecho de áudio.", erro.message)
        assertTrue("o laço devia parar no prazo, mas pediu ${falso.pedidosDeSaida} saídas", falso.pedidosDeSaida <= 3)
        assertEquals(1, falso.paradas)
        assertEquals(1, falso.liberacoes)
    }

    @Test
    fun `caminho feliz decodifica ate o fim do fluxo e grava o wav`() {
        var saidas = 0
        val falso = DecodificadorFalso { info ->
            info.size = bloco
            info.flags = if (saidas == 2) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            saidas++
            0
        }
        val destino = arquivoTemporario()

        val resultado = VoiceReferenceAudio.decodificarESalvar(
            decodificacao = Decodificacao(falso, fonteDeTeste(), rate, 1),
            destino = destino,
            relogio = { 0L }
        )

        assertEquals(0.3f, resultado.seconds, 0.0001f)
        assertEquals(44L + bloco * 3L, destino.length())
        assertEquals(1, falso.paradas)
        assertEquals(1, falso.liberacoes)
    }

    /** Fonte de amostras de mentira: devolve [amostras] amostras de 4 bytes e depois o fim do fluxo. */
    private fun fonteDeTeste(amostras: Int = 1): FonteDeAmostras {
        var lidas = 0
        return object : FonteDeAmostras {
            override fun ler(buffer: ByteBuffer): Int = if (lidas++ < amostras) 4 else -1

            override fun instante(): Long = 0L

            override fun avancar() = Unit
        }
    }

    private fun arquivoTemporario(): File =
        File.createTempFile("ref", ".wav").also { it.deleteOnExit() }

    /** Decodificador de mentira para o laço: cada saída é decidida por [aoSair] e para/libera é contado. */
    private inner class DecodificadorFalso(
        private val aoSair: (InfoDoBuffer) -> Int
    ) : Decodificador {
        var paradas = 0
        var liberacoes = 0
        var pedidosDeSaida = 0
        private var entradas = 0

        override fun iniciar() = Unit

        override fun desenfileirarEntrada(esperaUs: Long): Int = entradas++

        override fun desenfileirarSaida(info: InfoDoBuffer, esperaUs: Long): Int {
            pedidosDeSaida++
            return aoSair(info)
        }

        override fun bufferDeEntrada(indice: Int): ByteBuffer = ByteBuffer.allocate(tamanhoDoBuffer)

        override fun bufferDeSaida(indice: Int): ByteBuffer = ByteBuffer.allocate(tamanhoDoBuffer)

        override fun enfileirarEntrada(indice: Int, tamanho: Int, instanteUs: Long, flags: Int) = Unit

        override fun liberarSaida(indice: Int) = Unit

        override val formatoDeSaida: FormatoDeSaida
            get() = FormatoDeSaida(null, null)

        override fun parar() {
            paradas++
        }

        override fun liberar() {
            liberacoes++
        }
    }
}
