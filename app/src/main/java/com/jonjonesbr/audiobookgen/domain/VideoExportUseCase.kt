package com.jonjonesbr.audiobookgen.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

private const val MS_PARA_US = 1_000L
private const val US_POR_SEGUNDO = 1_000_000L

/**
 * Gera um MP4 (imagem estática da capa + o áudio já convertido) usando só as APIs de mídia do
 * próprio Android (MediaExtractor/MediaCodec/MediaMuxer) — sem ffmpeg nem nenhuma dependência
 * nova. Feature experimental: baixo nível, sem cobertura de teste em aparelho real.
 *
 * MediaMuxer só grava áudio AAC em contêiner MP4 (não aceita MP3 direto), então o áudio de
 * origem é decodificado e reencodado pra AAC ([transcodificarAudioParaAac]), e cada pacote vai
 * direto para o muxer, sem acumular o áudio na memória. O vídeo é a capa codificada uma única vez
 * em H.264 ([codificarQuadrosEstaticosH264]) e repetida a cada [INTERVALO_QUADRO_VIDEO_US].
 */
/** [duracaoMaxParteMin] padrão = 11 h 50 (o YouTube aceita até 12 h por vídeo). */
data class OpcoesVideo(
    val resolucao: ResolucaoVideo = ResolucaoVideo.P240,
    val duracaoMaxParteMin: Int = DURACAO_MAX_PARTE_PADRAO_MIN,
    val modo: ModoDivisao = ModoDivisao.MAXIMO
)

const val DURACAO_MAX_PARTE_PADRAO_MIN = 11 * 60 + 50

class VideoExportUseCase {

    /**
     * Grava as partes em [pastaSaida] como `parte_1.mp4`, `parte_2.mp4`… e devolve os arquivos em
     * ordem. [onProgress] recebe 0–100 do total; [onParte] recebe o índice (1…) da parte em gravação.
     */
    suspend fun exportar(
        audioFile: File,
        capa: Bitmap?,
        pastaSaida: File,
        opcoes: OpcoesVideo = OpcoesVideo(),
        fronteirasCapituloUs: List<Long> = emptyList(),
        onProgress: (Int) -> Unit = {},
        onParte: (Int) -> Unit = {}
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            val duracaoUs = obterDuracaoUs(audioFile).coerceAtLeast(1L)
            val quadros = codificarQuadrosComFallback(capa, opcoes.resolucao)
            val contexto = coroutineContext
            val ativo = { contexto.isActive }
            val partes = ArrayList<File>()
            try {
                gravarPartes(audioFile, quadros, duracaoUs, pastaSaida, opcoes, fronteirasCapituloUs, partes, onProgress, onParte, ativo)
            } catch (e: Throwable) {
                partes.forEach { it.delete() }
                throw e
            }
            partes
        }
    }
}

/** Alguns encoders de hardware recusam resoluções pequenas: tenta a escolhida e depois as maiores. */
private fun codificarQuadrosComFallback(capa: Bitmap?, resolucao: ResolucaoVideo): TrilhaCodificada {
    var ultimoErro: Exception? = null
    for (candidata in ResolucaoVideo.values().filter { it.ordinal >= resolucao.ordinal }) {
        try {
            return codificarQuadrosEstaticosH264(prepararBitmapDeVideo(capa, candidata))
        } catch (e: Exception) {
            ultimoErro = e
        }
    }
    throw ultimoErro ?: IllegalStateException("Nenhuma resolucao de video disponivel")
}

private fun prepararBitmapDeVideo(capa: Bitmap?, resolucao: ResolucaoVideo): Bitmap {
    val largura = resolucao.largura
    val altura = resolucao.altura
    val saida = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(saida)
    canvas.drawColor(Color.BLACK)
    if (capa != null && capa.width > 0 && capa.height > 0) {
        val escala = minOf(largura.toFloat() / capa.width, altura.toFloat() / capa.height)
        val larguraCapa = (capa.width * escala).toInt().coerceAtLeast(1)
        val alturaCapa = (capa.height * escala).toInt().coerceAtLeast(1)
        val escalado = Bitmap.createScaledBitmap(capa, larguraCapa, alturaCapa, true)
        canvas.drawBitmap(escalado, (largura - larguraCapa) / 2f, (altura - alturaCapa) / 2f, null)
    }
    return saida
}

private fun obterDuracaoUs(audioFile: File): Long {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(audioFile.absolutePath)
        val formato = indiceTrilhaAudio(extractor)?.let { extractor.getTrackFormat(it) }
        if (formato != null && formato.containsKey(MediaFormat.KEY_DURATION)) {
            return formato.getLong(MediaFormat.KEY_DURATION)
        }
    } finally {
        extractor.release()
    }
    return obterDuracaoViaMetadataRetriever(audioFile)
}

