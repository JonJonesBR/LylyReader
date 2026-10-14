package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.PronunciationDictionary
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Dicionário de pronúncia POR LIVRO: um JSON por livro em `<directory>/<sha256(caminho)>.json`, mesma chave de identidade
 * das outras stores por livro. Usa o mesmo formato de entradas do dicionário global ([PronunciationDictionary.toJson]).
 * Não toca em AppPrefs: o dicionário global continua em `pronuncias_json`.
 */
class PronunciationBookStore(private val directory: File) {
    /** Dicionário do livro; vazio se a identidade é vazia, se não há arquivo ou se o JSON é inválido. */
    fun load(caminho: String): PronunciationDictionary {
        if (caminho.isBlank()) return PronunciationDictionary.EMPTY
        val arquivo = cacheFile(caminho)
        if (!arquivo.isFile) {
            CACHE.remove(arquivo.path)
            return PronunciationDictionary.EMPTY
        }
        // Cache por arquivo: a síntese consulta a cada trecho. Relê só se data ou tamanho mudaram (ou após save/apagar/migrar).
        val marca = Marca(arquivo.lastModified(), arquivo.length())
        CACHE[arquivo.path]?.takeIf { it.marca == marca }?.let { return it.dicionario }
        val dicionario = PronunciationDictionary.fromJson(runCatching { arquivo.readText(Charsets.UTF_8) }.getOrNull())
        CACHE[arquivo.path] = Entrada(marca, dicionario)
        return dicionario
    }

    /** JSON do dicionário do livro, normalizado (sempre um array válido; "[]" se não há nada). */
    fun rawJson(caminho: String): String = load(caminho).toJson()

    /** Salva por substituição atômica para que uma interrupção não deixe JSON parcial. */
    fun save(caminho: String, dicionario: PronunciationDictionary): Boolean {
        if (caminho.isBlank()) return false
        invalidar(caminho)
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) return false
            val target = cacheFile(caminho)
            val temporary = File(directory, "${target.name}.tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(dicionario.toJson().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                temporary.delete()
                return false
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.isFile && target.length() > 0L
        }.getOrDefault(false)
    }

    /** Migração de caminho: leva o dicionário do livro para a identidade nova, se ela ainda não tiver um. */
    fun migrarIdentidade(antigo: String, novo: String): Boolean {
        if (antigo.isBlank() || novo.isBlank() || antigo == novo) return false
        val origem = cacheFile(antigo)
        val destino = cacheFile(novo)
        if (!origem.isFile || destino.exists()) return false
        return runCatching { origem.copyTo(destino, overwrite = false).isFile }.getOrDefault(false)
            .also { invalidar(novo) }
    }

    /** Apaga o dicionário do livro (livro removido da biblioteca). */
    fun apagarIdentidade(caminho: String) {
        if (caminho.isNotBlank()) {
            cacheFile(caminho).delete()
            invalidar(caminho)
        }
    }

    private fun cacheFile(caminho: String): File = File(directory, "${sha256(caminho)}.json")

    private fun invalidar(caminho: String) {
        CACHE.remove(cacheFile(caminho).path)
    }

    private fun sha256(valor: String): String = MessageDigest.getInstance("SHA-256")
        .digest(valor.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        /** Loja na pasta do app (`filesDir/pronuncias_livro`), a mesma que [com.jonjonesbr.audiobookgen.data.DadosDoLivro] migra e apaga. */
        fun doApp(context: Context): PronunciationBookStore =
            PronunciationBookStore(File(context.filesDir, "pronuncias_livro"))

        /** Cache compartilhado entre instâncias (doApp cria uma por chamada), chaveado pelo caminho do arquivo. */
        private val CACHE = java.util.concurrent.ConcurrentHashMap<String, Entrada>()
    }
}

/** Assinatura do arquivo (data e tamanho) usada para saber se o cache do dicionário do livro ainda vale. */
private data class Marca(val modificadoEm: Long, val tamanho: Long)

private class Entrada(val marca: Marca, val dicionario: PronunciationDictionary)
