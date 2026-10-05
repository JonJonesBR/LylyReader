package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import android.content.Context

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OnnxTtsEngine — Síntese TTS local via motores neurais ONNX.
 *
 * Suporta:
 *   - Supertonic 3 (4 ONNX: text_encoder, duration_predictor, vector_estimator, vocoder)
 */
class OnnxTtsEngine(
    private val context: Context,
    /**
     * Quando true (padrão), vozes Supertonic são delegadas ao processo isolado
     * [SupertonicSynthService] via [SupertonicProcessClient] — um crash nativo (ONNX Runtime
     * do Supertonic) não derruba o app. O próprio serviço cria o engine com `false` para rodar
     * a síntese localmente (dentro do processo filho) sem recursão.
     */
    private val allowIsolatedProcessDelegation: Boolean = true
) {
    private val TAG = "OnnxTtsEngine"
    private val modelManager = OnnxModelManager(context)

    private val recommendedThreadCount: Int by lazy {
        // SoCs modernos como Helio G99 (8 núcleos: 2xA76 + 6xA55) se beneficiam de até 4 threads
        // no ONNX Runtime, desde que o intra_op esteja acoplado ao threadpool interno do XNNPACK.
        // Limitar a 2 subutiliza o chip; acima de 4 causa oversubscription nos núcleos lentos.
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }

    // Motivo específico da última falha de síntese de um chunk (Supertonic) — sem isso,
    // synthesize() só sabia dizer "falhou, tente novamente" mesmo quando a causa real (sessão
    // nula, exceção do ONNX Runtime) já tinha sido determinada mais embaixo.
    @Volatile private var lastChunkErrorReason: String? = null

    private fun logSynthFailure(msg: String): FloatArray? { lastChunkErrorReason = msg; Log.e(TAG, msg); return null }

    companion object {
        private const val MAX_TENTATIVAS_PROCESSO_ISOLADO = 2
        private const val TAMANHO_MIN_ARQUIVO_VALIDO = 500
        private const val MAX_CHARS_PER_CHUNK = 450
        // Chunk maior para Supertonic: cada chamada JNI re-parseia o JSON de estilo (~420KB)
        // e o nativo re-divide o texto por frases (~300 chars) inserindo 0,1s de pausa entre
        // elas. Chamadas maiores amortizam o parse/JNI e ganham as pausas naturais do nativo
        // (a concatenação Kotlin entre chunks não insere pausa). 2000 chars ≈ 2min de áudio
        // por chamada, mantendo o pico de memória do processo :supertonic baixo.
        private const val MAX_CHARS_PER_SUPERTONIC_CALL = 500
        private const val SUPERTONIC_SR       = 44100
        // Todas as vozes Supertonic usam o pacote V2 (Supertone/supertonic-2) — multilíngue:
        // en/ko/es/pt/fr no mesmo modelo (~300 MB). Variantes do voice ID só mudam o `lang`
        // passado para o nativo, e o arquivo de estilo (M1.json/F1.json).
        private const val SUPERTONIC_VERSION  = "v2"
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Verificação de modelo
    // ──────────────────────────────────────────────────────────────────────────

    fun isModelDownloaded(voiceId: String): Boolean {
        if (voiceId.startsWith("supertonic-")) {
            // Todas as vozes Supertonic compartilham o pacote V2 multilíngue (en/ko/es/pt/fr).
            return SupertonicAssetManager.isVersionReady(context, SUPERTONIC_VERSION)
        }
        return modelManager.isModelDownloaded(voiceId)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Síntese principal
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun synthesize(
        texto: String,
        voiceId: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        Log.d(TAG, "synthesize() called: voice=$voiceId, text='${texto.take(50)}', output=$outputFile")

        if (!isModelDownloaded(voiceId)) {
            val msg = "Modelo '$voiceId' não baixado. Acesse Configurações → Vozes Offline."
            Log.w(TAG, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        Log.d(TAG, "Modelo verificado: $voiceId está baixado.")

        // Vozes Supertonic rodam em processo isolado: a lib nativa (ONNX Runtime do Supertonic)
        // pode dar abort()/segfault, o que não é capturável em Kotlin — apareceria como o app
        // inteiro fechando sem nenhum log (nem CrashLogWriter, nem exceção Java). Delegando ao
        // :supertonic, um crash mata só o processo filho e o app principal recebe uma falha limpa.
        val isIsolatableVoice = voiceId.startsWith("supertonic-")
        if (isIsolatableVoice && allowIsolatedProcessDelegation) {
            return@withContext sintetizarViaProcessoIsoladoComRetry(texto, voiceId, outputFile)
        }

        try {
            val speedPct = com.jonjonesbr.audiobookgen.data.AppPrefs(context).ritmo
            val speed    = speedPct / 100.0f

            val chunks   = splitIntoChunks(texto, voiceId)
            Log.d(TAG, "Texto dividido em ${chunks.size} chunks.")
            val wavParts = mutableListOf<FloatArray>()
            var sampleRate = SUPERTONIC_SR

            for ((i, chunk) in chunks.withIndex()) {
                Log.d(TAG, "Sintetizando chunk $i/${chunks.size} (${chunk.length} chars) via $voiceId")
                val result = when {
                    // Supertonic via native backend C++/Rust (JNI)
                    voiceId.startsWith("supertonic-") -> { sampleRate = SUPERTONIC_SR
                                                           val r = synthesizeSupertonicNative(chunk, voiceId, speed)
                                                           Log.d(TAG, "Supertonic result: ${r?.size?.toString() ?: "null"} samples")
                                                           r }
                    else -> {
                        Log.e(TAG, "Voice ID '$voiceId' não reconhecido pelo engine.")
                        null
                    }
                }

                if (result == null || result.isEmpty()) {
                    val motivo = lastChunkErrorReason ?: "resultado nulo/vazio sem exceção"
                    Log.e(TAG, "Falha ao sintetizar chunk $i para $voiceId — $motivo")
                    return@withContext Result.failure(RuntimeException("Falha na síntese do chunk $i: $motivo"))
                }
                lastChunkErrorReason = null
                wavParts.add(result)
            }

            // Supertonic: 0,1s de silêncio entre chunks Kotlin — o nativo já insere essa
            // pausa entre frases DENTRO de cada chamada e trunca o silêncio final pelo
            // duration predictor, então emendar direto colava duas frases sem pausa.
            // (FloatArray nasce zerado: basta pular as posições do gap.)
            val gapSamples = if (voiceId.startsWith("supertonic-")) sampleRate / 10 else 0
            val totalGap = gapSamples * (wavParts.size - 1).coerceAtLeast(0)
            val allSamples = FloatArray(wavParts.sumOf { it.size } + totalGap)
            var pos = 0
            for ((idx, part) in wavParts.withIndex()) {
                if (idx > 0) pos += gapSamples
                part.copyInto(allSamples, pos); pos += part.size
            }

            writeWavFile(allSamples, sampleRate, outputFile)

            if (!outputFile.exists() || outputFile.length() < 500) {
                Log.e(TAG, "WAV inválido: size=${outputFile.length()}, samples=${allSamples.size}")
                return@withContext Result.failure(RuntimeException("Arquivo WAV inválido gerado."))
            }

            Log.i(TAG, "Síntese concluída: ${outputFile.length()} bytes @ $sampleRate Hz, ${allSamples.size} samples")
            Result.success(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Erro na síntese offline para '$voiceId': ${e.message}", e)
            CrashLogWriter.log(e, "synthesize", "voiceId=$voiceId, text='${texto.take(50)}'")
            Result.failure(e)
        }
    }

    /**
     * O processo isolado do Supertonic às vezes morre por falta de memória transitória (outro
     * app consumindo RAM naquele instante, não um problema do modelo em si) — reportado pelo
     * usuário como falha aleatória. [SupertonicProcessClient] já reconecta a um processo NOVO
     * (memória fresca) na próxima chamada, então uma única retentativa automática é segura e
     * resolve boa parte dos casos sem mascarar erros de verdade (modelo corrompido, etc., que
     * falhariam de novo com o mesmo motivo e não são retentados).
     */
    private suspend fun sintetizarViaProcessoIsoladoComRetry(
        texto: String,
        voiceId: String,
        outputFile: File
    ): Result<File> {
        for (tentativa in 1..MAX_TENTATIVAS_PROCESSO_ISOLADO) {
            val erro = SupertonicProcessClient.synthesize(context, texto, voiceId, outputFile)
            if (erro == null && outputFile.exists() && outputFile.length() >= TAMANHO_MIN_ARQUIVO_VALIDO) {
                Log.i(TAG, "Síntese (processo isolado) OK: ${outputFile.length()} bytes")
                return Result.success(outputFile)
            }
            val processoMorreu = erro?.contains("processo isolado morreu") == true
            val ultimaTentativa = tentativa == MAX_TENTATIVAS_PROCESSO_ISOLADO
            if (!processoMorreu || ultimaTentativa) {
                val msg = "Falha na síntese de voz offline: ${erro ?: "motivo desconhecido"}"
                return Result.failure(RuntimeException(msg))
            }
            Log.w(TAG, "Processo isolado morreu (tentativa $tentativa/$MAX_TENTATIVAS_PROCESSO_ISOLADO), retentando")
        }
        error("unreachable: loop sempre retorna na última tentativa")
    }

    fun synthesizeSync(texto: String, voiceId: String, outputFile: File): Boolean =
        kotlinx.coroutines.runBlocking {
            synthesize(texto, voiceId, outputFile).isSuccess
        }

    // ──────────────────────────────────────────────────────────────────────────
    //  Supertonic 3
    // ──────────────────────────────────────────────────────────────────────────

    private fun synthesizeSupertonicNative(texto: String, voiceId: String, speed: Float): FloatArray? {
        lastChunkErrorReason = null
        if (!com.brahmadeo.supertonic.tts.SupertonicTTS.isNativeLoaded()) {
            return logSynthFailure("Supertonic: lib nativa não carregada")
        }

        // Idioma do Supertonic (V2 multilíngue: en/ko/es/pt/fr). As variantes "-pt" forçam pt.
        // As multilíngues (M1/F1) detectam o idioma do texto — antes usavam "en" fixo, o que
        // fazia o preditor de duração GAGUEJAR ao ler português ("E-e-esse é é é...").
        val lang = if (voiceId.endsWith("-pt", ignoreCase = true)) {
            "pt"
        } else {
            when (com.jonjonesbr.audiobookgen.util.LanguageDetector.detectLanguage(texto)) {
                "pt-BR" -> "pt"
                "es-ES" -> "es"
                else -> "en"
            }
        }
        val version = SUPERTONIC_VERSION

        val onnxDir = File(context.filesDir, "$version/onnx")
        // Validate all required ONNX/config files exist with non-zero length before native init.
        // Any missing/empty file causes the Rust ort crate to panic-abort the process.
        val requiredOnnxFiles = listOf(
            "duration_predictor.onnx", "text_encoder.onnx",
            "vector_estimator.onnx", "vocoder.onnx",
            "tts.json", "unicode_indexer.json"
        )
        for (name in requiredOnnxFiles) {
            val f = File(onnxDir, name)
            if (!f.exists() || f.length() == 0L) {
                return logSynthFailure("Supertonic: arquivo do modelo ausente/vazio: ${f.absolutePath}")
            }
        }
        val modelPath = onnxDir.absolutePath

        val libDir = context.applicationInfo.nativeLibraryDir
        // Usa a cópia 1.26.0 dedicada ao Supertonic (ver SupertonicTTS.init e build.gradle.kts).
        // ORT_DYLIB_PATH aponta para ela; o dlocate por soname reaproveita a já carregada.
        val libFile = File(libDir, "libonnxruntime_supertonic.so")
        val libPath = if (libFile.exists()) libFile.absolutePath else "libonnxruntime_supertonic.so"

        if (!com.brahmadeo.supertonic.tts.SupertonicTTS.isInitialized(modelPath)) {
            // xnnThreads = recommendedThreadCount (dinâmico): o XNNPACK executa o grosso
            // do vector_estimator e com menos threads deixaria núcleos rápidos ociosos.
            val initSuccess = try {
                com.brahmadeo.supertonic.tts.SupertonicTTS
                    .initialize(modelPath, libPath, recommendedThreadCount, recommendedThreadCount)
            } catch (e: Throwable) {
                Log.e(TAG, "Supertonic init threw: ${e.message}", e)
                lastChunkErrorReason = "init Supertonic lançou: ${e.message ?: e.javaClass.simpleName}"; false
            }
            if (!initSuccess) {
                return null.also { if (lastChunkErrorReason == null) lastChunkErrorReason = "falha ao inicializar" }
            }
        }

        // Garante os estilos embutidos no APK em filesDir (idempotente; cobre o processo
        // isolado :supertonic, que pula o startup pesado da Application).
        SupertonicAssetManager.copyBundledVoiceStyles(context, version)

        // Resolve estilo (HF distribui como M1.json/F1.json — maiúsculo).
        // Aceita também variantes minúsculas para compatibilidade.
        val rawStyleName = voiceId.removePrefix("supertonic-")
            .removeSuffix("-pt")
            .removeSuffix("-PT")
        val styleCandidates = listOf(
            rawStyleName.uppercase(),
            rawStyleName,
            rawStyleName.lowercase()
        ).distinct()
        val voiceStylesDir = File(context.filesDir, "$version/voice_styles")
        val styleFile = styleCandidates
            .map { File(voiceStylesDir, "$it.json") }
            .firstOrNull { it.exists() && it.length() > 0 }
        if (styleFile == null) {
            return logSynthFailure("Supertonic: estilo não encontrado p/ '$voiceId' em $voiceStylesDir")
        }

        // Passos do flow-matching (vector_estimator roda 'steps' vezes — é o custo dominante).
        // Configurável pelo usuário (Configurações): menos passos = mais rápido, menos qualidade.
        val supertonicSteps = com.jonjonesbr.audiobookgen.data.AppPrefs(context).supertonicSteps
            .coerceIn(1, 16)
        val pcmBytes = com.brahmadeo.supertonic.tts.SupertonicTTS.generateAudio(
            text = texto,
            lang = lang,
            stylePath = styleFile.absolutePath,
            speed = speed,
            bufferDuration = 0.0f,
            steps = supertonicSteps,
            gain = 1.0f
        ) ?: return logSynthFailure("Supertonic: generateAudio nativo retornou null (lang=$lang)")

        val floats = FloatArray(pcmBytes.size / 2)
        val buffer = java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in floats.indices) {
            floats[i] = buffer.get(i) / 32768.0f
        }
        return floats
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun writeWavFile(samples: FloatArray, sampleRate: Int, output: File) {
        val numSamples = samples.size
        val dataSize   = numSamples * 2  // int16 = 2 bytes por amostra

        FileOutputStream(output).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(dataSize + 36)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)               // PCM chunk size
                putShort(1)              // AudioFormat: PCM
                putShort(1)              // NumChannels: mono
                putInt(sampleRate)       // SampleRate
                putInt(sampleRate * 2)   // ByteRate
                putShort(2)              // BlockAlign
                putShort(16)             // BitsPerSample
                put("data".toByteArray())
                putInt(dataSize)
            }
            fos.write(header.array())

            val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                val clamped = s.coerceIn(-1.0f, 1.0f)
                pcmBuf.putShort((clamped * 32767).toInt().toShort())
            }
            fos.write(pcmBuf.array())
        }
    }

    private fun splitIntoChunks(texto: String, voiceId: String): List<String> {
        val trimmed = texto.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (voiceId.startsWith("supertonic-")) {
            return splitByChars(trimmed, MAX_CHARS_PER_SUPERTONIC_CALL)
        }
        return splitByChars(trimmed, MAX_CHARS_PER_CHUNK)
    }

    private fun splitByChars(texto: String, limite: Int): List<String> {
        if (texto.length <= limite) return listOf(texto)
        val chunks = mutableListOf<String>()
        var restante = texto
        while (restante.isNotEmpty()) {
            if (restante.length <= limite) {
                chunks.add(restante); break
            }
            val corte = restante.lastIndexOf('.', limite)
                .takeIf { it > limite / 2 }
                ?: restante.lastIndexOf(' ', limite).takeIf { it > 0 }
                ?: limite

            chunks.add(restante.substring(0, corte + 1).trim())
            restante = restante.substring(corte + 1).trim()
        }
        return chunks
    }

    fun releaseAll() {
        // Supertonic não mantém cache de sessões por instância (o nativo é um singleton
        // gerenciado por SupertonicTTS) — nada a liberar aqui hoje.
    }
}
