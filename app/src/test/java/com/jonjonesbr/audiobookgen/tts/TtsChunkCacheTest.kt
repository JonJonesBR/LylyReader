package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class TtsChunkCacheTest {
    private fun contextComCacheDir(): Context {
        val dir = Files.createTempDirectory("tts_chunk_cache_test").toFile()
        return mockk<Context>(relaxed = true) { every { cacheDir } returns dir }
    }

    private fun escreverWavValido(file: File) {
        val bytes = ByteArray(600)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WAVE".toByteArray().copyInto(bytes, 8)
        file.writeBytes(bytes)
    }

    @Test
    fun aparaApagaOsMaisAntigosPrimeiroEIgnoraArquivosDeOutrosTipos() {
        val context = contextComCacheDir()
        val dir = context.cacheDir
        val agora = System.currentTimeMillis()
        fun criar(nome: String, bytes: Int, idadeMs: Long): File = File(dir, nome).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(agora - idadeMs)
        }
        val velho = criar("tts_v2_velho.wav", 400, 3 * 3_600_000L)
        val meio = criar("tts_v2_meio.wav", 400, 2 * 3_600_000L)
        val novo = criar("tts_v2_novo.wav", 400, 1 * 3_600_000L)
        val outro = criar("capa.jpg", 5000, 9 * 3_600_000L)   // não é do cache de áudio
        val temporario = criar("tts_123.tmp", 5000, 9 * 3_600_000L)

        assertEquals(1200L, TtsChunkCache.tamanhoBytes(context))
        // teto 900 → alvo 720: remover só o mais antigo deixa 800 (> 720), então sai também o seguinte.
        val removidos = TtsChunkCache.aparar(context, tetoBytes = 900, agora = agora)

        assertEquals(2, removidos)
        assertFalse(velho.exists())
        assertFalse(meio.exists())
        assertTrue(novo.exists())
        assertTrue(outro.exists())
        assertTrue(temporario.exists())
    }

    @Test
    fun tetoValeParaOsDoisCachesJuntos() {
        val context = contextComCacheDir()
        val agora = 1_000_000_000_000L
        val pasta = File(context.cacheDir, "audio_chunks").apply { mkdirs() }
        val velhoPython = File(pasta, "a.bin").apply { writeBytes(ByteArray(500)); setLastModified(agora - 5 * 3_600_000L) }
        val novoPython = File(pasta, "b.bin").apply { writeBytes(ByteArray(500)); setLastModified(agora - 1 * 3_600_000L) }
        val novoLeitor = File(context.cacheDir, "tts_v2_x.wav").apply { writeBytes(ByteArray(500)); setLastModified(agora - 2 * 3_600_000L) }

        assertEquals(1500L, TtsChunkCache.tamanhoBytes(context))
        // teto 1000 → alvo 800: sai o mais antigo (python a.bin) e sobram 1000 (> 800), então sai também o seguinte.
        assertEquals(2, TtsChunkCache.aparar(context, tetoBytes = 1000, agora = agora))
        assertFalse(velhoPython.exists())
        assertFalse(novoLeitor.exists())
        assertTrue(novoPython.exists())
    }

    @Test
    fun aparaComTetoZeroNaoRemoveNada() {
        val context = contextComCacheDir()
        val arquivo = File(context.cacheDir, "tts_v2_a.wav").apply { writeBytes(ByteArray(400)); setLastModified(1L) }
        assertEquals(0, TtsChunkCache.aparar(context, tetoBytes = 0))
        assertTrue(arquivo.exists())
    }

    @Test
    fun hashTextoEhDeterministico() {
        assertEquals(TtsChunkCache.hashTexto("mesmo texto"), TtsChunkCache.hashTexto("mesmo texto"))
    }

    @Test
    fun hashTextoDifereParaTextosDiferentes() {
        assertNotEquals(TtsChunkCache.hashTexto("texto A"), TtsChunkCache.hashTexto("texto B"))
    }

    @Test
    fun hashTextoTem16CharsHex() {
        val hash = TtsChunkCache.hashTexto("qualquer coisa")
        assertEquals(16, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun chaveIncluiVozVelocidadeTextoERevisaoDeModelo() {
        val context = contextComCacheDir()
        val a = TtsChunkCache.file(context, "supertonic-f4-pt", 100, "Olá", "modelo-v1")
        assertEquals(a, TtsChunkCache.file(context, "supertonic-f4-pt", 100, "Olá", "modelo-v1"))
        assertNotEquals(a, TtsChunkCache.file(context, "supertonic-f4-pt", 100, "Olá", "modelo-v2"))
        assertNotEquals(a, TtsChunkCache.file(context, "supertonic-f4-pt", 90, "Olá", "modelo-v1"))
        assertNotEquals(a, TtsChunkCache.file(context, "kokoro-pf-dora", 100, "Olá", "modelo-v1"))
        assertNotEquals(a, TtsChunkCache.file(context, "supertonic-f4-pt", 100, "Oi", "modelo-v1"))
    }

    @Test
    fun revisaoIdentificaModelosOficiaisEPacoteByom() {
        assertEquals("supertonic-v2", TtsChunkCache.revisaoModelo("supertonic-f4-pt"))
        assertEquals("kokoro-multilang-v1_0-fp32", TtsChunkCache.revisaoModelo("kokoro-pf-dora"))
        assertEquals(
            "pocket-tts-3.3.0-per-language-tokenizers-v4-decoder-frame-by-frame-rafael-nofilter-pause500-nodash-v2",
            TtsChunkCache.revisaoModelo("pocket-ptbr-rafael")
        )
        assertEquals(
            "pocket-tts-3.3.0-per-language-tokenizers-v4-decoder-frame-by-frame-pause500-nodash-v2",
            TtsChunkCache.revisaoModelo("pocket-en-alba")
        )
        val rafaelPrevious = TtsChunkCache.file(
            contextComCacheDir(), "pocket-ptbr-rafael", 100, "teste",
            "pocket-tts-3.3.0-per-language-tokenizers-v3-50-token-chunks-rafael-lp55-v2"
        )
        val rafaelCurrent = TtsChunkCache.file(contextComCacheDir(), "pocket-ptbr-rafael", 100, "teste")
        assertNotEquals(rafaelPrevious.name, rafaelCurrent.name)
        assertEquals("mms-tts-por-v1", TtsChunkCache.revisaoModelo("mms-por::main"))
        assertEquals("byom-byom-a1b2", TtsChunkCache.revisaoModelo("byom-a1b2::voz"))
    }

    @Test
    fun passosDoPocketDiferentesDoPadraoMudamARevisaoSoDoPocket() {
        val base = "rev"
        assertEquals("rev", TtsChunkCache.revisaoComPassos(base, "pocket-ptbr-rafael", 1))
        assertEquals("rev-passos3", TtsChunkCache.revisaoComPassos(base, "pocket-ptbr-rafael", 3))
        assertEquals("rev", TtsChunkCache.revisaoComPassos(base, "supertonic-f4-pt", 12))
        assertEquals("rev-passos4", TtsChunkCache.revisaoComPassos(base, "supertonic-f4-pt", 4))
        assertEquals("rev", TtsChunkCache.revisaoComPassos(base, "kokoro-pf-dora", 3))
        assertEquals("rev", TtsChunkCache.revisaoComPassos(base, "piper-ptbr-cadu::main-pt", 4))
    }

    @Test
    fun vozAndroidNaoExpoeDoisPontosNoNome() {
        val file = TtsChunkCache.file(contextComCacheDir(), "android::com.pkg::Voz Nome", 100, "texto")
        assertFalse(file.name.contains(":"))
    }

    @Test
    fun duasChamadasSimultaneasCompartilhamUmaSinteseEPublicamAudioCompleto() = kotlinx.coroutines.runBlocking {
        val context = contextComCacheDir()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val final = TtsChunkCache.file(context, "kokoro-pf-dora", 100, "mesmo texto")

        val first = async {
            TtsChunkCache.getOrSynthesize(context, "kokoro-pf-dora", 100, "mesmo texto") { temporary ->
                calls.incrementAndGet()
                started.complete(Unit)
                finish.await()
                escreverWavValido(temporary)
                assertFalse("Cache não deve aparecer antes da síntese terminar", final.exists())
                true
            }
        }
        started.await()
        val second = async {
            TtsChunkCache.getOrSynthesize(context, "kokoro-pf-dora", 100, "mesmo texto") {
                calls.incrementAndGet()
                escreverWavValido(it)
                true
            }
        }
        Thread.sleep(100)
        assertEquals(1, calls.get())
        finish.complete(Unit)

        val resultA = first.await()
        val resultB = second.await()
        assertEquals(1, calls.get())
        assertEquals(final, resultA)
        assertEquals(resultA, resultB)
        assertTrue(TtsChunkCache.arquivoValido(final))
    }
    @Test
    fun cacheInvalidoEhRegeneradoEAudioParcialNaoEhPublicado() = runTest {
        val context = contextComCacheDir()
        val target = TtsChunkCache.file(context, "kokoro-pf-dora", 100, "texto")
        target.writeBytes(ByteArray(700))
        var calls = 0

        val result = TtsChunkCache.getOrSynthesize(context, "kokoro-pf-dora", 100, "texto") {
            calls++
            escreverWavValido(it)
            true
        }

        assertEquals(1, calls)
        assertNotNull(result)
        assertTrue(TtsChunkCache.arquivoValido(target))

        val outraChave = TtsChunkCache.file(context, "kokoro-pf-dora", 100, "falha")
        val falha = TtsChunkCache.getOrSynthesize(context, "kokoro-pf-dora", 100, "falha") {
            it.writeBytes(ByteArray(700))
            true
        }
        assertEquals(null, falha)
        assertFalse(outraChave.exists())
        assertEquals(0, target.parentFile!!.listFiles { file -> file.name.endsWith(".tmp") }!!.size)
    }
}
