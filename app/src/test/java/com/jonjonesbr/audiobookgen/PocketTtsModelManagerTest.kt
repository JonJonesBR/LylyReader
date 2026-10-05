package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class PocketTtsModelManagerTest {
    @Test
    fun catalogMapsEachFixedVoiceToItsOwnLanguagePack() {
        assertEquals("pocket-ptbr-int8", PocketTtsModelManager.packIdForVoice("pocket-ptbr-rafael"))
        assertEquals("pocket-en-int8", PocketTtsModelManager.packIdForVoice("pocket-en-alba"))
        assertEquals("pocket-es-int8", PocketTtsModelManager.packIdForVoice("pocket-es-lola"))
        assertNull(PocketTtsModelManager.packIdForVoice("pocket-voice-from-outside-catalog"))

        val specs = listOf("pocket-ptbr-int8", "pocket-en-int8", "pocket-es-int8")
            .mapNotNull(PocketTtsModelManager::spec)
        assertEquals(listOf("pt-BR", "en-US", "es-ES"), specs.map { it.languageTag })
        assertEquals(3, specs.map { it.archive }.toSet().size)
        assertEquals(
            listOf(
                "fe9db388dc987c8a26bb88789d2f20989e68e00d8534442a141ecccec21cedba",
                "d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6",
                "9714c8ce180d8147ab4c1406cd759619828f95d7000c87c41dc9ccec527a3e89"
            ),
            specs.map { it.tokenizerSha256 }
        )
        assertEquals(
            listOf("pocket/tokenizers/pt-BR.model", "pocket/tokenizers/en-US.model", "pocket/tokenizers/es-ES.model"),
            specs.map { it.tokenizerAsset }
        )
    }

    @Test
    fun readinessRejectsTokenizerFromAnotherLanguagePack() {
        val model = requireNotNull(PocketTtsModelManager.spec("pocket-ptbr-int8"))
        val root = Files.createTempDirectory("pocket-tokenizer-test").toFile()
        val tokenizer = File(root, "models/tokenizer.model").apply {
            parentFile?.mkdirs()
            writeText("generic tokenizer with a valid-looking file size")
        }

        assertFalse(PocketTtsModelManager.hasExpectedTokenizer(root, model))

        val matchingDigest = MessageDigest.getInstance("SHA-256")
            .digest(tokenizer.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue(PocketTtsModelManager.hasExpectedTokenizer(root, model.copy(tokenizerSha256 = matchingDigest)))
    }

    @Test
    fun bundledTokenizerRepairsAnOlderPackOnlyWhenItsHashMatches() {
        val root = Files.createTempDirectory("pocket-tokenizer-repair").toFile()
        val tokenizer = File(root, "models/tokenizer.model").apply {
            parentFile?.mkdirs()
            writeText("old tokenizer")
        }
        val correctBytes = ByteArray(8192) { index -> (index % 251).toByte() }
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(correctBytes)
            .joinToString("") { "%02x".format(it) }
        val model = requireNotNull(PocketTtsModelManager.spec("pocket-ptbr-int8"))
            .copy(tokenizerSha256 = expectedHash)

        assertFalse(
            PocketTtsModelManager.replaceTokenizerFromTrustedSource(root, model, "wrong asset".byteInputStream())
        )
        assertEquals("old tokenizer", tokenizer.readText())

        assertTrue(PocketTtsModelManager.replaceTokenizerFromTrustedSource(root, model, correctBytes.inputStream()))
        assertArrayEquals(correctBytes, tokenizer.readBytes())
        assertTrue(PocketTtsModelManager.hasExpectedTokenizer(root, model))
    }

    @Test
    fun archivePathValidationRejectsTraversalAndPlatformPaths() {
        assertTrue(PocketTtsModelManager.isSafeArchivePath("models/flow_lm_main_int8.onnx"))
        assertTrue(PocketTtsModelManager.isSafeArchivePath("voices/rafael.kv"))
        assertFalse(PocketTtsModelManager.isSafeArchivePath("../outside"))
        assertFalse(PocketTtsModelManager.isSafeArchivePath("models/../../outside"))
        assertFalse(PocketTtsModelManager.isSafeArchivePath("C:/outside"))
        assertFalse(PocketTtsModelManager.isSafeArchivePath("voices\\outside.kv"))
        assertFalse(PocketTtsModelManager.isSafeArchivePath("models//unexpected.onnx"))
    }
}
