package com.jonjonesbr.audiobookgen.util

import androidx.annotation.StringRes
import com.jonjonesbr.audiobookgen.R
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONException

private const val PROFUNDIDADE_MAXIMA_CAUSAS = 5

/**
 * Converte uma exceção técnica numa mensagem amigável para o usuário (ID de string localizada).
 * O detalhe técnico (e.message) deve ir só para o log, nunca para a tela.
 */
@StringRes
fun mensagemDeErro(t: Throwable): Int {
    var atual: Throwable? = t
    var profundidade = 0
    while (atual != null && profundidade < PROFUNDIDADE_MAXIMA_CAUSAS) {
        categoriaDeErro(atual)?.let { return it }
        atual = atual.cause?.takeIf { it !== atual }
        profundidade++
    }
    return R.string.erro_amigavel_generico
}

@StringRes
private fun categoriaDeErro(t: Throwable): Int? {
    val mensagem = t.message.orEmpty()
    return when {
        t is UnknownHostException || t is ConnectException ||
            t is SocketTimeoutException || t is SSLException ->
            R.string.erro_amigavel_sem_conexao
        t is IOException && (mensagem.contains("ENOSPC") || mensagem.contains("No space left")) ->
            R.string.erro_amigavel_sem_espaco
        t is FileNotFoundException -> R.string.erro_amigavel_arquivo_ilegivel
        t is JSONException || mensagem.contains("Unexpected token") ->
            R.string.erro_amigavel_formato
        else -> null
    }
}
