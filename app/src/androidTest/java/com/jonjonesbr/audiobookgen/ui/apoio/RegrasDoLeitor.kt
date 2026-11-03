package com.jonjonesbr.audiobookgen.ui.apoio

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedReadingService
import com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
import com.jonjonesbr.audiobookgen.ui.ReaderActivity
import com.jonjonesbr.audiobookgen.util.PythonInicio
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * Regra dos testes de interface do Leitor (rb-46). Antes de cada teste: fecha diálogos do sistema que travem a tela
 * (ANR do launcher no emulador recém-ligado), apaga as preferências do app, desliga o Python, põe a voz falsa
 * no lugar da real e concede a notificação (Android 13+). Depois do teste: solta a leitura guiada, para o serviço
 * e restaura o Python e a costura. Se o teste falhar, tira a captura FALHA_<teste> antes de fechar o Leitor, para
 * que a captura mostre a tela do jeito que o teste a deixou.
 *
 * O cenário do Leitor é aberto por [abrir], e não por `ActivityScenario.launch(...).use { }`: assim a regra é dona
 * dele e o fecha só no fim, depois da captura de falha.
 *
 * A voz começa curta (1,5 s por trecho); o teste que precisa de outra chama [usarVoz] no corpo.
 */
class RegrasDoLeitor : TestRule {

    /** A voz falsa em uso neste teste (pronta depois que a regra prepara o teste). */
    lateinit var vozFalsa: VozFalsa
        private set

    /** Cenário do Leitor aberto por [abrir] neste teste, se houver; a regra o fecha no fim. */
    private var cenario: ActivityScenario<ReaderActivity>? = null

    /** Troca a voz falsa do teste em curso (a costura passa a devolver esta). */
    fun usarVoz(voz: VozFalsa) {
        vozFalsa = voz
        TtsSynthesizer.substitutoParaTestes = { vozFalsa }
    }

    /** Abre a ReaderActivity com [intent]; o cenário fica com a regra, que o fecha depois da captura de falha. */
    fun abrir(intent: Intent): ActivityScenario<ReaderActivity> =
        ActivityScenario.launch<ReaderActivity>(intent).also { cenario = it }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentacao = InstrumentationRegistry.getInstrumentation()
            val app = instrumentacao.targetContext
            val pythonOriginal = PythonInicio.iniciar
            val substitutoOriginal = TtsSynthesizer.substitutoParaTestes
            try {
                preparar(instrumentacao, app)
                base.evaluate()
            } catch (erro: Throwable) {
                // Captura antes de fechar o Leitor (o finally fecha). Falha de I/O aqui não pode esconder o erro.
                runCatching { Capturas.capturar("FALHA_${description.methodName}") }
                throw erro
            } finally {
                runCatching { cenario?.close() }
                cenario = null
                desmontar(instrumentacao, app, pythonOriginal, substitutoOriginal)
            }
        }
    }

    private fun preparar(instrumentacao: Instrumentation, app: Context) {
        fecharDialogosDoSistema()
        apagarPreferenciasDoApp(app)
        PythonInicio.iniciar = { throw IllegalStateException("Sem Python nos testes de interface") }
        usarVoz(VozFalsa.curta(app))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentacao.uiAutomation.grantRuntimePermission(
                app.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    /**
     * Diálogo "X isn't responding" (ANR): clica em "Wait" (nunca em "Close app"), até 3 vezes. Pública para os
     * testes chamarem logo antes de um toque ou de uma captura, porque o ANR do launcher pode surgir no meio do teste.
     */
    fun fecharDialogosDoSistema() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        repeat(TENTATIVAS_DIALOGO) {
            val anr = device.hasObject(By.textContains("isn't responding")) ||
                device.hasObject(By.res("android", "aerr_wait"))
            if (!anr) return
            val botao = device.findObject(By.res("android", "aerr_wait")) ?: device.findObject(By.text("Wait"))
            if (botao != null) botao.click() else device.pressBack()
            Thread.sleep(ESPERA_DIALOGO_MS)
        }
    }

    /** Apaga cada arquivo de shared_prefs, para o teste começar sem preferências de testes anteriores. */
    private fun apagarPreferenciasDoApp(app: Context) {
        File(app.dataDir, "shared_prefs").listFiles()?.forEach { arquivo ->
            app.deleteSharedPreferences(arquivo.name.removeSuffix(".xml"))
        }
    }

    /** Solta a leitura guiada na thread principal, para o serviço e devolve o Python e a costura ao normal. */
    private fun desmontar(
        instrumentacao: Instrumentation,
        app: Context,
        pythonOriginal: (Context) -> Unit,
        substitutoOriginal: ((String) -> TtsSynthesizer)?
    ) {
        try {
            instrumentacao.runOnMainSync {
                GuidedPlaybackBridge.manager?.release()
                if (GuidedReadingService.ativo) GuidedReadingService.stop(app)
            }
        } finally {
            PythonInicio.iniciar = pythonOriginal
            TtsSynthesizer.substitutoParaTestes = substitutoOriginal
        }
    }

    private companion object {
        const val TENTATIVAS_DIALOGO = 3
        const val ESPERA_DIALOGO_MS = 1_000L
    }
}
