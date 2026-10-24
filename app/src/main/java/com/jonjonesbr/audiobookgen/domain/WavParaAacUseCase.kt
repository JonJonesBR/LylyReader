package com.jonjonesbr.audiobookgen.domain

import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

private const val TAG = "WavParaAac"

/** Espaço livre mínimo, como fração do WAV, para tentar a compressão. */
private const val FRACAO_ESPACO_MINIMO = 0.2

/** Diferença máxima entre a duração do WAV e a do AAC gerado. */
private const val TOLERANCIA_DURACAO_MS = 500L

private const val FORMATO_PCM = 1
private const val FORMATO_EXTENSIBLE = 0xFFFE

/**
 * Motores locais gravam WAV com extensão .mp3 (sem FFmpeg). Esta função diz se os primeiros bytes são de um WAV:
 * "RIFF" na posição 0 e "WAVE" na posição 8.
 */
internal fun ehWavDisfarcado(primeirosBytes: ByteArray): Boolean =
    primeirosBytes.size >= 12 &&
        String(primeirosBytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(primeirosBytes, 8, 4, Charsets.US_ASCII) == "WAVE"

/** Cabeçalho lido do WAV: onde começa o PCM, quanto tem e em que formato. */
internal data class CabecalhoWav(
    val formatoTag: Int,
    val canais: Int,
    val taxa: Int,
    val bitsPorAmostra: Int,
    val offsetDados: Long,
    val tamanhoDados: Long
) {
    /** Só PCM 16 bits, mono ou estéreo, é comprimido. Outros formatos ficam como WAV. */
    val comprimivel: Boolean
        get() = (formatoTag == FORMATO_PCM || formatoTag == FORMATO_EXTENSIBLE) &&
            bitsPorAmostra == 16 && canais in 1..2 && taxa > 0 && tamanhoDados > 0

    fun duracaoMs(): Long = tamanhoDados * 1000L / (taxa.toLong() * canais * 2L)
}

/**
 * Lê o cabeçalho RIFF do arquivo, por chunks (sem carregar o áudio). Trata data com tamanho 0xFFFFFFFF ou maior que o
 * arquivo (cortado) limitando ao que existe. Devolve null se não for WAV ou se faltar o chunk fmt ou data.
 */
internal fun lerCabecalhoWav(arquivo: RandomAccessFile): CabecalhoWav? {
    val tamanhoArquivo = arquivo.length()
    arquivo.seek(0)
    val inicio = ByteArray(12)
    if (tamanhoArquivo < 12 || arquivo.read(inicio) != 12 || !ehWavDisfarcado(inicio)) return null
    var formatoTag = -1
    var canais = 0
    var taxa = 0
    var bits = 0
    var offsetDados = -1L
    var tamanhoDados = 0L
    var posicao = 12L
    val cabecalhoChunk = ByteArray(8)
    while (posicao + 8 <= tamanhoArquivo) {
        arquivo.seek(posicao)
        if (arquivo.read(cabecalhoChunk) != 8) break
        val id = String(cabecalhoChunk, 0, 4, Charsets.US_ASCII)
        val tamanho = u32(cabecalhoChunk, 4)
        val corpo = posicao + 8
        when (id) {
            "fmt " -> {
                if (tamanho < 16) return null
                val fmt = ByteArray(16)
                arquivo.seek(corpo)
                if (arquivo.read(fmt) != 16) return null
                formatoTag = u16(fmt, 0)
                canais = u16(fmt, 2)
                taxa = u32(fmt, 4).toInt()
                bits = u16(fmt, 14)
            }
            "data" -> {
                offsetDados = corpo
                tamanhoDados = minOf(tamanho, tamanhoArquivo - corpo).coerceAtLeast(0L)
            }
        }
        if (formatoTag >= 0 && offsetDados >= 0) break
        posicao = corpo + tamanho + (tamanho and 1L)
    }
    if (formatoTag < 0 || offsetDados < 0) return null
    return CabecalhoWav(formatoTag, canais, taxa, bits, offsetDados, tamanhoDados)
}

/** Há espaço livre para o arquivo temporário da compressão (≥ 0,2 × o tamanho do WAV). */
internal fun temEspacoParaCompressao(espacoLivre: Long, tamanhoWav: Long): Boolean =
    espacoLivre.toDouble() >= tamanhoWav * FRACAO_ESPACO_MINIMO

/** A duração do AAC confere com a do WAV dentro da tolerância. Sem duração lida, não confere. */
internal fun duracaoConfere(duracaoWavMs: Long, duracaoAacMs: Long?, toleranciaMs: Long = TOLERANCIA_DURACAO_MS): Boolean =
    duracaoAacMs != null && abs(duracaoWavMs - duracaoAacMs) <= toleranciaMs

/** O arquivo começa com um cabeçalho WAV (RIFF/WAVE). */
internal fun ehWavArquivo(arquivo: File): Boolean {
    if (!arquivo.isFile) return false
    return runCatching {
        arquivo.inputStream().use { entrada ->
            val inicio = ByteArray(12)
            entrada.read(inicio) == 12 && ehWavDisfarcado(inicio)
        }
    }.getOrDefault(false)
}

private fun u16(bytes: ByteArray, deslocamento: Int): Int =
    (bytes[deslocamento].toInt() and 0xFF) or ((bytes[deslocamento + 1].toInt() and 0xFF) shl 8)

private fun u32(bytes: ByteArray, deslocamento: Int): Long =
    u16(bytes, deslocamento).toLong() or (u16(bytes, deslocamento + 2).toLong() shl 16)

private fun duracaoAacMs(arquivo: File): Long? {
    val leitor = MediaMetadataRetriever()
    return try {
        leitor.setDataSource(arquivo.absolutePath)
        leitor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (e: Exception) {
        null
    } finally {
        runCatching { leitor.release() }
    }
}

/**
 * Comprime o WAV disfarçado de .mp3 em AAC (MP4, 64 kbps) no mesmo caminho. Grava num `.tmp` ao lado, valida
 * (tamanho e duração) e só então substitui o original. Devolve true se substituiu.
 *
 * Qualquer falha devolve false e mantém o WAV intacto (com log). Cancelamento da coroutine propaga como
 * CancellationException, também sem tocar no WAV.
 */
suspend fun comprimirWavParaAac(arquivo: File): Boolean = withContext(Dispatchers.IO) {
    val tmp = File(arquivo.parentFile, arquivo.name + ".tmp")
    val job = coroutineContext[Job]
    try {
        val cabecalho = RandomAccessFile(arquivo, "r").use { lerCabecalhoWav(it) } ?: return@withContext false
        if (!cabecalho.comprimivel) return@withContext false
        tmp.delete()
        RandomAccessFile(arquivo, "r").use { raf ->
            codificarPcm16ParaMp4(
                arquivo = raf,
                offsetDados = cabecalho.offsetDados,
                tamanhoDados = cabecalho.tamanhoDados,
                canais = cabecalho.canais,
                taxa = cabecalho.taxa,
                saida = tmp,
                interrompido = { job?.isActive == false }
            )
        }
        check(tmp.isFile && tmp.length() > 0 && tmp.length() < arquivo.length()) { "Arquivo comprimido invalido" }
        check(duracaoConfere(cabecalho.duracaoMs(), duracaoAacMs(tmp))) { "Duracao do AAC diferente do WAV" }
        Files.move(tmp.toPath(), arquivo.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Compressao do audio falhou; mantido o WAV: ${e.message}")
        false
    } finally {
        tmp.delete()
    }
}
