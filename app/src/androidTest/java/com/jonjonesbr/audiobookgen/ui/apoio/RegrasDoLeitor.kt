package com.jonjonesbr.audiobookgen.ui.apoio

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
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
 * no lugar da real, concede a notificação (Android 13+) e deixa a tela acesa. Depois do teste: solta a leitura
 * guiada, para o serviço, restaura o Python, a costura e os ajustes de tela e de fonte (`settings`).
 * Se o teste falhar, tira a captura FALHA_<teste> antes de fechar o Leitor, para que a captura mostre a tela
 * do jeito que o teste a deixou.
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

    /** Valores do sistema antes do teste (`settings get`), devolvidos no fim (rb-46 U3). Nulo: ainda não lidos. */
    private var timeoutDeTelaOriginal: String? = null
    private var fonteOriginal: String? = null
    private var ficarAcesaOriginal: String? = null

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
        manterTelaAcesa()
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
     * Tela que não apaga durante o teste (no emulador o apagamento faz a captura sair preta). Guarda o timeout atual
     * para [restaurarSistema] devolvê-lo.
     */
    private fun manterTelaAcesa() {
        timeoutDeTelaOriginal = comandoDoShell("settings get system screen_off_timeout")
        fonteOriginal = comandoDoShell("settings get system font_scale")
        ficarAcesaOriginal = comandoDoShell("settings get global stay_on_while_plugged_in")
        comandoDoShell("svc power stayon true")
        comandoDoShell("settings put system screen_off_timeout 1800000")
    }

    /**
     * Devolve o timeout de tela, a escala de fonte e o "ficar acesa" ao valor de antes do teste. Falha de shell aqui
     * não esconde o resultado do teste: é só o melhor esforço. Se a leitura inicial não rodou, não mexe em nada.
     */
    private fun restaurarSistema() {
        if (timeoutDeTelaOriginal == null) return
        runCatching {
            restaurarAjuste("global", "stay_on_while_plugged_in", ficarAcesaOriginal)
            restaurarAjuste("system", "screen_off_timeout", timeoutDeTelaOriginal)
            restaurarAjuste("system", "font_scale", fonteOriginal)
        }
    }

    /**
     * Põe o ajuste de volta; se estava sem valor ("null" no `settings get`), apaga o que o teste gravou. [espaco] é o
     * namespace do `settings` (`system` ou `global`).
     */
    private fun restaurarAjuste(espaco: String, chave: String, valor: String?) {
        val atual = valor?.trim()
        if (atual.isNullOrEmpty() || atual == "null") {
            comandoDoShell("settings delete $espaco $chave")
        } else {
            comandoDoShell("settings put $espaco $chave $atual")
        }
    }

    /**
     * Roda um comando do shell e devolve a saída inteira (a leitura espera o fim do comando). Pública para o teste
     * ajustar a fonte (`settings put system font_scale ...`) antes de abrir o Leitor.
     */
    fun comandoDoShell(comando: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(comando)
        ).bufferedReader().use { it.readText() }

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
            restaurarSistema()
            PythonInicio.iniciar = pythonOriginal
            TtsSynthesizer.substitutoParaTestes = substitutoOriginal
        }
    }

    private companion object {
        const val TENTATIVAS_DIALOGO = 3
        const val ESPERA_DIALOGO_MS = 1_000L
    }
}