private fun obterDuracaoViaMetadataRetriever(audioFile: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(audioFile.absolutePath)
        val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: error("Nao foi possivel determinar a duracao do audio")
        ms * MS_PARA_US
    } finally {
        retriever.release()
    }
}

@Suppress("LongParameterList")
private fun gravarPartes(
    audioFile: File,
    quadros: TrilhaCodificada,
    duracaoUs: Long,
    pastaSaida: File,
    opcoes: OpcoesVideo,
    fronteirasUs: List<Long>,
    partes: MutableList<File>,
    onProgress: (Int) -> Unit,
    onParte: (Int) -> Unit,
    ativo: () -> Boolean
) {
    val estado = EstadoCodificacao()
    val analisador = AnalisadorDeSilencios()
    var taxa = 0
    val maxParteUs = opcoes.duracaoMaxParteMin.coerceAtLeast(1) * 60L * US_POR_SEGUNDO
    val divisor = DivisorDePartes(
        duracaoTotalUs = duracaoUs,
        maxParteUs = maxParteUs,
        modo = opcoes.modo,
        silencios = { analisador.silenciosPorLimiar() },
        fronteirasUs = fronteirasUs,
        criarSaida = { indice ->
            val arquivo = File(pastaSaida, "parte_${indice + 1}.mp4")
            partes.add(arquivo)
            onParte(indice + 1)
            GravadorMp4EmFluxo(arquivo, quadros) {
                formatoComCsdGarantido(estado, "Encoder AAC nao reportou formato de saida")
            }
        }
    )
    try {
        estado.cancelado = { !ativo() }
        estado.aoReceberAmostra = { amostra -> divisor.receber(amostra) }
        if (duracaoUs > maxParteUs) {
            estado.aoDecodificarPcm = { pcm, canais, taxaPcm ->
                taxa = taxaPcm
                analisador.alimentar(pcm, canais, taxaPcm)
                analisador.descartarAntesDe(estado.ultimoPtsUs - JANELA_BUSCA_CORTE_US * 2)
            }
        }
        transcodificarAudioParaAac(audioFile, duracaoUs, estado, onProgress)
        analisador.finalizar(taxa)
        // O vídeo acompanha o fim REAL do áudio gravado: a duração estimada do MP3 (sem cabeçalho de duração) pode ser menor.
        divisor.finalizar(maxOf(estado.ultimoPtsUs, 1L))
        check(divisor.partesGravadas > 0) { "Nenhuma amostra de audio foi codificada" }
    } finally {
        divisor.liberar()
    }
}

/**
 * Grava no MP4 cada pacote AAC assim que ele sai do encoder. O muxer só inicia na primeira amostra
 * de áudio (quando o formato AAC com csd-0 já é conhecido); os quadros de vídeo — as mesmas poucas
 * amostras-chave repetidas — são intercalados conforme o tempo do áudio avança.
 */
private class GravadorMp4EmFluxo(
    private val destino: File,
    private val quadros: TrilhaCodificada,
    private val formatoAudio: () -> MediaFormat
) : SaidaDeParte {
    private var muxer: MediaMuxer? = null
    private var trilhaVideo = -1
    private var trilhaAudio = -1
    private var proximoQuadro = 0
    private var concluido = false
    private val info = MediaCodec.BufferInfo()

    override fun gravarAudio(amostra: AmostraCodificada) {
        val m = muxer ?: iniciar()
        escreverQuadrosAte(m, amostra.presentationTimeUs, inclusivo = true)
        escrever(m, trilhaAudio, amostra)
    }

    override fun finalizar(duracaoUs: Long) {
        val m = muxer ?: error("Nenhuma amostra de audio foi codificada")
        escreverQuadrosAte(m, duracaoUs, inclusivo = false)
        m.stop()
        concluido = true
    }

    override fun liberar() {
        muxer?.let { m ->
            if (!concluido) runCatching { m.stop() }
            m.release()
        }
        muxer = null
    }

    private fun iniciar(): MediaMuxer {
        val m = MediaMuxer(destino.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer = m
        trilhaVideo = m.addTrack(quadros.format)
        trilhaAudio = m.addTrack(formatoAudio())
        m.start()
        return m
    }

    private fun escreverQuadrosAte(m: MediaMuxer, limiteUs: Long, inclusivo: Boolean) {
        while (true) {
            val pts = proximoQuadro * INTERVALO_QUADRO_VIDEO_US
            if (if (inclusivo) pts > limiteUs else pts >= limiteUs) return
            val modelo = quadros.amostras[proximoQuadro % quadros.amostras.size]
            escrever(m, trilhaVideo, AmostraCodificada(modelo.bytes, pts, modelo.flags))
            proximoQuadro++
        }
    }

    private fun escrever(m: MediaMuxer, trilha: Int, amostra: AmostraCodificada) {
        info.set(0, amostra.bytes.size, amostra.presentationTimeUs, amostra.flags)
        m.writeSampleData(trilha, ByteBuffer.wrap(amostra.bytes), info)
    }
}
