package com.jonjonesbr.audiobookgen.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.jonjonesbr.audiobookgen.domain.OrigemLivro
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

class BibliotecaStoreLimiteTest {

    private lateinit var filesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("bib-limite").toFile()
        context = mockk {
            every { filesDir } returns this@BibliotecaStoreLimiteTest.filesDir
        }
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `copiarComLimite copia stream dentro do teto e calcula SHA-256`() {
        val dados = "Conteudo de teste do livro".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        val saida = ByteArrayOutputStream()

        val lidos = BibliotecaStore.copiarComLimite(
            entrada = ByteArrayInputStream(dados),
            saida = saida,
            digest = digest,
            limiteBytes = dados.size.toLong()
        )

        assertEquals(dados.size.toLong(), lidos)
        assertArrayEquals(dados, saida.toByteArray())
        val esperado = MessageDigest.getInstance("SHA-256").digest(dados)
        assertArrayEquals(esperado, digest.digest())
    }

    @Test
    fun `copiarComLimite interrompe antes de gravar alem do limite`() {
        val limite = 1024L
        val dados = ByteArray(2048) { (it % 127).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val saida = ByteArrayOutputStream()

        try {
            BibliotecaStore.copiarComLimite(
                entrada = ByteArrayInputStream(dados),
                saida = saida,
                digest = digest,
                limiteBytes = limite
            )
            fail("Esperava IOException ao exceder o limite")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("limite"))
        }

        assertTrue("Nao deveria gravar alem do limite", saida.size() <= limite)
    }

    @Test
    fun `importarArquivo recusa arquivo maior que 50 MB antes de abrir stream`() = runTest {
        val origem = File(filesDir, "gigante.epub")
        RandomAccessFile(origem, "rw").use { it.setLength(BibliotecaStore.LIMITE_BYTES + 1L) }

        try {
            BibliotecaStore.importarArquivo(
                ctx = context,
                arquivo = origem,
                nomeOriginal = "gigante.epub",
                origem = OrigemLivro.ARQUIVO,
                chaveOrigem = null
            )
            fail("Esperava IOException para arquivo acima de 50 MB")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("50 MB"))
        }

        val livrosDir = BibliotecaStore.dir(context)
        assertFalse(livrosDir.exists() && livrosDir.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `importarDeUri interrompe copia quando stream ultrapassa 50 MB e apaga temporario`() = runTest {
        val totalEnviar = BibliotecaStore.LIMITE_BYTES + 256L * 1024L
        val resolver = mockk<ContentResolver> {
            every { getType(any()) } returns "application/epub+zip"
            every { openInputStream(any()) } returns StreamSintetico(totalEnviar)
        }
        val ctxComResolver = mockk<Context> {
            every { filesDir } returns this@BibliotecaStoreLimiteTest.filesDir
            every { contentResolver } returns resolver
        }
        val uri = mockk<Uri>()

        try {
            BibliotecaStore.importarDeUri(ctxComResolver, uri, "stream_gigante.epub")
            fail("Esperava IOException quando o stream exceder 50 MB")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("50 MB"))
        }

        val arquivosRestantes = BibliotecaStore.dir(ctxComResolver).listFiles().orEmpty()
        assertTrue("Temporarios .part devem ser apagados no finally", arquivosRestantes.isEmpty())
    }

    private class StreamSintetico(private var restante: Long) : InputStream() {
        override fun read(): Int {
            if (restante <= 0L) return -1
            restante--
            return 0x41
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (restante <= 0L) return -1
            val n = minOf(len.toLong(), restante).toInt()
            b.fill(0x41, off, off + n)
            restante -= n
            return n
        }
    }
}
