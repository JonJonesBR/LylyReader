package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.TrechoAudio
import com.jonjonesbr.audiobookgen.domain.trechosDeJson
import java.io.File
import java.security.MessageDigest

/**
 * Início de cada trecho do audiobook gerado (ver [TrechoAudio]), para o leitor destacar o trecho exato que está
 * tocando. Um arquivo por MP3 em `filesDir/sincronia_audio/`; pode ter milhares de trechos, por isso não fica em prefs.
 * Audiobooks gerados antes deste recurso não têm arquivo, e o destaque continua aproximado.
 */
object SincroniaAudioStore {
    private const val PASTA = "sincronia_audio"

    private fun arquivo(ctx: Context, caminhoMp3: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(caminhoMp3.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, PASTA), "$hash.json")
    }

    fun salvarJson(ctx: Context, caminhoMp3: String, json: String?) {
        if (caminhoMp3.isBlank()) return
        val destino = arquivo(ctx, caminhoMp3)
        if (json.isNullOrBlank() || json == "[]") {
            destino.delete()
            return
        }
        runCatching {
            destino.parentFile?.mkdirs()
            val temporario = File(destino.parentFile, destino.name + ".tmp")
            temporario.writeText(json, Charsets.UTF_8)
            if (!temporario.renameTo(destino)) {
                destino.delete()
                temporario.renameTo(destino)
            }
        }
    }

    fun carregar(ctx: Context, caminhoMp3: String): List<TrechoAudio> {
        if (caminhoMp3.isBlank()) return emptyList()
        val origem = arquivo(ctx, caminhoMp3)
        return if (origem.isFile) trechosDeJson(runCatching { origem.readText(Charsets.UTF_8) }.getOrNull()) else emptyList()
    }

    fun remover(ctx: Context, caminhoMp3: String) {
        arquivo(ctx, caminhoMp3).delete()
    }
}
