package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Voz reportada pelo motor TTS já inicializado (nome real da engine + locale + rótulo amigável).
 * [precisaBaixar] reflete [TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED] — o motor conhece a
 * voz (ex.: pacotes pt-BR do Google TTS) mas os dados ainda não foram baixados no aparelho.
 */
data class AndroidVoiceInfo(
    val name: String,
    val locale: Locale,
    val label: String,
    val precisaBaixar: Boolean = false
)

/**
 * Motor TTS nativo Android.
 * Sintetiza texto para arquivo .wav sem dependência de rede ou Chaquopy.
 *
 * Motores de terceiros mal-comportados podem nunca disparar onDone/onError — init() e cada
 * chunk têm timeout para não travar a coroutine indefinidamente.
 */
class AndroidTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "AndroidTtsEngine"
        private const val MAX_CHARS_POR_CHUNK = 3_800  // margem de segurança abaixo do limite de 4000
        // Motores de terceiros podem demorar mais para vincular o serviço na primeira vez.
        private const val INIT_TIMEOUT_MS = 12_000L
        // Alguns motores de terceiros (ex.: Supertonic TTS) sintetizam via modelo de difusão —
        // inferência nativa pesada, sem aceleração de hardware garantida — e podem ficar mais
        // lentos ainda a cada chamada sob throttling térmico do aparelho. 5s bastava para
        // motores leves, mas é curto demais para esses; como o timeout é só um teto (não soma
        // atraso para motores rápidos), um valor bem mais folgado não piora Google TTS/Edge/etc.
        private const val MIN_CHUNK_TIMEOUT_MS = 20_000L
        private const val MS_POR_CHAR_TIMEOUT = 20L
        // Sentinela para timeout — não colide com nenhum TextToSpeech.ERROR_* real (todos < 0
        // mas com valor absoluto pequeno).
        private const val CODIGO_TIMEOUT = Int.MIN_VALUE
        // Convenção usada por alguns motores de terceiros (ex.: MultiTTS) pra "voz genérica,
        // decida você mesmo" — ver comentário em synthesize(). Não-privada: AndroidTtsEngineCache
        // usa a mesma constante ao rotular essa voz na lista exibida ao usuário.
        const val VOZ_GENERICA_PADRAO = "NOT_SET"
        // Header RIFF fixo: "RIFF"(4) + tamanho(4) + "WAVE"(4).
        private const val RIFF_HEADER_SIZE = 12
        private const val BUFFER_COPIA_BYTES = 8192
        // Formato WAV/RIFF: id do chunk tem 4 bytes ("RIFF"/"WAVE"/"data") e o cabeçalho de
        // cada subchunk tem 8 (id 4 + tamanho 4).
        private const val WAV_ID_TAMANHO = 4
        private const val WAV_CHUNK_HEADER_TAMANHO = 8

        /** Traduz um código de retorno do TextToSpeech para uma descrição legível em logs. */
        private fun descreverCodigoTts(code: Int): String = when (code) {
            TextToSpeech.SUCCESS -> "sucesso"
            CODIGO_TIMEOUT -> "timeout (motor não respondeu a tempo)"
            TextToSpeech.ERROR -> "erro genérico (ERROR)"
            TextToSpeech.ERROR_SYNTHESIS -> "falha de síntese (ERROR_SYNTHESIS)"
            TextToSpeech.ERROR_SERVICE -> "falha no serviço do motor (ERROR_SERVICE)"
            TextToSpeech.ERROR_OUTPUT -> "falha ao gravar saída (ERROR_OUTPUT)"
            TextToSpeech.ERROR_NETWORK -> "falha de rede (ERROR_NETWORK)"
            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "timeout de rede (ERROR_NETWORK_TIMEOUT)"
            TextToSpeech.ERROR_INVALID_REQUEST -> "requisição inválida (ERROR_INVALID_REQUEST)"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "dados da voz não instalados (ERROR_NOT_INSTALLED_YET)"
            else -> "código desconhecido ($code)"
        }
    }

    private var tts: TextToSpeech? = null
    private var inicializado = false
    private var enginePackage: String? = null

    /** Inicializa o motor (opcionalmente um pacote específico). Deve ser chamado antes de [synthesize]. */
    suspend fun init(targetPackage: String? = null): Boolean {
        enginePackage = targetPackage
        return try {
            withTimeout(INIT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val listener = TextToSpeech.OnInitListener { status ->
                        inicializado = (status == TextToSpeech.SUCCESS)
                        if (cont.isActive) cont.resume(inicializado)
                    }

                    tts = if (targetPackage != null) {
                        TextToSpeech(context, listener, targetPackage)
                    } else {
                        TextToSpeech(context, listener)
                    }

                    cont.invokeOnCancellation { tts?.shutdown() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Timeout ao inicializar motor '$targetPackage'", e)
            tts?.shutdown()
            tts = null
            inicializado = false
            false
        }
    }

    /** Lista vozes disponíveis no motor já inicializado. */
    fun getAvailableVoices(): List<AndroidVoiceInfo> {
        val t = tts ?: return emptyList()
        return t.voices?.map { v ->
            val precisaBaixar = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
            AndroidVoiceInfo(v.name, v.locale, "${v.locale.displayLanguage} - ${v.name}", precisaBaixar)
        } ?: emptyList()
    }

    /**
     * Sintetiza [texto] para [arquivoSaida] na voz [vozId], na velocidade [speed] (1.0 = normal).
     * Divide em chunks, sintetiza cada um e concatena.
     * Retorna [Result.success] com o arquivo ou [Result.failure] em caso de erro.
     */
    @Suppress("ReturnCount") // cada return é um modo de falha distinto com mensagem própria
    suspend fun synthesize(
        texto: String,
        vozId: String,
        arquivoSaida: File,
        speed: Float = 1.0f
    ): Result<File> {
        if (!inicializado) {
            // Reusa o pacote já armazenado (se houver) em vez de cair no motor padrão do
            // sistema — preserva a escolha de motor do usuário mesmo neste caminho de fallback.
            val ok = init(enginePackage)
            if (!ok) return Result.failure(IllegalStateException("TTS não inicializado"))
        }

        val t = tts ?: return Result.failure(IllegalStateException("TTS é nulo"))

        if (vozId.equals(VOZ_GENERICA_PADRAO, ignoreCase = true)) {
            // Voz-coringa que alguns motores de terceiros expõem (ex.: MultiTTS reporta uma
            // voz literalmente chamada "NOT_SET") — convenção pra "use a voz configurada dentro
            // do próprio app do motor". Não chama t.voice = ... aqui de propósito: deixa o
            // motor decidir sozinho qual voz/idioma usar, em vez de forçar um valor que ele não
            // souber mapear.
            Log.d(TAG, "Voz genérica '$VOZ_GENERICA_PADRAO' — usando o padrão configurado no motor")
        } else {
            // A voz precisa existir NESTE motor exato. Se não existir (ex.: o motor pedido não
            // iniciou e caímos para o motor padrão do sistema em AndroidTtsEngineCache), não dá
            // pra "adivinhar" um idioma a partir do nome — nomes de voz de motores de terceiros
            // (ex.: "filmora_xyz_1") não são locales válidos. Falhar aqui é melhor que sintetizar
            // silenciosamente com uma voz/idioma errado.
            val voz = t.voices?.firstOrNull { it.name == vozId }
                ?: return Result.failure(
                    IllegalStateException("Voz '$vozId' não encontrada no motor atual (${t.defaultEngine})")
                )
            t.voice = voz
        }
        t.setSpeechRate(speed)

        // Divide texto em chunks
        val chunks = dividirEmChunks(texto)
        val tempFiles = mutableListOf<File>()

        return try {
            for ((index, chunk) in chunks.withIndex()) {
                val tempFile = File(context.cacheDir, "tts_chunk_${index}_${System.currentTimeMillis()}.wav")
                val codigo = sintetizarChunk(t, chunk, tempFile)
                if (codigo != TextToSpeech.SUCCESS) {
                    return Result.failure(
                        RuntimeException("Falha no chunk $index (voz=$vozId): ${descreverCodigoTts(codigo)}")
                    )
                }
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    return Result.failure(
                        RuntimeException("Motor retornou sucesso mas o chunk $index ficou vazio (voz=$vozId)")
                    )
                }
                tempFiles.add(tempFile)
            }

            concatenarWav(tempFiles, arquivoSaida)
            Result.success(arquivoSaida)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao sintetizar/concatenar (voz=$vozId): ${e.message}", e)
            Result.failure(e)
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    /** Libera recursos do TTS. Chamar em onDestroy/release. */
    fun release() {
        tts?.shutdown()
        tts = null
        inicializado = false
    }

    // ── Privados ──────────────────────────────────────────────────────────────

    private fun dividirEmChunks(texto: String): List<String> {
        if (texto.length <= MAX_CHARS_POR_CHUNK) return listOf(texto)
        val chunks = mutableListOf<String>()
        var restante = texto
        while (restante.isNotEmpty()) {
            if (restante.length <= MAX_CHARS_POR_CHUNK) {
                chunks.add(restante)
                break
            }
            // Quebra no último ponto/exclamação/interrogação dentro do limite
            val corte = restante.lastIndexOf('.', MAX_CHARS_POR_CHUNK)
                .takeIf { it > MAX_CHARS_POR_CHUNK / 2 }
                ?: restante.lastIndexOf(' ', MAX_CHARS_POR_CHUNK)
                    .takeIf { it > 0 }
                ?: MAX_CHARS_POR_CHUNK
            chunks.add(restante.substring(0, corte + 1).trim())
            restante = restante.substring(corte + 1).trim()
        }
        return chunks
    }

    /** Retorna [TextToSpeech.SUCCESS] (0) em sucesso, ou o código de erro real em falha. */
    private suspend fun sintetizarChunk(
        t: TextToSpeech,
        chunk: String,
        destino: File
    ): Int {
        val timeoutMs = maxOf(MIN_CHUNK_TIMEOUT_MS, chunk.length * MS_POR_CHAR_TIMEOUT)
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val utteranceId = "utt_${System.currentTimeMillis()}"
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId && cont.isActive) cont.resume(TextToSpeech.SUCCESS)
                        }
                        override fun onError(id: String?) {
                            if (id == utteranceId && cont.isActive) cont.resume(TextToSpeech.ERROR)
                        }
                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId && cont.isActive) cont.resume(errorCode)
                        }
                    })
                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                    val result = t.synthesizeToFile(chunk, params, destino, utteranceId)
                    if (result != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resume(result)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Timeout sintetizando chunk (${chunk.length} chars)", e)
            CODIGO_TIMEOUT
        }
    }

    /**
     * Lê/escreve inteiros little-endian de 32 bits em um `ByteArray` — usado pelos campos de
     * tamanho do formato RIFF/WAV. Os literais (0xFF, 8, 16, 24) são a definição do formato, não
     * números arbitrários, por isso a supressão.
     */
    @Suppress("MagicNumber")
    private fun leInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    @Suppress("MagicNumber")
    private fun escreveLeInt32(bytes: ByteArray, offset: Int, valor: Int) {
        bytes[offset] = (valor and 0xFF).toByte()
        bytes[offset + 1] = ((valor shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((valor shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((valor shr 24) and 0xFF).toByte()
    }

    /** Offset (a partir do início do arquivo) e tamanho declarado do subchunk "data" de um WAV. */
    private data class WavDataChunk(val dataOffset: Int, val dataSize: Int)

    /**
     * Localiza o subchunk "data" real dentro de um WAV, em vez de assumir um header fixo de 44
     * bytes — motores TTS diferentes (ex.: alguns motores de terceiros) podem escrever chunks
     * extras (ex.: "fact", "LIST") antes de "data", o que corrompia a concatenação quando o
     * offset era fixo e um parágrafo real (mais longo que a amostra de preview) exigia mais de
     * um chunk sintetizado.
     */
    @Suppress("ReturnCount") // early-returns de parsing: cada um é uma condição de WAV inválido
    private fun localizarDataChunk(file: File): WavDataChunk? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < RIFF_HEADER_SIZE) return null
            val riffHeader = ByteArray(RIFF_HEADER_SIZE)
            raf.readFully(riffHeader)
            if (String(riffHeader, 0, WAV_ID_TAMANHO, Charsets.US_ASCII) != "RIFF" ||
                String(riffHeader, RIFF_HEADER_SIZE - WAV_ID_TAMANHO, WAV_ID_TAMANHO, Charsets.US_ASCII) != "WAVE"
            ) {
                return null
            }

            var offset = RIFF_HEADER_SIZE
            val chunkHeader = ByteArray(WAV_CHUNK_HEADER_TAMANHO)
            while (offset + WAV_CHUNK_HEADER_TAMANHO <= raf.length()) {
                raf.seek(offset.toLong())
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, WAV_ID_TAMANHO, Charsets.US_ASCII)
                val chunkSize = leInt32(chunkHeader, WAV_ID_TAMANHO)
                val dataStart = offset + WAV_CHUNK_HEADER_TAMANHO
                if (chunkId == "data") {
                    return WavDataChunk(dataStart, chunkSize)
                }
                // Chunks têm padding de 1 byte se o tamanho for ímpar (regra do formato RIFF).
                offset = dataStart + chunkSize + (chunkSize and 1)
            }
            return null
        }
    }

    /**
     * Concatena arquivos WAV mantendo o header (até o início do PCM) apenas do primeiro chunk,
     * com os campos de tamanho corrigidos para refletir o total combinado.
     */
    private fun concatenarWav(arquivos: List<File>, saida: File) {
        if (arquivos.isEmpty()) return
        if (arquivos.size == 1) {
            arquivos[0].copyTo(saida, overwrite = true)
            return
        }

        val dataChunks = arquivos.map { file ->
            localizarDataChunk(file)
                ?: throw IOException("WAV inválido (sem subchunk 'data'): ${file.name}")
        }

        // Header = tudo do primeiro arquivo até (exclusive) o início dos dados PCM do "data".
        val headerBytes = ByteArray(dataChunks[0].dataOffset)
        RandomAccessFile(arquivos[0], "r").use { it.readFully(headerBytes) }

        val tamanhosPcm = arquivos.indices.map { i ->
            minOf(dataChunks[i].dataSize.toLong(), arquivos[i].length() - dataChunks[i].dataOffset)
        }
        val totalPcmSize = tamanhosPcm.sum()

        // Corrige os campos de tamanho no header (little-endian): RIFF size (bytes 4-7) e o
        // tamanho do subchunk "data" (últimos 4 bytes do header copiado, logo antes do PCM).
        val riffSize = (headerBytes.size + totalPcmSize - WAV_CHUNK_HEADER_TAMANHO).toInt()
        escreveLeInt32(headerBytes, WAV_ID_TAMANHO, riffSize)
        val dataSizeOffset = headerBytes.size - WAV_ID_TAMANHO
        escreveLeInt32(headerBytes, dataSizeOffset, totalPcmSize.toInt())

        java.io.FileOutputStream(saida).use { out ->
            out.write(headerBytes)
            val buffer = ByteArray(BUFFER_COPIA_BYTES)
            arquivos.forEachIndexed { i, file ->
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(dataChunks[i].dataOffset.toLong())
                    var restante = tamanhosPcm[i]
                    while (restante > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), restante).toInt())
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        restante -= n
                    }
                }
            }
        }
    }
}
