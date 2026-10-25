package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.TextoExtraidoJson
import java.io.File
import java.security.MessageDigest

/**
 * Texto do livro já extraído (e já limpo para a narração) guardado no aparelho: abrir de novo o mesmo livro
 * não precisa do Python, e o leitor aparece quase na hora. A chave leva o arquivo (caminho, tamanho, data),
 * a instalação do app (regras novas de extração e limpeza invalidam tudo) e, no texto limpo, o formato, o idioma
 * e as palavras excluídas. Guarda os [MAX_ARQUIVOS] textos (bruto e limpo contam um cada) usados mais recentemente.
 */
object CacheDeTextoDoLivro {
    private const val TAG = "CacheDeTextoDoLivro"
    private const val PASTA = "texto_livros"
    private const val MAX_ARQUIVOS = 40

    /** Null quando o arquivo não existe (nada a guardar nem a ler). */
    fun chaveBruto(context: Context, caminho: String): String? {
        val arquivo = File(caminho)
        if (!arquivo.isFile) return null
        return "bruto|$caminho|${arquivo.length()}|${arquivo.lastModified()}|${versaoDoApp(context)}"
    }

    /** Muda a cada instalação ou atualização do app: regras novas de extração e limpeza valem na hora. */
    private fun versaoDoApp(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    fun chaveLimpo(chaveBruto: String, formato: String, idioma: String, blacklist: List<String>): String =
        "limpo|$chaveBruto|$formato|$idioma|${blacklist.joinToString("\u0001")}"

    fun ler(context: Context, chave: String?): TextoExtraido? = lerComRemovidas(context, chave)?.first

    /** Texto limpo e quantas linhas a limpeza removeu. */
    @Suppress("TooGenericExceptionCaught") // cache estragado nunca pode impedir a abertura do livro
    fun lerComRemovidas(context: Context, chave: String?): Pair<TextoExtraido, Int>? {
        val arquivo = arquivoDa(context, chave ?: return null)
        if (!arquivo.isFile) return null
        return try {
            val json = arquivo.readText(Charsets.UTF_8)
            val lido = TextoExtraidoJson.doCache(json) to TextoExtraidoJson.removidasDoCache(json)
            arquivo.setLastModified(System.currentTimeMillis()) // usado agora: fica entre os mais recentes
            lido
        } catch (e: Exception) {
            Log.w(TAG, "Cache de texto ilegível, descartado", e)
            arquivo.delete()
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun gravar(context: Context, chave: String?, texto: TextoExtraido, removidas: Int = 0) {
        val destino = arquivoDa(context, chave ?: return)
        try {
            destino.parentFile?.mkdirs()
            val temporario = File(destino.parentFile, destino.name + ".tmp")
            temporario.writeText(TextoExtraidoJson.paraCache(texto, removidas), Charsets.UTF_8)
            if (!temporario.renameTo(destino)) temporario.delete()
            podar(destino.parentFile)
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível guardar o texto do livro", e)
        }
    }

    private fun podar(pasta: File?) {
        val arquivos = pasta?.listFiles { f -> f.name.endsWith(".json") } ?: return
        arquivos.sortedByDescending { it.lastModified() }.drop(MAX_ARQUIVOS).forEach { it.delete() }
    }

    private fun arquivoDa(context: Context, chave: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(chave.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, PASTA), "$hash.json")
    }
}
