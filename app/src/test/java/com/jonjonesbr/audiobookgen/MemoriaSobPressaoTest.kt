package com.jonjonesbr.audiobookgen

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.player.MediaPlayerEngineImpl
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.ui.CapasDaBiblioteca
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoriaSobPressaoTest {

    private val gestoresCriados = mutableListOf<GuidedPlayerManager>()

    @Before
    fun configuracao() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        CapasDaBiblioteca.limparCache()
    }

    @After
    fun limpeza() {
        gestoresCriados.forEach { runCatching { it.release() } }
        gestoresCriados.clear()
        CapasDaBiblioteca.limparCache()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    @Suppress("DEPRECATION")
    fun nivelLeveNaoDisparaAlivio() {
        val registro = RegistroAlivio()
        aliviarMemoriaSobPressao(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, registro.portas)
        assertFalse(registro.reduziuBitmaps)
        assertFalse(registro.limpouBitmaps)
        assertFalse(registro.soltouAudioMemoria)
        assertFalse(registro.aparouAudioDisco)
        assertFalse(registro.coletouPython)
    }

    @Test
    @Suppress("DEPRECATION")
    fun pressaoModeradaReduzBitmapsESoltaDemaisCaches() {
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ).forEach { nivel ->
            val registro = RegistroAlivio()
            aliviarMemoriaSobPressao(nivel, registro.portas)
            assertTrue(registro.reduziuBitmaps)
            assertFalse(registro.limpouBitmaps)
            assertTrue(registro.soltouAudioMemoria)
            assertTrue(registro.aparouAudioDisco)
            assertTrue(registro.coletouPython)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun pressaoCriticaLimpaBitmapsESoltaDemaisCaches() {
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ).forEach { nivel ->
            val registro = RegistroAlivio()
            aliviarMemoriaSobPressao(nivel, registro.portas)
            assertFalse(registro.reduziuBitmaps)
            assertTrue(registro.limpouBitmaps)
            assertTrue(registro.soltouAudioMemoria)
            assertTrue(registro.aparouAudioDisco)
            assertTrue(registro.coletouPython)
        }
    }

    @Test
    fun cacheDeCapasReduzPelaMetadeELimpaPorCompleto() {
        val bitmaps = (1..QTD_BITMAPS_CACHE).map { indice ->
            Bitmap.createBitmap(LADO_BITMAP_PX, LADO_BITMAP_PX, Bitmap.Config.ARGB_8888).also { bmp ->
                CapasDaBiblioteca.colocarEmCacheParaTeste("capa_$indice", bmp)
            }
        }
        assertTrue(CapasDaBiblioteca.tamanhoCacheKb() > TETO_REDUZIDO_KB)

        CapasDaBiblioteca.reduzirCache()
        assertEquals(TETO_REDUZIDO_KB, CapasDaBiblioteca.tamanhoCacheKb())

        CapasDaBiblioteca.limparCache()
        assertEquals(0, CapasDaBiblioteca.tamanhoCacheKb())
        bitmaps.forEach { it.recycle() }
    }

    @Test
    fun leituraGuiadaPreservaTrechoAtualESeguinteQuandoTocando() {
        val manager = criarManagerParaTeste()
        val indices = listOf(0, 1, 2, INDICE_TRES, INDICE_QUATRO)
        manager.popularCacheParaTeste(indicesPrefetch = indices, indiceAtivo = 1, tocando = true)

        manager.soltarCacheEmMemoria()

        assertEquals(setOf(1, 2), manager.indicesEmCacheParaTeste())
        assertTrue(manager.temOracoesEmCacheParaTeste())
        assertEquals(GuidedState(index = 1, isPlaying = true, isLoading = false), manager.state.value)
    }

    @Test
    fun leituraGuiadaEsvaziaCacheQuandoParada() {
        val manager = criarManagerParaTeste()
        val indices = listOf(0, 1, 2, INDICE_TRES)
        manager.popularCacheParaTeste(indicesPrefetch = indices, indiceAtivo = -1, tocando = false)

        manager.soltarCacheEmMemoria()

        assertTrue(manager.indicesEmCacheParaTeste().isEmpty())
        assertFalse(manager.temOracoesEmCacheParaTeste())
        assertEquals(GuidedState(index = -1, isPlaying = false, isLoading = false), manager.state.value)
    }

    private fun criarManagerParaTeste(): GuidedPlayerManager {
        mockkConstructor(MediaPlayerEngineImpl::class)
        every { anyConstructed<MediaPlayerEngineImpl>().setCompletionListener(any()) } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().setErrorListener(any()) } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().setVolume(any()) } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().release() } just Runs
        every { anyConstructed<MediaPlayerEngineImpl>().isPlaying() } returns false
        every { anyConstructed<MediaPlayerEngineImpl>().getDuration() } returns 0
        every { anyConstructed<MediaPlayerEngineImpl>().getCurrentPosition() } returns 0
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pythonUseCase = mockk<PythonEngineUseCase>(relaxed = true)
        val escopo = TestScope(UnconfinedTestDispatcher())
        return GuidedPlayerManager(context, pythonUseCase, escopo).also { gestoresCriados.add(it) }
    }

    private class RegistroAlivio {
        var reduziuBitmaps = false
        var limpouBitmaps = false
        var soltouAudioMemoria = false
        var aparouAudioDisco = false
        var coletouPython = false

        val portas = PortasAlivioMemoria(
            reduzirBitmaps = { reduziuBitmaps = true },
            limparBitmaps = { limpouBitmaps = true },
            soltarAudioMemoria = { soltouAudioMemoria = true },
            apararAudioDisco = { aparouAudioDisco = true },
            coletarPythonSeIniciado = { coletouPython = true }
        )
    }

    private companion object {
        private const val LADO_BITMAP_PX = 512
        private const val QTD_BITMAPS_CACHE = 4
        private const val TETO_REDUZIDO_KB = 3 * 1024
        private const val INDICE_TRES = 3
        private const val INDICE_QUATRO = 4
    }
}
