package com.jonjonesbr.audiobookgen.domain

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private const val BYTE_ID3_I = 0x49.toByte()
private const val BYTE_ID3_D = 0x44.toByte()
private const val BYTE_ID3_3 = 0x33.toByte()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CompartilharMp3LegadoTest {

    private lateinit var context: Context
    private val arquivosCriados = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        arquivosCriados.forEach { it.delete() }
    }

    @Test
    fun `exportar no modo legado copia mp3 para Downloads e devolve uri file`() = runBlocking {
        val origem = File(context.filesDir, "audiobook_legado_teste.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(BYTE_ID3_I, BYTE_ID3_D, BYTE_ID3_3, 0x03, 0x00))
        }
        arquivosCriados.add(origem)

        val resultado = DocumentExportUseCase(context).exportar(origem, pastaSafUri = null).getOrThrow()

        assertEquals("Downloads", resultado.folderName)
        assertEquals("file", resultado.destUri.scheme)
        val destino = File(requireNotNull(resultado.destUri.path))
        arquivosCriados.add(destino)
        assertTrue(destino.isFile)
        assertEquals(origem.readBytes().toList(), destino.readBytes().toList())
    }

    @Test
    fun `fileProvider gera uri content para mp3 em filesDir e monta intent de compartilhamento`() {
        val mp3 = File(context.filesDir, "compartilhar_legado.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(BYTE_ID3_I, BYTE_ID3_D, BYTE_ID3_3, 0x03, 0x00))
        }
        arquivosCriados.add(mp3)

        val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", mp3)
        val mime = mimeParaCompartilhar(context, shareUri, mp3)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        assertEquals("content", shareUri.scheme)
        assertEquals("audio/mpeg", intent.type)
        @Suppress("DEPRECATION")
        val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(shareUri, streamUri)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun `fileProvider recusa caminho fora de file_paths e mimeParaCompartilhar mantem fallback audio mpeg`() {
        val foraDoEscopo = File(context.cacheDir, "fora_do_file_paths.mp3").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(BYTE_ID3_I, BYTE_ID3_D, BYTE_ID3_3, 0x03, 0x00))
        }
        arquivosCriados.add(foraDoEscopo)

        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", foraDoEscopo)
        }

        val mime = mimeParaCompartilhar(context, Uri.fromFile(foraDoEscopo), arquivo = null)
        assertEquals("audio/mpeg", mime)
    }
}
