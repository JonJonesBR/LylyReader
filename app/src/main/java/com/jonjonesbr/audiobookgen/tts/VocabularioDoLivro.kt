package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.jonjonesbr.audiobookgen.util.PythonInicio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registra no normalizador (text_normalizer.py) as palavras que o livro escreve em minúsculas:
 * com elas, "CASA" num título é lida como palavra e "OAB" continua soletrada como sigla.
 * O registro roda em segundo plano; [aguardar] segura a síntese até ele terminar (com limite),
 * para o primeiro título do livro já sair certo.
 */
object VocabularioDoLivro {
    private const val TAG = "VocabularioDoLivro"
    private const val ESPERA_MAXIMA_MS = 3_000L
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var registro: Job? = null

    fun registrar(context: Context, chave: String, texto: String) {
        val app = context.applicationContext
        registro = escopo.launch {
            try {
                PythonInicio.garantir(app)
                Python.getInstance().getModule("text_normalizer")
                    .callAttr("registrar_vocabulario", chave, texto)
            } catch (erro: Exception) {
                Log.w(TAG, "Vocabulário do livro não registrado", erro)
            }
        }
    }

    suspend fun aguardar() {
        val job = registro ?: return
        if (job.isActive) withTimeoutOrNull(ESPERA_MAXIMA_MS) { job.join() }
    }
}
