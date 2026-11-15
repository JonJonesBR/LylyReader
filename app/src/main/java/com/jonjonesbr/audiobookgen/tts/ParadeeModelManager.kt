package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Arquivos ONNX das vozes Paradee (`paradee-ptbr` em `filesDir/paradee/ptbr`
 * e `paradee-v13` em `filesDir/paradee/v13`).
 */
internal object ParadeeModelManager {
    private const val TAG = "ParadeeModelManager"
    private const val SUBDIR_PTBR = "paradee/ptbr"
    private const val SUBDIR_V13 = "paradee/v13"
    private const val SUFIXO_PARCIAL = ".part"
    private const val MENSAGEM_CORROMPIDO = "As vozes Paradee chegaram corrompidas. Tente baixar de novo."

    private fun subdir(pacoteId: String): String = when (pacoteId) {
        ParadeeVozes.PACOTE_ID_V13 -> SUBDIR_V13
        else -> SUBDIR_PTBR
    }

    fun dir(ctx: Context, pacoteId: String = ParadeeVozes.PACOTE_ID): File =
        File(ctx.filesDir, subdir(pacoteId))

    fun arquivo(ctx: Context, spec: ParadeeVozes.Spec): File =
        File(dir(ctx, spec.pacoteId), spec.arquivo)

    fun url(spec: ParadeeVozes.Spec): String =
        spec.url ?: "${KokoroModelManager.BASE_URL}/${subdir(spec.pacoteId)}/${spec.arquivo}"

    fun isReady(ctx: Context, pacoteId: String = ParadeeVozes.PACOTE_ID): Boolean =
        ParadeeVozes.arquivosDoPacote(pacoteId).all { isPronto(ctx, it) }

    fun isProntoParaVoz(ctx: Context, voiceId: String): Boolean =
        ParadeeVozes.spec(voiceId)?.let { isPronto(ctx, it) } ?: false

    fun tamanhoOcupado(ctx: Context, pacoteId: String? = null): Long = when (pacoteId) {
        null -> ParadeeVozes.PACOTES.sumOf { tamanhoOcupado(ctx, it) }
        else -> {
            val pasta = dir(ctx, pacoteId)
            if (pasta.exists()) pasta.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        }
    }

    /** Apaga a pasta do pacote (ou todas, se [pacoteId] for nulo), inclusive `.part`. */
    fun delete(ctx: Context, pacoteId: String? = null) {
        if (pacoteId == null) {
            ParadeeVozes.PACOTES.forEach { dir(ctx, it).deleteRecursively() }
        } else {
            dir(ctx, pacoteId).deleteRecursively()
        }
    }

    /**
     * Baixa só os arquivos que faltam (ou estão com tamanho errado). O progresso é a soma dos bytes de
     * todos os arquivos do pacote. Cada arquivo baixado é conferido por tamanho e SHA-256; se não bater, é apagado.
     */
    suspend fun download(
        ctx: Context,
        pacoteId: String = ParadeeVozes.PACOTE_ID,
        progresso: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val specs = ParadeeVozes.arquivosDoPacote(pacoteId)
        val total = specs.sumOf { it.bytes }
        var concluido = specs.filter { isPronto(ctx, it) }.sumOf { it.bytes }
        progresso((concluido.toFloat() / total).coerceIn(0f, 1f))
        dir(ctx, pacoteId).mkdirs()
        for (spec in specs) {
            if (isPronto(ctx, spec)) continue
            val destino = arquivo(ctx, spec)
            // Retomada: os bytes já guardados no .part contam como baixados desde o início.
            val parcialAntes = File(destino.parentFile, destino.name + SUFIXO_PARCIAL)
                .let { if (it.isFile) it.length() else 0L }
            var baixado = concluido + parcialAntes
            SupertonicAssetManager.downloadFileWithResume(url(spec), destino) { bytes ->
                baixado += bytes
                progresso((baixado.toFloat() / total).coerceIn(0f, 1f))
            }
            if (destino.length() != spec.bytes || sha256Hex(destino) != spec.sha256) {
                Log.e(TAG, "Arquivo ${spec.arquivo} não confere: tamanho=${destino.length()} esperado=${spec.bytes}")
                destino.delete()
                throw IOException(MENSAGEM_CORROMPIDO)
            }
            concluido += spec.bytes
        }
        progresso(1f)
    }

    private fun isPronto(ctx: Context, spec: ParadeeVozes.Spec): Boolean {
        val f = arquivo(ctx, spec)
        return f.isFile && f.length() == spec.bytes
    }
}
