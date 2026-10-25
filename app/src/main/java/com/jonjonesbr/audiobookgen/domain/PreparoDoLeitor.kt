package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Adianta a abertura do leitor quando o próximo passo provável é abrir um livro (tela do livro, cartão
 * "Continuar" da Biblioteca): liga o Python e extrai o texto em segundo plano, que fica no cache
 * ([com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro]). Quando a pessoa toca em ler, falta pouco ou nada.
 * Cada livro é preparado no máximo uma vez por execução do app.
 */
object PreparoDoLeitor {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparados: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    fun preparar(context: Context, caminhoDoLivro: String) {
        if (caminhoDoLivro.isBlank() || !preparados.add(caminhoDoLivro)) return
        val app = context.applicationContext
        escopo.launch {
            // A extração já liga o Python quando o texto não está no cache; se está, nada a fazer além disso.
            ExtractTextUseCase(app).invoke(caminhoDoLivro)
        }
    }
}
