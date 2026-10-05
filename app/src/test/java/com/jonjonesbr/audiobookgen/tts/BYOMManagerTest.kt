package com.jonjonesbr.audiobookgen.tts

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Testes das funções puras do BYOMManager (V6 Parte B): parsing/validação de manifesto,
 * extração com as travas extras de fonte não-confiável (zip-slip, nº de entradas, tamanho,
 * nomes permitidos) e validação de conteúdo por arquitetura. Import/rehydratação em si exigem
 * Context/IO real e não são testados em JVM (mesmo critério do KokoroModelManagerTest).
 */
class BYOMManagerTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun manifestoJson(
        arquitetura: String = "kokoro",
        nome: String = "Pacote Teste",
        atribuicao: String = "Autor X, MIT",
        vozes: String = """[{"id":"v1","nome":"Voz 1","idioma":"pt-BR","sid":0}]"""
    ) = """{"arquitetura":"$arquitetura","nome":"$nome","atribuicao":"$atribuicao","vozes":$vozes}"""

    // ── lerManifesto ─────────────────────────────────────────────────────────────────────

    @Test
    fun `le manifesto valido kokoro`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            File(dir, "manifesto.json").writeText(manifestoJson())
            val m = BYOMManager.lerManifesto(dir)
            assertNotNull(m)
            assertEquals("kokoro", m!!.arquitetura)
            assertEquals("Pacote Teste", m.nome)
            assertEquals(1, m.vozes.size)
            assertEquals("v1", m.vozes[0].idOriginal)
            assertEquals("pt-BR", m.vozes[0].idioma)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejeita manifesto ausente`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            assertNull(BYOMManager.lerManifesto(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejeita manifesto com arquitetura desconhecida`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            File(dir, "manifesto.json").writeText(manifestoJson(arquitetura = "xtts"))
            assertNull(BYOMManager.lerManifesto(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejeita manifesto sem vozes`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            File(dir, "manifesto.json").writeText(manifestoJson(vozes = "[]"))
            assertNull(BYOMManager.lerManifesto(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejeita manifesto json malformado`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            File(dir, "manifesto.json").writeText("{ isso nao e json valido")
            assertNull(BYOMManager.lerManifesto(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ignora voz individual sem id ou idioma mas mantem as validas`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            val vozes = """[
                {"id":"v1","nome":"Voz 1","idioma":"pt-BR","sid":0},
                {"id":"","nome":"Sem id","idioma":"pt-BR","sid":1}
            ]"""
            File(dir, "manifesto.json").writeText(manifestoJson(vozes = vozes))
            val m = BYOMManager.lerManifesto(dir)
            assertNotNull(m)
            assertEquals(1, m!!.vozes.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── validarConteudo ──────────────────────────────────────────────────────────────────

    private fun arquivoValido(dir: File, nome: String, bytes: Int = 1000) {
        File(dir, nome).writeBytes(ByteArray(bytes) { 1 })
    }

    @Test
    fun `kokoro valido com voices e lexicon`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt")
            arquivoValido(dir, "voices.bin")
            arquivoValido(dir, "lexicon-us-en.txt")
            val manifesto = BYOMManager.ManifestoImportado(
                "kokoro", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0))
            )
            assertNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `kokoro sem voices bin e rejeitado`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt")
            arquivoValido(dir, "lexicon-us-en.txt")
            val manifesto = BYOMManager.ManifestoImportado(
                "kokoro", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0))
            )
            assertNotNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `kokoro sem lexicon e sem espeak-ng-data e rejeitado`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt")
            arquivoValido(dir, "voices.bin")
            val manifesto = BYOMManager.ManifestoImportado(
                "kokoro", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0))
            )
            assertNotNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `kokoro com espeak-ng-data no lugar do lexicon e aceito`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt")
            arquivoValido(dir, "voices.bin")
            File(dir, "espeak-ng-data").mkdirs()
            arquivoValido(File(dir, "espeak-ng-data").apply { mkdirs() }, "phontab")
            val manifesto = BYOMManager.ManifestoImportado(
                "kokoro", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0))
            )
            assertNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `vits valido com lexicon unico`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt")
            arquivoValido(dir, "lexicon.txt")
            val manifesto = BYOMManager.ManifestoImportado(
                "vits", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0))
            )
            assertNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `vits sem lexicon e sem espeak-ng-data e rejeitado`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt")
            val manifesto = BYOMManager.ManifestoImportado(
                "vits", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0))
            )
            assertNotNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `vits sem fonemizacao (frontend por caractere, ex MMS-TTS) e aceito sem lexicon nem espeak`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt")
            val manifesto = BYOMManager.ManifestoImportado(
                "vits", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0)),
                semFonemizacao = true
            )
            assertNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `le manifesto com semFonemizacao true`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            val json = """{"arquitetura":"vits","nome":"MMS","atribuicao":"Meta, CC-BY-NC",
                |"vozes":[{"id":"main","nome":"Padrao","idioma":"pt-BR","sid":0}],
                |"semFonemizacao":true}""".trimMargin()
            File(dir, "manifesto.json").writeText(json)
            val m = BYOMManager.lerManifesto(dir)
            assertNotNull(m)
            assertTrue(m!!.semFonemizacao)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `manifesto sem semFonemizacao default para false`() {
        val dir = Files.createTempDirectory("byom_manifesto").toFile()
        try {
            File(dir, "manifesto.json").writeText(manifestoJson(arquitetura = "vits"))
            val m = BYOMManager.lerManifesto(dir)
            assertNotNull(m)
            assertFalse(m!!.semFonemizacao)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tokens txt pequeno (vocabulario por caractere) e aceito`() {
        // Regressao: tokens.txt tem tamanho legitimamente pequeno pra modelos de tokenizacao
        // por caractere (bundle real do MMS-TTS pt-BR: 476 bytes) — nao deve seguir o mesmo
        // piso de TAMANHO_MIN_ARQUIVO_VALIDO usado pra pesos de modelo (model.onnx).
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt", bytes = 100)
            val manifesto = BYOMManager.ManifestoImportado(
                "vits", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0)),
                semFonemizacao = true
            )
            assertNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tokens txt vazio e rejeitado mesmo pequeno`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "model.onnx")
            arquivoValido(dir, "tokens.txt", bytes = 0)
            val manifesto = BYOMManager.ManifestoImportado(
                "vits", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0)),
                semFonemizacao = true
            )
            assertNotNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `vits sem model onnx e rejeitado`() {
        val dir = Files.createTempDirectory("byom_conteudo").toFile()
        try {
            arquivoValido(dir, "tokens.txt")
            arquivoValido(dir, "lexicon.txt")
            val manifesto = BYOMManager.ManifestoImportado(
                "vits", "X", "", listOf(BYOMManager.VozImportada("v1", "V1", "pt-BR", 0))
            )
            assertNotNull(BYOMManager.validarConteudo(dir, manifesto))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── extrairZipByom: zip-slip / limites / nomes permitidos ───────────────────────────────

    @Test
    fun `extrai zip valido kokoro`() {
        val zip = Files.createTempFile("byom_zip", ".zip").toFile()
        val destino = Files.createTempDirectory("byom_dest").toFile()
        try {
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("manifesto.json"))
                zos.write(manifestoJson().toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("model.onnx"))
                zos.write(ByteArray(100) { 1 })
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("espeak-ng-data/pt_dict"))
                zos.write(byteArrayOf(1, 2, 3))
                zos.closeEntry()
            }
            BYOMManager.extrairZipByom(zip, destino)
            assertTrue(File(destino, "model.onnx").exists())
            assertTrue(File(destino, "espeak-ng-data/pt_dict").exists())
        } finally {
            zip.delete()
            destino.deleteRecursively()
        }
    }

    @Test
    fun `rejeita entrada zip-slip`() {
        for (nomeMalicioso in listOf("../evil.txt", "/abs/evil.txt", "espeak-ng-data/../../evil.txt")) {
            val zip = Files.createTempFile("byom_slip", ".zip").toFile()
            val destino = Files.createTempDirectory("byom_dest").toFile()
            try {
                ZipOutputStream(zip.outputStream()).use { zos ->
                    zos.putNextEntry(ZipEntry(nomeMalicioso))
                    zos.write(byteArrayOf(1))
                    zos.closeEntry()
                }
                assertThrows(IOException::class.java) { BYOMManager.extrairZipByom(zip, destino) }
                assertFalse(File(destino.parentFile, "evil.txt").exists())
            } finally {
                zip.delete()
                destino.deleteRecursively()
            }
        }
    }

    @Test
    fun `rejeita arquivo com nome nao esperado na raiz`() {
        val zip = Files.createTempFile("byom_bad_root", ".zip").toFile()
        val destino = Files.createTempDirectory("byom_dest").toFile()
        try {
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("evil.so"))
                zos.write(byteArrayOf(1))
                zos.closeEntry()
            }
            assertThrows(IOException::class.java) { BYOMManager.extrairZipByom(zip, destino) }
        } finally {
            zip.delete()
            destino.deleteRecursively()
        }
    }

    @Test
    fun `aceita lexicon com nome variavel na raiz (padrao kokoro)`() {
        val zip = Files.createTempFile("byom_lexicon", ".zip").toFile()
        val destino = Files.createTempDirectory("byom_dest").toFile()
        try {
            ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("lexicon-us-en.txt"))
                zos.write(byteArrayOf(1))
                zos.closeEntry()
            }
            BYOMManager.extrairZipByom(zip, destino)
            assertTrue(File(destino, "lexicon-us-en.txt").exists())
        } finally {
            zip.delete()
            destino.deleteRecursively()
        }
    }

    @Test
    fun `rejeita zip vazio`() {
        val zip = Files.createTempFile("byom_vazio", ".zip").toFile()
        val destino = Files.createTempDirectory("byom_dest").toFile()
        try {
            ZipOutputStream(zip.outputStream()).use { zos -> zos.putNextEntry(ZipEntry("dir/")) }
            assertThrows(IOException::class.java) { BYOMManager.extrairZipByom(zip, destino) }
        } finally {
            zip.delete()
            destino.deleteRecursively()
        }
    }
}
