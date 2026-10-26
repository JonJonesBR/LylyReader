package com.jonjonesbr.audiobookgen.util

import java.io.File

private val EXTENSOES_DE_AUDIO = setOf("mp3", "m4a", "m4b", "aac", "wav", "ogg", "opus", "mp4")

/**
 * O pedido "abrir audiobook" chega pela MainActivity, que é exportada: qualquer app pode mandar um caminho, e o
 * "Abrir com…" repassa o arquivo a outro app com permissão de leitura. Só um arquivo de áudio existente é aceito
 * (caminho canônico, sem `..`), para ninguém usar esse atalho para pegar livros, logs ou dados internos do app.
 */
fun audiobookCompartilhavel(caminho: String): File? {
    if (caminho.isBlank()) return null
    val arquivo = runCatching { File(caminho).canonicalFile }.getOrNull() ?: return null
    return arquivo.takeIf { it.isFile && it.extension.lowercase() in EXTENSOES_DE_AUDIO }
}
