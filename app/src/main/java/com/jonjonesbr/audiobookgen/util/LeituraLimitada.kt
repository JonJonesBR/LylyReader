package com.jonjonesbr.audiobookgen.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

private const val TAMANHO_BUFFER_LEITURA = 8 * 1024

/**
 * Lê todos os bytes de um [InputStream] até o teto [limiteBytes], fechando o fluxo ao final.
 * Lança [IOException] assim que o fluxo ultrapassar o limite, evitando alocar respostas ou
 * arquivos grandes demais na memória.
 */
fun InputStream.lerBytesComLimite(limiteBytes: Long): ByteArray {
    require(limiteBytes >= 0L) { "limiteBytes deve ser >= 0" }
    val saida = ByteArrayOutputStream()
    val buffer = ByteArray(TAMANHO_BUFFER_LEITURA)
    var total = 0L
    use { stream ->
        while (true) {
            val lidos = stream.read(buffer)
            if (lidos < 0) break
            total += lidos
            if (total > limiteBytes) {
                throw IOException("Conteúdo excede o limite de $limiteBytes bytes")
            }
            saida.write(buffer, 0, lidos)
        }
    }
    return saida.toByteArray()
}

/** Lê o [InputStream] como texto [charset] respeitando o teto [limiteBytes]. */
fun InputStream.lerTextoComLimite(limiteBytes: Long, charset: Charset = Charsets.UTF_8): String =
    lerBytesComLimite(limiteBytes).toString(charset)
