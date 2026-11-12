package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Arquivos ONNX das vozes Paradee (pacote `paradee-ptbr`), em `filesDir/paradee/ptbr`. */
internal object ParadeeModelManager {
    private const val TAG = "ParadeeModelManager"
    private const val SUBDIR = "paradee/ptbr"
    private const val SUFIXO_PARCIAL = ".part"
    private const val MENSAGEM_CORROMPIDO = "As vozes Paradee chegaram corrompidas. Tente baixar de novo."

    fun dir(ctx: Context): File = File(ctx.filesDir, SUBDIR)

    fun arquivo(ctx: Context, spec: ParadeeVozes.Spec): File = File(dir(ctx), spec.arquivo)

    fun url(spec: ParadeeVozes.Spec): String = "${KokoroModelManager.BASE_URL}/$SUBDIR/${spec.arquivo}"

    fun isReady(ctx: Context): Boolean = ParadeeVozes.todas.all { isPronto(ctx, it) }

    fun tamanhoOcupado(ctx: Context): Long {
        val pasta = dir(ctx)
        return if (pasta.exists()) pasta.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    }

    /** Apaga a pasta inteira, inclusive os `.part` de downloads interrompidos. */
    fun delete(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    /**
     * Baixa só os arquivos que faltam (ou estão com tamanho errado). O progresso é a soma dos bytes de
     * todos os arquivos. Cada arquivo baixado é conferido por tamanho e SHA-256; se não bater, é apagado.
     */
    suspend fun download(ctx: Context, progresso: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val total = ParadeeVozes.todas.sumOf { it.bytes }
        var concluido = ParadeeVozes.todas.filter { isPronto(ctx, it) }.sumOf { it.bytes }
        progresso((concluido.toFloat() / total).coerceIn(0f, 1f))
        dir(ctx).mkdirs()
        for (spec in ParadeeVozes.todas) {
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
