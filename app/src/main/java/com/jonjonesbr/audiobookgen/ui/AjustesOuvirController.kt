package com.jonjonesbr.audiobookgen.ui

import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.util.AutoRewindMode
import kotlinx.coroutines.launch

/**
 * Seção "Ao ouvir" dos Ajustes (rb-11): repetir ao retomar, player do audiobook pronto e "Restaurar o padrão
 * do som". Extraído da [SettingsActivity]. A recarga das outras seções depois de restaurar volta pelo
 * callback [Callbacks.aoRestaurarSom], sem o controller ler campos da Activity.
 */
class AjustesOuvirController(
    private val activity: AppCompatActivity,
    private val pythonEngine: PythonEngineUseCase,
    private val callbacks: Callbacks,
) {
    data class Callbacks(val aoRestaurarSom: () -> Unit)

    private val appPrefs by lazy { AppPrefs(activity) }
    private val dialogos = DialogosAbertos(activity)

    private val modosAutoRewind = AutoRewindMode.entries
    private val rotulosAutoRewind =
        intArrayOf(R.string.aj_retomar_nao, R.string.aj_retomar_curto, R.string.aj_retomar_longo)
    private val valoresPlayerConversao = listOf(null, "app", "sistema")
    private val rotulosPlayerConversao =
        intArrayOf(R.string.aj_fim_perguntar, R.string.aj_fim_app, R.string.aj_fim_sistema)

    /** Mostra os rótulos atuais de "Repetir ao retomar" e do player do audiobook pronto. */
    fun carregar() {
        activity.findViewById<TextView>(R.id.tvAutoRewindAtual)
            .setText(rotulosAutoRewind[modosAutoRewind.indexOf(appPrefs.autoRewindMode).coerceAtLeast(0)])
        val player = valoresPlayerConversao.indexOf(appPrefs.playerConversaoConcluida).coerceAtLeast(0)
        activity.findViewById<TextView>(R.id.tvPlayerConversaoAtual).setText(rotulosPlayerConversao[player])
    }

    fun escolherAutoRewind() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.aj_retomar)
            .setSingleChoiceItems(
                rotulosAutoRewind.map { activity.getString(it) }.toTypedArray(),
                modosAutoRewind.indexOf(appPrefs.autoRewindMode)
            ) { dialogo, qual ->
                appPrefs.autoRewindMode = modosAutoRewind[qual]
                carregar()
                dialogo.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .let { dialogos.mostrar(it) }
    }

    fun escolherPlayerConversao() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.aj_fim_conversao)
            .setSingleChoiceItems(
                rotulosPlayerConversao.map { activity.getString(it) }.toTypedArray(),
                valoresPlayerConversao.indexOf(appPrefs.playerConversaoConcluida).coerceAtLeast(0)
            ) { dialogo, qual ->
                appPrefs.playerConversaoConcluida = valoresPlayerConversao[qual]
                carregar()
                dialogo.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .let { dialogos.mostrar(it) }
    }

    /** "Restaurar o padrão do som": pausas, tom, repetir ao retomar, qualidade, passos e buffering. */
    fun confirmarRestaurarSom() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.aj_restaurar_som)
            .setMessage(R.string.aj_restaurar_som_msg)
            .setPositiveButton(R.string.aj_restaurar) { _, _ ->
                appPrefs.restaurarPadroesDeSom()
                activity.lifecycleScope.launch {
                    pythonEngine.sincronizarConfiguracoes(
                        appPrefs.motorTts, SecurePreferences.getGeminiKeys(activity), appPrefs.pausaMs
                    )
                }
                callbacks.aoRestaurarSom()
                Toast.makeText(activity, R.string.aj_restaurado, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .let { dialogos.mostrar(it) }
    }
}
