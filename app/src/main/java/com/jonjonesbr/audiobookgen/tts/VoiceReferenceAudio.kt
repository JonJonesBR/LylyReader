package com.jonjonesbr.audiobookgen.tts

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Áudio de referência para a clonagem: grava pelo microfone ou converte um arquivo de áudio para
 * WAV 24 kHz mono (16 bits), o formato que o Pocket TTS espera. Tudo local; nada é enviado.
 */
object VoiceReferenceAudio {
    private const val TARGET_RATE = PocketCustomVoices.SAMPLE_RATE
    private const val MAX_SAMPLES = PocketCustomVoices.MAX_SECONDS * TARGET_RATE
    private const val FRAME_MS = 50

    data class Result(val seconds: Float)

    /** Decodifica qualquer áudio que o Android abra (mp3, m4a, ogg, wav, flac…) para o WAV de referência. */
    fun converterArquivo(context: Context, uri: Uri, destino: File): Result {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val faixa = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("O arquivo não tem áudio.")
            extractor.selectTrack(faixa)
            val formato = extractor.getTrackFormat(faixa)
            val mime = formato.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(formato, null, null, 0)
            codec.start()
            var taxa = if (formato.containsKey(MediaFormat.KEY_SAMPLE_RATE)) formato.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_RATE
            var canais = if (formato.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var fimEntrada = false
            var fimSaida = false
            // Lê no máximo ~35 s decodificados: basta para cortar em 30 s depois de reamostrar.
            val limiteBytes = (PocketCustomVoices.MAX_IMPORT_SECONDS + 5L) * taxa * canais * 2
            while (!fimSaida && pcm.size() < limiteBytes) {
                if (!fimEntrada) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            fimEntrada = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o >= 0 -> {
                        val saida = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size)
                        saida.position(info.offset)
                        saida.get(bytes, 0, info.size)
                        pcm.write(bytes)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fimSaida = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val novo = codec.outputFormat
                        if (novo.containsKey(MediaFormat.KEY_SAMPLE_RATE)) taxa = novo.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (novo.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) canais = novo.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            codec.stop()
            codec.release()
            val curtas = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val amostras = ShortArray(curtas.remaining()).also { curtas.get(it) }
            return salvar(mono(amostras, canais), taxa, destino)
        } finally {
            extractor.release()
        }
    }

    private fun mono(amostras: ShortArray, canais: Int): ShortArray {
        if (canais <= 1) return amostras
        return ShortArray(amostras.size / canais) { i ->
            var soma = 0
            for (c in 0 until canais) soma += amostras[i * canais + c]
            (soma / canais).toShort()
        }
    }

    private fun reamostrar(entrada: ShortArray, de: Int, para: Int): ShortArray {
        if (de == para || entrada.isEmpty()) return entrada
        val saida = ShortArray((entrada.size.toLong() * para / de).toInt())
        val passo = de.toDouble() / para
        for (i in saida.indices) {
            val pos = i * passo
            val a = pos.toInt().coerceAtMost(entrada.size - 1)
            val b = (a + 1).coerceAtMost(entrada.size - 1)
            val frac = pos - a
            saida[i] = (entrada[a] * (1 - frac) + entrada[b] * frac).toInt().toShort()
        }
        return saida
    }

    /**
     * Devolve o trecho de [MAX_SAMPLES] com mais fala: o motor só comporta ~10 s de referência, e um
     * trecho cheio de pausas ou ruído clona pior. Pontua janelas deslizantes (passo de 0,5 s) pela
     * quantidade de quadros de 50 ms com voz e começa a janela no início de um trecho falado.
     */
    internal fun melhorTrecho(dados: ShortArray): ShortArray {
        if (dados.size <= MAX_SAMPLES) return dados
        val quadro = TARGET_RATE * FRAME_MS / 1000
        val nQuadros = dados.size / quadro
        val rms = DoubleArray(nQuadros) { q ->
            var soma = 0.0
            for (i in q * quadro until (q + 1) * quadro) soma += dados[i].toDouble() * dados[i]
            Math.sqrt(soma / quadro)
        }
        val ordenado = rms.sortedArray()
        val limiar = ordenado[(ordenado.size * 0.9).toInt().coerceAtMost(ordenado.lastIndex)] * 0.25
        val voz = BooleanArray(nQuadros) { rms[it] > limiar }
        val janela = MAX_SAMPLES / quadro
        val passo = 500 / FRAME_MS
        var melhor = 0
        var melhorPontos = -1
        var inicio = 0
        while (inicio + janela <= nQuadros) {
            var pontos = 0
            for (q in inicio until inicio + janela) if (voz[q]) pontos++
            if (pontos > melhorPontos) { melhorPontos = pontos; melhor = inicio }
            inicio += passo
        }
        // recua/avança até o começo de um trecho falado (primeiro quadro com voz depois de uma pausa)
        var ajustado = melhor
        for (q in melhor until (melhor + passo * 2).coerceAtMost(nQuadros - janela)) {
            if (voz[q] && (q == 0 || !voz[q - 1])) { ajustado = q; break }
        }
        val de = ajustado * quadro
        return dados.copyOfRange(de, (de + MAX_SAMPLES).coerceAtMost(dados.size))
    }

    /** Reduz um WAV de referência longo (24 kHz mono PCM16) ao melhor trecho, reescrevendo o arquivo. */
    fun aparar(arquivo: File) {
        runCatching {
            if (arquivo.length() <= 44L + MAX_SAMPLES * 2L + TARGET_RATE) return
            val bytes = arquivo.readBytes()
            val curtas = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val amostras = ShortArray(curtas.remaining()).also { curtas.get(it) }
            escreverWav(arquivo, melhorTrecho(amostras), TARGET_RATE)
        }
    }

    /** Reamostra para 24 kHz, escolhe o melhor trecho de 10 s, normaliza o volume e grava o WAV de referência. */
    fun salvar(monoAmostras: ShortArray, taxa: Int, destino: File): Result {
        val dados = melhorTrecho(reamostrar(monoAmostras, taxa, TARGET_RATE)).copyOf()
        val pico = dados.maxOfOrNull { abs(it.toInt()) } ?: 0
        if (pico in 1..20_000) {
            // só sobe volumes baixos (até 3×), sem estourar o pico
            val ganho = (24_000.0 / pico).coerceAtMost(3.0)
            for (i in dados.indices) dados[i] = (dados[i] * ganho).toInt().coerceIn(-32768, 32767).toShort()
        }
        escreverWav(destino, dados, TARGET_RATE)
        return Result(dados.size.toFloat() / TARGET_RATE)
    }

    fun escreverWav(arquivo: File, amostras: ShortArray, taxa: Int) {
        arquivo.parentFile?.mkdirs()
        RandomAccessFile(arquivo, "rw").use { f ->
            f.setLength(0)
            val dadosBytes = amostras.size * 2
            val cab = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            cab.put("RIFF".toByteArray()).putInt(36 + dadosBytes).put("WAVE".toByteArray())
            cab.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(taxa).putInt(taxa * 2).putShort(2).putShort(16)
            cab.put("data".toByteArray()).putInt(dadosBytes)
            f.write(cab.array())
            val corpo = ByteBuffer.allocate(dadosBytes).order(ByteOrder.LITTLE_ENDIAN)
            amostras.forEach { corpo.putShort(it) }
            f.write(corpo.array())
        }
    }

    /** Gravação pelo microfone (exige a permissão RECORD_AUDIO já concedida). */
    class Gravador {
        private var gravador: AudioRecord? = null
        private var thread: Thread? = null
        @Volatile private var gravando = false
        private val acumulado = ByteArrayOutputStream()
        private var taxa = TARGET_RATE
        @Volatile var nivel = 0f
            private set

        val segundos: Float get() = acumulado.size() / 2f / taxa

        @SuppressLint("MissingPermission")
        fun iniciar(): Boolean {
            for (tentativa in intArrayOf(TARGET_RATE, 16_000, 44_100)) {
                val minimo = AudioRecord.getMinBufferSize(tentativa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimo <= 0) continue
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, tentativa, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minimo * 4
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    continue
                }
                taxa = tentativa
                gravador = rec
                acumulado.reset()
                gravando = true
                rec.startRecording()
                thread = Thread {
                    val buf = ShortArray(2048)
                    while (gravando) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        var pico = 0
                        val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) {
                            bytes.putShort(buf[i])
                            pico = maxOf(pico, abs(buf[i].toInt()))
                        }
                        synchronized(acumulado) { acumulado.write(bytes.array()) }
                        nivel = pico / 32768f
                        if (segundos >= PocketCustomVoices.MAX_RECORD_SECONDS) gravando = false
                    }
                }.also { it.start() }
                return true
            }
            return false
        }

        val terminouSozinho: Boolean get() = gravador != null && !gravando

        fun parar(destino: File): Result? {
            gravando = false
            thread?.join(1500)
            gravador?.let { runCatching { it.stop() }; it.release() }
            gravador = null
            val bytes = synchronized(acumulado) { acumulado.toByteArray() }
            if (bytes.size < 2) return null
            val curtas = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val amostras = ShortArray(curtas.remaining()).also { curtas.get(it) }
            return salvar(amostras, taxa, destino)
        }

        fun cancelar() {
            gravando = false
            thread?.join(1500)
            gravador?.let { runCatching { it.stop() }; it.release() }
            gravador = null
        }
    }
}
