package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavParaAacTest {

    @get:Rule
    val pasta = TemporaryFolder()

    @Test
    fun reconheceCabecalhoWavDisfarcado() {
        val wav = wav(canais = 1, taxa = 22050, bits = 16, dados = ByteArray(10))
        assertTrue(ehWavDisfarcado(wav.copyOf(12)))
    }

    @Test
    fun naoReconheceMp3NemTextoNemCabecalhoCurto() {
        assertFalse(ehWavDisfarcado("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray()))
        assertFalse(ehWavDisfarcado("RIFF0000AVI ".toByteArray()))
        assertFalse(ehWavDisfarcado("RIFF".toByteArray()))
    }

    @Test
    fun espacoMinimoEVinteporcentoDoWav() {
        assertTrue(temEspacoParaCompressao(espacoLivre = 20, tamanhoWav = 100))
        assertFalse(temEspacoParaCompressao(espacoLivre = 19, tamanhoWav = 100))
        assertTrue(temEspacoParaCompressao(espacoLivre = 0, tamanhoWav = 0))
    }

    @Test
    fun duracaoConfereDentroDeMeioSegundo() {
        assertTrue(duracaoConfere(10_000, 10_000))
        assertTrue(duracaoConfere(10_000, 10_500))
        assertTrue(duracaoConfere(10_000, 9_500))
        assertFalse(duracaoConfere(10_000, 10_600))
        assertFalse(duracaoConfere(10_000, null))
    }

    @Test
    fun lerCabecalhoDeWavMonoPcm16() {
        // 0,1 s em 22050 Hz mono, 16 bits: 2205 amostras = 4410 bytes de PCM.
        val arquivo = gravar(wav(canais = 1, taxa = 22050, bits = 16, dados = ByteArray(4410)))
        val cabecalho = RandomAccessFile(arquivo, "r").use { lerCabecalhoWav(it) }
        assertNotNull(cabecalho)
        assertEquals(1, cabecalho!!.canais)
        assertEquals(22050, cabecalho.taxa)
        assertEquals(16, cabecalho.bitsPorAmostra)
        assertEquals(44L, cabecalho.offsetDados)
        assertEquals(4410L, cabecalho.tamanhoDados)
        assertEquals(100L, cabecalho.duracaoMs())
        assertTrue(cabecalho.comprimivel)
    }

    @Test
    fun pulaChunkExtraComTamanhoImpar() {
        // Um chunk LIST de tamanho ímpar (3) antes de data: o padding de 1 byte precisa ser pulado.
        val extra = "LIST".toByteArray() + le32(3) + byteArrayOf(1, 2, 3, 0)
        val arquivo = gravar(wav(canais = 2, taxa = 16000, bits = 16, dados = ByteArray(64), extra = extra))
        val cabecalho = RandomAccessFile(arquivo, "r").use { lerCabecalhoWav(it) }
        assertNotNull(cabecalho)
        assertEquals(2, cabecalho!!.canais)
        assertEquals(64L, cabecalho.tamanhoDados)
        assertEquals(44L + extra.size, cabecalho.offsetDados)
    }

    @Test
    fun dataComTamanhoInvalidoOuCortadoUsaOQueExiste() {
        // Tamanho 0xFFFFFFFF (streaming) e arquivo com só 100 bytes de PCM: vale o que existe.
        val arquivo = gravar(wav(canais = 1, taxa = 22050, bits = 16, dados = ByteArray(100), tamanhoDeclarado = 0xFFFFFFFFL))
        val cabecalho = RandomAccessFile(arquivo, "r").use { lerCabecalhoWav(it) }
        assertNotNull(cabecalho)
        assertEquals(100L, cabecalho!!.tamanhoDados)
    }

    @Test
    fun formatoExtensivelComPcm16EhComprimivel() {
        val arquivo = gravar(wav(canais = 1, taxa = 22050, bits = 16, dados = ByteArray(200), tag = 0xFFFE))
        val cabecalho = RandomAccessFile(arquivo, "r").use { lerCabecalhoWav(it) }
        assertTrue(cabecalho!!.comprimivel)
    }

    @Test
    fun pcm8BitsOuPontoFlutuanteNaoEhComprimivel() {
        val oito = gravar(wav(canais = 1, taxa = 22050, bits = 8, dados = ByteArray(200)))
        val flutuante = gravar(wav(canais = 1, taxa = 22050, bits = 32, dados = ByteArray(200), tag = 3))
        assertFalse(RandomAccessFile(oito, "r").use { lerCabecalhoWav(it) }!!.comprimivel)
        assertFalse(RandomAccessFile(flutuante, "r").use { lerCabecalhoWav(it) }!!.comprimivel)
    }

    @Test
    fun arquivoQueNaoEhWavNaoTemCabecalho() {
        val mp3 = gravar("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000ÿû".toByteArray() + ByteArray(200))
        assertNull(RandomAccessFile(mp3, "r").use { lerCabecalhoWav(it) })
    }

    @Test
    fun wavSemChunkDataNaoTemCabecalho() {
        val semData = "RIFF".toByteArray() + le32(28) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + fmtBody(1, 1, 22050, 16)
        assertNull(RandomAccessFile(gravar(semData), "r").use { lerCabecalhoWav(it) })
    }

    @Test
    fun ehWavArquivoLeOsPrimeirosBytesDoArquivo() {
        assertTrue(ehWavArquivo(gravar(wav(canais = 1, taxa = 8000, bits = 16, dados = ByteArray(10)))))
        assertFalse(ehWavArquivo(gravar(ByteArray(40))))
        assertFalse(ehWavArquivo(File(pasta.root, "nao_existe.mp3")))
    }

    @Test
    fun leituraDoPcmNaoPerdeBytesNemDesalinhaOEstereo() {
        // 10 bytes de PCM estéreo (quadro de 4 bytes): 2 quadros completos + 2 bytes de sobra no fim.
        val arquivo = gravar(ByteArray(10) { it.toByte() })
        RandomAccessFile(arquivo, "r").use { raf ->
            val bloco = ByteArray(64)
            assertEquals(8, lerAlinhado(raf, bloco, capacidadeEncoder = 8, restante = 10L, bytesPorQuadro = 4))
            assertEquals(8L, raf.filePointer)
            // A sobra (2 bytes, quadro incompleto) volta para o arquivo; nada é consumido sem ser entregue.
            assertEquals(0, lerAlinhado(raf, bloco, capacidadeEncoder = 8, restante = 2L, bytesPorQuadro = 4))
            assertEquals(8L, raf.filePointer)
        }
    }

    @Test
    fun leituraDeQuadrosInteirosSemSobra() {
        val arquivo = gravar(ByteArray(16) { it.toByte() })
        RandomAccessFile(arquivo, "r").use { raf ->
            val bloco = ByteArray(64)
            assertEquals(12, lerAlinhado(raf, bloco, capacidadeEncoder = 12, restante = 16L, bytesPorQuadro = 4))
            assertEquals(4, lerAlinhado(raf, bloco, capacidadeEncoder = 12, restante = 4L, bytesPorQuadro = 4))
            assertEquals(bloco[0], 12.toByte())
        }
    }

    // ── Montagem de WAV sintético ──────────────────────────────────────────────

    private fun gravar(bytes: ByteArray): File =
        File(pasta.root, "amostra_${System.nanoTime()}.mp3").apply { writeBytes(bytes) }

    private fun wav(
        canais: Int,
        taxa: Int,
        bits: Int,
        dados: ByteArray,
        tag: Int = 1,
        extra: ByteArray = ByteArray(0),
        tamanhoDeclarado: Long = dados.size.toLong()
    ): ByteArray {
        val fmt = "fmt ".toByteArray() + le32(16) + fmtBody(tag, canais, taxa, bits)
        val data = "data".toByteArray() + le32(tamanhoDeclarado) + dados
        val corpo = "WAVE".toByteArray() + fmt + extra + data
        return "RIFF".toByteArray() + le32(corpo.size.toLong()) + corpo
    }

    private fun fmtBody(tag: Int, canais: Int, taxa: Int, bits: Int): ByteArray {
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(tag.toShort())
        bb.putShort(canais.toShort())
        bb.putInt(taxa)
        bb.putInt(taxa * canais * bits / 8)
        bb.putShort((canais * bits / 8).toShort())
        bb.putShort(bits.toShort())
        return bb.array()
    }

    private fun le32(valor: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(valor.toInt()).array()

    private fun le32(valor: Int): ByteArray = le32(valor.toLong())
}
