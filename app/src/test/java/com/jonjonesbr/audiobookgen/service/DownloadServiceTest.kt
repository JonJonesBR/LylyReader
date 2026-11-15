package com.jonjonesbr.audiobookgen.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashLogWriter.clearAllLogs(context)
        CrashLogWriter.init(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
        CrashLogWriter.clearAllLogs(context)
        CrashLogWriter::class.java.getDeclaredField("appContext")
            .apply { isAccessible = true }
            .set(null, null)
    }

    @Test
    fun `start registra falha no logcat e no CrashLogWriter quando startForegroundService falha`() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>(), any()) } returns 0
        val ctx = spyk(context)
        every { ctx.startForegroundService(any()) } throws SecurityException("bloqueado em segundo plano")

        DownloadService.start(ctx)

        verify(exactly = 1) { Log.w("DownloadService", any<String>(), any<SecurityException>()) }
        val arquivos = CrashLogWriter.getLogFiles(context)
        assertEquals(1, arquivos.size)
        val conteudo = arquivos.first().readText()
        assertTrue(conteudo.contains("DownloadService.start"))
        assertTrue(conteudo.contains("SecurityException"))
    }
}
