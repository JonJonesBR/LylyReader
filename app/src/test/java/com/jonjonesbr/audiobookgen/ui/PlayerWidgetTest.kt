package com.jonjonesbr.audiobookgen.ui

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.jonjonesbr.audiobookgen.domain.PlayerState
import com.jonjonesbr.audiobookgen.service.AcaoWidgetResolvida
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import com.jonjonesbr.audiobookgen.service.PlayerNotificationManager
import com.jonjonesbr.audiobookgen.service.resolverAcaoWidget
import com.jonjonesbr.audiobookgen.util.PlayerStateHolder
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val REQ_TESTE = 999
private const val WIDGET_ID_TESTE = 7

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlayerWidgetTest {

    private lateinit var context: Context
    private lateinit var dummyPi: PendingIntent

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PlayerStateHolder.update(PlayerState())
        dummyPi = PendingIntent.getActivity(
            context,
            REQ_TESTE,
            Intent(context, MeusLivrosActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    @After
    fun tearDown() {
        PlayerStateHolder.update(PlayerState())
    }

    @Test
    fun `sem faixa carregada widget nao cria PendingIntent de servico e abre o app`() {
        val chamadasServico = mutableListOf<Intent>()
        val chamadasAbrir = mutableListOf<Intent>()

        PlayerWidget().construirViews(
            context = context,
            criarPendingServico = { _, _, intent, _ ->
                chamadasServico += intent
                dummyPi
            },
            criarPendingAbrir = { _, _, intent, _ ->
                chamadasAbrir += intent
                dummyPi
            }
        )

        assertTrue(chamadasServico.isEmpty())
        assertEquals(1, chamadasAbrir.size)
        assertEquals(MeusLivrosActivity::class.java.name, chamadasAbrir.first().component?.className)
    }

    @Test
    fun `com faixa carregada botoes usam acoes de foreground com caminho e nome`() {
        PlayerStateHolder.update(
            PlayerState(trackName = "Dom Casmurro", caminho = "/audiobooks/dom.mp3", isPlaying = false)
        )
        val chamadasServico = mutableListOf<Pair<Int, Intent>>()

        PlayerWidget().construirViews(
            context = context,
            criarPendingServico = { _, req, intent, _ ->
                chamadasServico += req to intent
                dummyPi
            },
            criarPendingAbrir = { _, _, _, _ -> dummyPi }
        )

        assertEquals(
            listOf(
                PlayerNotificationManager.ACTION_PLAY_PAUSE,
                PlayerNotificationManager.ACTION_RECUAR,
                PlayerNotificationManager.ACTION_AVANCAR
            ),
            chamadasServico.map { it.second.action }
        )
        chamadasServico.forEach { (_, intent) ->
            assertEquals("/audiobooks/dom.mp3", intent.getStringExtra(AudioPlayerService.EXTRA_CAMINHO))
            assertEquals("Dom Casmurro", intent.getStringExtra(AudioPlayerService.EXTRA_NOME))
        }
    }

    @Test
    fun `onUpdate com fabrica padrao de getForegroundService atualiza AppWidgetManager`() {
        PlayerStateHolder.update(
            PlayerState(trackName = "Memorias Postumas", caminho = "/audiobooks/mp.mp3", isPlaying = true)
        )
        val manager = mockk<AppWidgetManager>(relaxed = true)

        PlayerWidget().onUpdate(context, manager, intArrayOf(WIDGET_ID_TESTE))

        verify(exactly = 1) { manager.updateAppWidget(WIDGET_ID_TESTE, any()) }
    }

    @Test
    fun `resolverAcaoWidget decide entre faixa ativa retomada do intent e encerramento`() {
        assertEquals(
            AcaoWidgetResolvida.EXECUTAR_NA_FAIXA_ATUAL,
            resolverAcaoWidget(temFaixaAtiva = true, caminhoExtra = null) { false }
        )
        assertEquals(
            AcaoWidgetResolvida.RETOMAR_CAMINHO_DO_INTENT,
            resolverAcaoWidget(temFaixaAtiva = false, caminhoExtra = "/livros/ok.mp3") { true }
        )
        assertEquals(
            AcaoWidgetResolvida.ENCERRAR_SEM_FAIXA,
            resolverAcaoWidget(temFaixaAtiva = false, caminhoExtra = "/fora/ok.mp3") { false }
        )
        assertEquals(
            AcaoWidgetResolvida.ENCERRAR_SEM_FAIXA,
            resolverAcaoWidget(temFaixaAtiva = false, caminhoExtra = " ") { true }
        )
    }
}
