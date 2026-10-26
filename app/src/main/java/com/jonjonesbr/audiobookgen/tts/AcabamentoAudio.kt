package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.lerCabecalhoWav
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * "Acabamento do áudio" (Naturalidade da voz, experimental): cada trecho sintetizado por um motor local sai com o
 * mesmo volume médio, e, na leitura guiada, sem o silêncio que o motor deixa nas bordas (as pausas da leitura já
 * são inseridas pelo app). Só trata WAV PCM de 16 bits; qualquer outro arquivo fica como veio.
 *
 * Na geração do audiobook só o volume é igualado: sem FFmpeg no aparelho, o silêncio das bordas é o que separa
 * um bloco do outro no arquivo final.
 */
object AcabamentoAudio {

    /** O que fazer com o trecho. O sufixo entra na chave do cache, para não misturar com o áudio cru. */
    enum class Modo(val sufixo: String) {
        NENHUM(""),
        VOLUME("-vol1"),
        COMPLETO("-acab1")
    }

    /** Volume médio alvo: cerca de -20 dBFS. */
    private const val RMS_ALVO = 3_300.0
    private const val GANHO_MIN = 0.5
    private const val GANHO_MAX = 3.0
    /** O pico depois do ganho não passa de ~-0,5 dBFS. */
    private const val PICO_MAX = 31_000.0
    /** Abaixo disto (em amplitude) é silêncio; ou 1/40 do pico, o que for maior. */
    private const val LIMIAR_SILENCIO_MIN = 120
    private const val FRACAO_DO_PICO = 40
    /** Folga mantida antes da primeira e depois da última fala (respiração, fim de sílaba). */
    private const val MARGEM_INICIO_MS = 30
    private const val MARGEM_FIM_MS = 80
    private const val MS_POR_SEGUNDO = 1_000
    private const val BYTES_POR_AMOSTRA = 2
    private const val TAMANHO_CABECALHO = 44
    private const val BITS = 16
    private const val FORMATO_PCM: Short = 1
    private const val TAMANHO_FMT = 16
    private const val RIFF_SEM_DADOS = 36

    fun modoNaLeitura(context: Context): Modo =
        if (ligado(context)) Modo.COMPLETO else Modo.NENHUM

    fun modoNaConversao(context: Context): Modo =
        if (ligado(context)) Modo.VOLUME else Modo.NENHUM

    private fun ligado(context: Context): Boolean =
        runCatching { AppPrefs(context).acabamentoAudio }.getOrDefault(false)

    /** Aplica [modo] ao [arquivo] no lugar. Falha ou formato não tratado: o arquivo fica como estava. */
    fun aplicar(arquivo: File, modo: Modo) {
        if (modo == Modo.NENHUM) return
        runCatching {
            lerPcm(arquivo)?.let { (amostras, canais, taxa) ->
                val aparado = if (modo == Modo.COMPLETO) aparar(amostras, canais, taxa) else amostras
                if (aparado.isNotEmpty()) gravarPcm(arquivo, igualarVolume(aparado), canais, taxa)
            }
        }
    }

    /** Tira o silêncio das bordas, mantendo uma folga curta. Sem fala detectada, devolve o original. */
    internal fun aparar(amostras: ShortArray, canais: Int, taxa: Int): ShortArray {
        val pico = amostras.maxOfOrNull { abs(it.toInt()) } ?: 0
        val limiar = maxOf(LIMIAR_SILENCIO_MIN, pico / FRACAO_DO_PICO)
        val primeira = amostras.indexOfFirst { abs(it.toInt()) > limiar }
        if (primeira < 0 || canais <= 0 || taxa <= 0) return amostras
        val ultima = amostras.indexOfLast { abs(it.toInt()) > limiar }
        val margemInicio = taxa * canais * MARGEM_INICIO_MS / MS_POR_SEGUNDO
        val margemFim = taxa * canais * MARGEM_FIM_MS / MS_POR_SEGUNDO
        // Corta em fronteira de quadro (todas as amostras de um instante juntas).
        val inicio = (primeira - margemInicio).coerceAtLeast(0).let { it - it % canais }
        val fim = (ultima + 1 + margemFim).coerceAtMost(amostras.size).let { it - it % canais }
        return if (fim > inicio) amostras.copyOfRange(inicio, fim) else amostras
    }

    /** Leva o volume médio ao alvo, sem passar do pico máximo e sem ganhos extremos. */
    internal fun igualarVolume(amostras: ShortArray): ShortArray {
        var soma = 0.0
        var pico = 0
        for (a in amostras) {
            val v = a.toInt()
            soma += v.toDouble() * v
            pico = maxOf(pico, abs(v))
        }
        val rms = if (amostras.isEmpty()) 0.0 else sqrt(soma / amostras.size)
        if (rms < 1.0 || pico == 0) return amostras
        val ganho = (RMS_ALVO / rms).coerceIn(GANHO_MIN, GANHO_MAX).coerceAtMost(PICO_MAX / pico)
        return ShortArray(amostras.size) { i ->
            (amostras[i] * ganho).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun lerPcm(arquivo: File): Triple<ShortArray, Int, Int>? =
        RandomAccessFile(arquivo, "r").use { raf ->
            val cab = lerCabecalhoWav(raf)?.takeIf { it.comprimivel } ?: return null
            val bytes = ByteArray(cab.tamanhoDados.toInt() - cab.tamanhoDados.toInt() % BYTES_POR_AMOSTRA)
            raf.seek(cab.offsetDados)
            raf.readFully(bytes)
            val amostras = ShortArray(bytes.size / BYTES_POR_AMOSTRA)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(amostras)
            Triple(amostras, cab.canais, cab.taxa)
        }

    private fun gravarPcm(arquivo: File, amostras: ShortArray, canais: Int, taxa: Int) {
        val dados = amostras.size * BYTES_POR_AMOSTRA
        val buffer = ByteBuffer.allocate(TAMANHO_CABECALHO + dados).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(RIFF_SEM_DADOS + dados)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(TAMANHO_FMT)
        buffer.putShort(FORMATO_PCM).putShort(canais.toShort()).putInt(taxa)
        buffer.putInt(taxa * canais * BYTES_POR_AMOSTRA).putShort((canais * BYTES_POR_AMOSTRA).toShort())
        buffer.putShort(BITS.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dados)
        amostras.forEach { buffer.putShort(it) }
        val temporario = File(arquivo.parentFile, arquivo.name + ".acab")
        temporario.writeBytes(buffer.array())
        if (!temporario.renameTo(arquivo)) {
            temporario.copyTo(arquivo, overwrite = true)
            temporario.delete()
        }
    }
}
