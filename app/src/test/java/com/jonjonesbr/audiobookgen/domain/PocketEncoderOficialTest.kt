package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

class PocketEncoderOficialTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun sha(dados: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(dados).joinToString("") { "%02x".format(it) }

    /** safetensors mínimo: "a" BF16 [2,3] (bytes 0..11) e "b" BF16 [2] (bytes 12..15). */
    private fun safetensorsSintetico(): ByteArray {
        val cabecalho = """{"__metadata__":{"format":"pt"},"a":{"dtype":"BF16","shape":[2,3],"data_offsets":[0,12]},"b":{"dtype":"BF16","shape":[2],"data_offsets":[12,16]}}"""
            .toByteArray()
        val tamanho = ByteArray(8) { i -> ((cabecalho.size.toLong() shr (8 * i)) and 0xFF).toByte() }
        return tamanho + cabecalho + ByteArray(16) { it.toByte() }
    }

    private val mapaSintetico = listOf(
        PesoMapeado("A", "a", transposto = true, forma = listOf(3, 2)),
        PesoMapeado("B", "b", transposto = false, forma = listOf(2))
    )

    @Test
    fun `varint segue o formato do protobuf`() {
        assertArrayEquals(bytes(1), PocketEncoderOficial.varint(1))
        assertArrayEquals(bytes(0xAC, 0x02), PocketEncoderOficial.varint(300))
    }

    @Test
    fun `pedaco de peso vira graph initializer com dims tipo nome e bytes`() {
        val esperado = bytes(
            0x3A, 21, 0x2A, 19,
            0x08, 1, 0x08, 2, 0x10, 1, 0x42, 1, 'w'.code, 0x4A, 8,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        assertArrayEquals(esperado, PocketEncoderOficial.fragmentoInicializador("w", listOf(1, 2), ByteArray(8)))
    }

    @Test
    fun `bf16 vira fp32 exato`() {
        // 1,0 em BF16 é 0x3F80 → em FP32 é 0x3F800000.
        val fp32 = PocketEncoderOficial.bf16ParaFp32(bytes(0x80, 0x3F), listOf(1), transposto = false)
        assertEquals(1.0f, java.nio.ByteBuffer.wrap(fp32).order(java.nio.ByteOrder.LITTLE_ENDIAN).float)
    }

    @Test
    fun `matriz transposta troca linhas por colunas`() {
        // [[1,2,3],[4,5,6]] em BF16 (só o byte baixo, para marcar posições).
        val origem = bytes(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0)
        val fp32 = PocketEncoderOficial.bf16ParaFp32(origem, listOf(2, 3), transposto = true)
        val ordem = (0 until 6).map { fp32[4 * it + 2].toInt() }
        assertEquals(listOf(1, 4, 2, 5, 3, 6), ordem)
    }

    @Test
    fun `le o tamanho do cabecalho em little endian e recusa absurdos`() {
        assertEquals(258L, PocketEncoderOficial.tamanhoCabecalho(bytes(2, 1, 0, 0, 0, 0, 0, 0)))
        assertTrue(runCatching { PocketEncoderOficial.tamanhoCabecalho(bytes(0, 0, 0, 0, 0, 0, 0, 1)) }.isFailure)
    }

    @Test
    fun `le os pesos em blocos pequenos e calcula o hash do arquivo inteiro`() {
        val arquivo = safetensorsSintetico()
        val lidos = PocketEncoderOficial.lerPesos(ByteArrayInputStream(arquivo), mapaSintetico, arquivo.size.toLong(), bloco = 3)
        assertEquals(sha(arquivo), lidos.sha256)
        assertArrayEquals(ByteArray(12) { it.toByte() }, lidos.pesos["a"])
        assertArrayEquals(bytes(12, 13, 14, 15), lidos.pesos["b"])
    }

    @Test
    fun `arquivo sem um peso esperado ou cortado e recusado`() {
        val arquivo = safetensorsSintetico()
        val semPeso = mapaSintetico + PesoMapeado("C", "c", transposto = false, forma = listOf(1))
        assertTrue(runCatching {
            PocketEncoderOficial.lerPesos(ByteArrayInputStream(arquivo), semPeso, 0)
        }.exceptionOrNull() is PocketEncoderOficial.ArquivoInvalidoException)
        val cortado = arquivo.copyOf(arquivo.size - 2)
        assertTrue(runCatching {
            PocketEncoderOficial.lerPesos(ByteArrayInputStream(cortado), mapaSintetico, 0)
        }.exceptionOrNull() is PocketEncoderOficial.ArquivoInvalidoException)
    }

    @Test
    fun `forma diferente da esperada e recusada`() {
        val tensor = TensorSafetensors("BF16", listOf(2, 3), 0, 12)
        assertTrue(PocketEncoderOficial.compativel(PesoMapeado("A", "a", true, listOf(3, 2)), tensor))
        assertFalse(PocketEncoderOficial.compativel(PesoMapeado("A", "a", false, listOf(3, 2)), tensor))
        assertFalse(PocketEncoderOficial.compativel(PesoMapeado("A", "a", false, listOf(2, 3)), tensor.copy(dtype = "F32")))
    }

    @Test
    fun `arquivos oficiais por hash idioma e tamanho`() {
        val pt = PocketEncoderOficial.porIdioma("pt-BR")
        assertEquals(pt, PocketEncoderOficial.porSha256(pt.sha256.uppercase()))
        assertNull(PocketEncoderOficial.porSha256("0".repeat(64)))
        assertEquals("en-US", PocketEncoderOficial.porIdioma("en").idioma)
        assertEquals("pt-BR", PocketEncoderOficial.porIdioma("fr-FR").idioma)
        assertTrue(PocketEncoderOficial.tamanhoPlausivel(null))
        assertTrue(PocketEncoderOficial.tamanhoPlausivel(219_029_196L))
        assertFalse(PocketEncoderOficial.tamanhoPlausivel(5_000L))
        assertTrue(pt.urlDownload.contains("/resolve/${pt.revisao}/languages/portuguese/model.safetensors"))
    }

    /**
     * Ponta a ponta com o arquivo oficial real (só roda onde ele está no cache do Hugging Face):
     * o codificador montado precisa ser idêntico, byte a byte, ao que a prova em Python montou e
     * validou contra o codificador anterior (diferença de saída 0,0).
     */
    @Test
    fun `monta o codificador portugues identico ao validado`() {
        val oficial = File(
            System.getProperty("user.home"),
            ".cache/huggingface/hub/models--kyutai--pocket-tts/snapshots/" +
                "2dd944b099d06bb9edbd221fef138685711d9bf0/languages/portuguese/model.safetensors"
        )
        assumeTrue("arquivo oficial não está neste computador", oficial.isFile)
        val assets = listOf(File("src/main/assets/pocket"), File("app/src/main/assets/pocket")).first { it.isDirectory }
        val mapa = PocketEncoderOficial.lerMapa(File(assets, "mimi_encoder_mapa.json").readText())
        val lidos = oficial.inputStream().buffered().use { PocketEncoderOficial.lerPesos(it, mapa, oficial.length()) }
        assertEquals(PocketEncoderOficial.porIdioma("pt-BR").sha256, lidos.sha256)
        val saida = ByteArrayOutputStream()
        File(assets, "mimi_encoder_planta.onnx").inputStream().use { PocketEncoderOficial.escreverOnnx(it, mapa, lidos.pesos, saida) }
        assertEquals("c8c631a942d781b99d32c9837cade626a31b8ce6ffa5d74fbcca94a338dc3197", sha(saida.toByteArray()))
    }
}
