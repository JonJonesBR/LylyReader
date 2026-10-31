package com.jonjonesbr.audiobookgen.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.SleepTimerManager
import com.jonjonesbr.audiobookgen.util.textosDaSoneca
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Sleep timer do leitor (diálogo + observação da contagem) — extraído de [ReaderRetornoController]
 * pra reduzir o número de funções do arquivo (T2.2). Padrão do projeto: activity + callbacks no
 * construtor, sem acessar campos da Activity.
 */
class ReaderSonecaController(
    private val activity: AppCompatActivity,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val obterTokenSessaoGuided: () -> Long?,
        val pausarGuided: () -> Unit,
        val pausarPlayerSeTocando: () -> Unit,
        val onAtualizarSoneca: (rotulo: String, descricao: String) -> Unit,
    )

    fun mostrarDialogoSleepTimer() {
        // Passa o token da sessão de leitura guiada vigente nesta Activity: se o timer
        // expirar, só ela (não uma sessão futura/diferente) deve ser pausada.
        val token = callbacks.obterTokenSessaoGuided()
        SleepTimerDialog.show(activity, token)
    }

    fun observarSleepTimer() {
        val stm = SleepTimerManager
        activity.lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                stm.finished.collect {
                    callbacks.pausarPlayerSeTocando()
                    // Só pausa a leitura guiada se a soneca foi ligada para ESTA sessão (token
                    // vigente no momento do start()) — evita pausar uma sessão diferente da
                    // que estava tocando quando a soneca foi ligada (ex.: soneca ligada para o
                    // player de áudio convertido, ou para uma leitura guiada já substituída).
                    val token = callbacks.obterTokenSessaoGuided()
                    if (token != null && stm.armedToken == token) {
                        callbacks.pausarGuided()
                    }
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.toast_leitura_pausada_soneca),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        // Mostra o tempo restante da soneca na tela (chip no player guiado), ou "fim do cap." no modo capítulo.
        activity.lifecycleScope.launch {
            activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(stm.remainingMs, stm.fimDoCapitulo) { ms, cap -> activity.textosDaSoneca(ms, cap) }
                    .collect { textos ->
                        // Chip sempre visível: 😴 sem soneca (toque para definir); contagem ou fim do cap. se ativa.
                        callbacks.onAtualizarSoneca(textos.rotulo, textos.descricao)
                    }
            }
        }
    }
}
