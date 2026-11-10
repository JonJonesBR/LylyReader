package com.jonjonesbr.audiobookgen.player

import com.jonjonesbr.audiobookgen.domain.CabecalhoWav
import com.jonjonesbr.audiobookgen.domain.lerCabecalhoWav
import java.io.File
import java.io.RandomAccessFile

/** Teto do PCM que o AudioTrack carrega de uma vez (MODE_STATIC). Acima disso, o WAV vai para o MediaPlayer. */
internal const val TETO_PCM_BYTES: Long = 8L * 1024 * 1024

private const val BYTES_POR_AMOSTRA_16 = 2
private const val MS_POR_SEGUNDO = 1000L

/** Onde está o PCM dentro do WAV, já validado para o AudioTrack: taxa, canais e tamanho em bytes. */
internal data class PcmWav(val taxa: Int, val canais: Int, val offsetDados: Long, val tamanhoDados: Int)

/**
 * Decide se o WAV pode ir para o AudioTrack: PCM 16 bits, mono ou estéreo, dentro do teto. Corta o excedente que
 * não forma um quadro completo. Devolve null para tudo o que deve ir para o MediaPlayer.
 */
internal fun avaliarWavPcm16(cabecalho: CabecalhoWav?, tetoBytes: Long = TETO_PCM_BYTES): PcmWav? {
    if (cabecalho == null || !cabecalho.comprimivel || cabecalho.tamanhoDados > tetoBytes) return null
    val tamanhoQuadro = cabecalho.canais * BYTES_POR_AMOSTRA_16
    val tamanhoUtil = cabecalho.tamanhoDados - cabecalho.tamanhoDados % tamanhoQuadro
    return PcmWav(cabecalho.taxa, cabecalho.canais, cabecalho.offsetDados, tamanhoUtil.toInt())
        .takeIf { tamanhoUtil > 0 }
}

/** Cabeçalho do WAV em [arquivo], ou null se não der para ler (arquivo ausente, cortado ou não-WAV). */
internal fun cabecalhoDoArquivo(arquivo: File): CabecalhoWav? =
    runCatching { RandomAccessFile(arquivo, "r").use { lerCabecalhoWav(it) } }.getOrNull()

/** Converte quadros (um instante por canal) em milissegundos. Taxa inválida devolve 0. */
internal fun framesParaMs(frames: Int, taxa: Int): Int =
    if (taxa <= 0) 0 else (frames.toLong() * MS_POR_SEGUNDO / taxa).toInt()

/** Velocidade ou tom diferentes de 1,0 exigem PlaybackParams. A 1,0 / 1,0 o áudio vai direto, sem esticamento. */
internal fun precisaDeParametros(speed: Float, tom: Float): Boolean = speed != 1f || tom != 1f

/** Lê só o trecho de PCM do WAV. Devolve null em qualquer falha de leitura. */
internal fun lerPcm(arquivo: File, pcm: PcmWav): ByteArray? = runCatching {
    RandomAccessFile(arquivo, "r").use { raf ->
        raf.seek(pcm.offsetDados)
        ByteArray(pcm.tamanhoDados).also { raf.readFully(it) }
    }
}.getOrNull()
