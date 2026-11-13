package com.jonjonesbr.audiobookgen.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Nome do arquivo de erro por processo: a falha num processo de voz (:pocket etc.) precisa gerar
 * `lyly_error_<data>_<processo>.txt`, e o processo principal continua com o nome de sempre.
 * Usa a Application padrão para não subir o startup pesado da LylyApplication.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashLogWriterTest {

    private val contexto: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun limparAntes() {
        esquecerInit()
        CrashLogWriter.clearAllLogs(contexto)
    }

    @After
    fun limparDepois() {
        esquecerInit()
    }

    @Test
    fun `crash em processo de voz grava o nome do processo e o stack trace`() {
        CrashLogWriter.init(contexto, processo = "pocket")
        CrashLogWriter.log(throwable = RuntimeException("x"), contextTag = "TAG")

        val arquivos = CrashLogWriter.getLogsDir(contexto)
            .listFiles { f -> f.name.startsWith("lyly_error_") && f.name.endsWith("_pocket.txt") }
            .orEmpty()
        assertEquals(1, arquivos.size)
        assertTrue(arquivos.first().readText().contains("STACK TRACE"))
    }

    @Test
    fun `processo principal continua sem sufixo`() {
        CrashLogWriter.init(contexto)
        CrashLogWriter.log(throwable = RuntimeException("x"), contextTag = "TAG")

        val arquivos = CrashLogWriter.getLogsDir(contexto)
            .listFiles { f -> f.name.startsWith("lyly_error_") && f.name.endsWith(".txt") }
            .orEmpty()
        assertEquals(1, arquivos.size)
        assertTrue(arquivos.first().name.matches(Regex("lyly_error_[0-9TZ-]+\\.txt")))
    }

    @Test
    fun `sem init nao cria arquivo`() {
        CrashLogWriter.getLogsDir(contexto).mkdirs()
        CrashLogWriter.log(throwable = RuntimeException("x"), contextTag = "TAG")

        assertEquals(0, listarLogs().size)
    }

    // O CrashLogWriter é um object: o estado do init fica no carregador de classes do teste.
    private fun esquecerInit() {
        CrashLogWriter::class.java.getDeclaredField("appContext")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun listarLogs(): List<File> = CrashLogWriter.getLogsDir(contexto)
        .listFiles { f -> f.name.startsWith("lyly_error_") && f.name.endsWith(".txt") }
        ?.toList()
        .orEmpty()
}
