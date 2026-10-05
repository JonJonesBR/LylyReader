package com.jonjonesbr.audiobookgen.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Testes das funções puras do KokoroModelManager (V6 T1.4): extração zip (layout + zip-slip)
 * e sha256. O download em si exige rede/device (não testado em JVM).
 */
class KokoroModelManagerTest {

    // ── Extração zip ──────────────────────────────────────────────────────────────────────

    @Test
    fun `extrai zip com layout de arquivos e diretorios`() {
        val zip = Files.createTempFile("kokoro_zip_test", ".zip").toFile()
        val destino = Files.createTempDirectory("kokoro_dest").toFile()
        try {
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("model.onnx"))
                zos.write(ByteArray(100) { 7 })
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("espeak-ng-data/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("espeak-ng-data/pt_dict"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
            extrairZip(zip, destino)
            val modelo = File(destino, "model.onnx")
            assertTrue(modelo.exists())
            assertEquals(100L, modelo.length())
            val dict = File(destino, "espeak-ng-data/pt_dict")
            assertTrue(dict.exists())
            assertEquals(3L, dict.length())
        } finally {
            zip.delete()
            destino.deleteRecursively()
        }
    }

    @Test
    fun `rejeita entrada zip-slip com caminho absoluto ou parent`() {
        for (nomeMalicioso in listOf("../evil.txt", "/abs/evil.txt", "a/../../evil.txt")) {
            val zip = Files.createTempFile("kokoro_slip", ".zip").toFile()
            val destino = Files.createTempDirectory("kokoro_dest").toFile()
            try {
                ZipOutputStream(zip.outputStream()).use { zos ->
                    zos.putNextEntry(ZipEntry(nomeMalicioso))
                    zos.write(byteArrayOf(1))
                    zos.closeEntry()
                }
                assertThrows(IOException::class.java) {
                    extrairZip(zip, destino)
                }
                // Nada vaza para fora do destino.
                assertFalse(File(destino.parentFile, "evil.txt").exists())
            } finally {
                zip.delete()
                destino.deleteRecursively()
            }
        }
    }

    @Test
    fun `rejeita zip vazio`() {
        val zip = Files.createTempFile("kokoro_vazio", ".zip").toFile()
        val destino = Files.createTempDirectory("kokoro_dest").toFile()
        try {
            ZipOutputStream(zip.outputStream()).use { zos -> zos.putNextEntry(ZipEntry("dir/")) }
            assertThrows(IOException::class.java) {
                extrairZip(zip, destino)
            }
        } finally {
            zip.delete()
            destino.deleteRecursively()
        }
    }

    // ── sha256 ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `sha256 de arquivo confere com vetor conhecido`() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val arquivo = Files.createTempFile("kokoro_sha", ".bin").toFile()
        try {
            arquivo.writeBytes("abc".toByteArray())
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256Hex(arquivo)
            )
        } finally {
            arquivo.delete()
        }
    }

    @Test
    fun `sha256 difere para conteudo alterado`() {
        val a = Files.createTempFile("kokoro_sha_a", ".bin").toFile()
        val b = Files.createTempFile("kokoro_sha_b", ".bin").toFile()
        try {
            a.writeBytes("conteudo original".toByteArray())
            b.writeBytes("conteudo originalX".toByteArray())
            assertFalse(sha256Hex(a) == sha256Hex(b))
        } finally {
            a.delete()
            b.delete()
        }
    }
}
