package com.jonjonesbr.audiobookgen.ui.apoio

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.ReaderActivity
import org.junit.Assert.fail

/** Tempo limite padrão de uma espera (rb-46). Um emulador lento leva alguns segundos para abrir o Leitor. */
const val TEMPO_LIMITE_ESPERA_MS = 30_000L

/**
 * Consulta a ReaderActivity a cada 50 ms (na thread do teste, via [ActivityScenario.onActivity]) até [condicao] ser
 * verdadeira. Falha cedo, com a mensagem do Leitor, se o layout de erro aparecer; ao estourar [timeoutMs], falha com
 * a descrição da espera. Não usar `onView` com a leitura guiada tocando: as esperas leem o estado direto.
 */
fun esperarAte(
    scenario: ActivityScenario<ReaderActivity>,
    descricao: String,
    timeoutMs: Long = TEMPO_LIMITE_ESPERA_MS,
    condicao: (ReaderActivity) -> Boolean
) {
    val fim = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < fim) {
        var pronto = false
        var erroDaTela: String? = null
        scenario.onActivity { a ->
            if (a.findViewById<View>(R.id.layoutReaderErro).visibility == View.VISIBLE) {
                erroDaTela = a.findViewById<TextView>(R.id.tvReaderErro).text.toString()
            } else {
                pronto = condicao(a)
            }
        }
        erroDaTela?.let { fail("Leitor mostrou erro: $it") }
        if (pronto) return
        Thread.sleep(50)
    }
    fail("Tempo esgotado: $descricao")
}
