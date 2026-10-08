package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Codificador de voz (Mimi encoder) do Pocket TTS: transforma o áudio de referência do usuário na
 * "impressão" da voz. Cada idioma tem o seu (os pesos diferem) e fica em `filesDir/pockettts`,
 * copiado para `models/mimi_encoder.onnx` do pacote do idioma quando a voz clonada é usada.
 *
 * O app não baixa este arquivo: ele é montado no aparelho a partir do arquivo oficial da Kyutai
 * que o próprio usuário baixa com a conta dele (ver [PocketEncoderImportador]).
 */
object PocketEncoderManager {
    private const val TAG = "PocketEncoder"
    /** Nome do arquivo DENTRO do pacote (é o que o motor nativo procura). */
    const val FILE_NAME = "mimi_encoder.onnx"
    private const val MIN_BYTES = 1024L * 1024L

    private fun dir(context: Context) = File(context.filesDir, "pockettts")

    /** "pt-BR" → "pt", "en-US" → "en", "es-ES" → "es". */
    private fun codigo(languageTag: String) = languageTag.substringBefore('-').lowercase()

    private fun remoto(languageTag: String) = "mimi_encoder-${codigo(languageTag)}.onnx"

    fun installedFile(context: Context, languageTag: String) = File(dir(context), remoto(languageTag))

    fun isInstalled(context: Context, languageTag: String): Boolean =
        installedFile(context, languageTag).let { it.isFile && it.length() >= MIN_BYTES }

    /** Algum idioma já liberado para clonagem? */
    fun algumInstalado(context: Context): Boolean = listOf("pt-BR", "en-US", "es-ES").any { isInstalled(context, it) }

    /** Garante o codificador do idioma dentro de `models/` do pacote (cópia). Falso se ainda não foi preparado. */
    fun installInto(context: Context, languageTag: String, packRoot: File): Boolean {
        if (!isInstalled(context, languageTag)) return false
        val origem = installedFile(context, languageTag)
        val destino = File(File(packRoot, "models"), FILE_NAME)
        if (destino.isFile && destino.length() == origem.length()) return true
        return runCatching {
            destino.parentFile?.mkdirs()
            val parcial = File(destino.parentFile, "$FILE_NAME.part")
            origem.copyTo(parcial, overwrite = true)
            if (!parcial.renameTo(destino)) {
                parcial.copyTo(destino, overwrite = true)
                parcial.delete()
            }
            true
        }.getOrElse {
            Log.e(TAG, "Não foi possível copiar o codificador para o pacote: ${it.message}")
            false
        }
    }

    fun tamanhoOcupado(context: Context): Long =
        dir(context).listFiles { f -> f.name.startsWith("mimi_encoder-") && f.name.endsWith(".onnx") }
            ?.sumOf { it.length() } ?: 0L

    /** Apaga as cópias dentro dos pacotes; na próxima síntese elas são refeitas a partir do arquivo novo. */
    fun removerCopiasNosPacotes(context: Context) {
        dir(context).listFiles { f -> f.isDirectory }?.forEach { File(File(it, "models"), FILE_NAME).delete() }
    }

    /** Remove os codificadores preparados (e as cópias dentro dos pacotes). As vozes clonadas continuam salvas. */
    fun delete(context: Context) {
        dir(context).listFiles { f -> f.name.startsWith("mimi_encoder-") }?.forEach { it.delete() }
        removerCopiasNosPacotes(context)
    }

    /** Remove só o codificador de um idioma (arquivo, parcial e a cópia dentro dos pacotes). */
    fun delete(context: Context, languageTag: String) {
        val alvo = installedFile(context, languageTag)
        alvo.delete()
        File(alvo.parentFile, "${alvo.name}.part").delete()
        removerCopiasNosPacotes(context)
    }
}
