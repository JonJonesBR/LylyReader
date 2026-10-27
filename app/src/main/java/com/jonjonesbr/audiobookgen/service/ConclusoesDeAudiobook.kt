package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.data.AppPrefs
import kotlinx.coroutines.flow.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Buffer dos avisos: conclusões seguidas numa tela só. Se lotar, descarta as mais antigas. */
private const val BUFFER_AVISOS = 16

/**
 * Audiobooks concluídos que a pessoa ainda não viu: alimenta o selo da aba Audiobooks na barra inferior.
 *
 * O contador fica em [AppPrefs] e sobrevive a fechar o app. O fluxo [eventos] é só um aviso ao vivo: não guarda nada,
 * então quem não estiver coletando (tela parada ou fechada) recebe apenas o contador ao voltar. O [ConversionWorker]
 * roda no processo principal, então o fluxo chega às telas abertas.
 */
object ConclusoesDeAudiobook {

    private val avisos = MutableSharedFlow<String>(
        extraBufferCapacity = BUFFER_AVISOS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Título de cada audiobook concluído com sucesso, entregue só a quem está coletando no momento. */
    val eventos: SharedFlow<String> = avisos

    /** Soma uma conclusão ao contador e avisa as telas visíveis. Chamado pelo worker ao salvar o MP3. */
    @Synchronized
    fun registrar(context: Context, titulo: String) {
        val prefs = AppPrefs(context)
        prefs.audiobooksConcluidosNaoVistos = prefs.audiobooksConcluidosNaoVistos + 1
        avisos.tryEmit(titulo)
    }

    /** Zera o contador: a pessoa abriu a aba Audiobooks. */
    @Synchronized
    fun marcarVistos(context: Context) {
        AppPrefs(context).audiobooksConcluidosNaoVistos = 0
    }
}
