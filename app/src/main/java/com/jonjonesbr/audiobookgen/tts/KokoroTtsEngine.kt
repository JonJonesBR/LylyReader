package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.DivisorDeOracoes
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * KokoroTtsEngine — síntese TTS neural local via Sherpa-ONNX (Kokoro-82M, bundle
 * kokoro-multi-lang-v1_0 (FP32) da k2-fsa). Ver PLANO_MELHORIAS_V6 (Base — motor "kokoro").
 *
 * Nota de qualidade (2026-09-03): o bundle INT8 (kokoro-int8-multi-lang-v1_0) tem artefato
 * de sibilo/chiado agudo audível (ressalto ~9,6kHz + ruído de quantização; validado por
 * ouvido vs FP32) — a Base usa o FP32, limpo na fonte.
 *
 * Estrutura de modelo em filesDir/kokoro/ (bundle desempacotado; download na 1ª execução):
 *   model.onnx, voices.bin, tokens.txt, lexicon-us-en.txt, lexicon-gb-en.txt,
 *   lexicon-zh.txt, espeak-ng-data/
 *
 * Diferenças estruturais vs [OnnxTtsEngine] (Supertonic, zona proibida — não alterado):
 * - Sessão ORT ÚNICA para todas as vozes (o modelo é o mesmo; sid + lang variam por chamada
 *   via [GenerationConfig.extra] — `lang` por chamada existe só na API Java/Kotlin do sherpa,
 *   não no binding Python).
 * - Em produção a síntese roda no processo isolado `:sherpa` (RN-2 do V6) — espelho do
 *   isolamento Supertonic, porque o sherpa-onnx também roda ONNX Runtime e a conversão de
 *   livro inteiro (horas de CPU) no processo principal repetiria o risco que criou o
 *   `:supertonic` (abort()/segfault nativo incapturável, morte por memória, conflito de
 *   soname de onnxruntime).
 */

/** Vozes Kokoro expostas pelo app: sid = índice no voices.bin (bundle v1.0, 53 vozes). */
internal data class KokoroVoiceParams(
    val sid: Int,
    val lang: String?
)

/** Diretório do bundle no filesDir (mesmo nome em todos os processos do app). */
internal const val KOKORO_MODEL_DIR = "kokoro"

/**
 * Resolve sid + lang de um voiceId "kokoro-*"; null para voz desconhecida.
 *
 * RN-1 do PLANO_MELHORIAS_V6 (não-negociável): para PT-BR, `lang` é explícito e vale
 * "pt-BR", NUNCA "pt" — no espeak-ng, "pt" = português europeu e "pt-BR" = brasileiro
 * (validado por ouvido em 2026-09-02; o default europeu soa errado). As vozes inglesas usam
 * `lang = null`, conforme o caminho oficial do sherpa com o lexicon en.
 */
private val KOKORO_ENGLISH_SIDS = mapOf(
    "kokoro-en-af-alloy" to 0,
    "kokoro-en-af-aoede" to 1,
    "kokoro-en-af-bella" to 2,
    "kokoro-en-af-heart" to 3,
    "kokoro-en-af-jessica" to 4,
    "kokoro-en-af-kore" to 5,
    "kokoro-en-af-nicole" to 6,
    "kokoro-en-af-nova" to 7,
    "kokoro-en-af-river" to 8,
    "kokoro-en-af-sarah" to 9,
    "kokoro-en-af-sky" to 10,
    "kokoro-en-am-adam" to 11,
    "kokoro-en-am-echo" to 12,
    "kokoro-en-am-eric" to 13,
    "kokoro-en-am-fenrir" to 14,
    "kokoro-en-am-liam" to 15,
    "kokoro-en-am-michael" to 16,
    "kokoro-en-am-onyx" to 17,
    "kokoro-en-am-puck" to 18,
    "kokoro-en-am-santa" to 19,
    "kokoro-en-bf-alice" to 20,
    "kokoro-en-bf-emma" to 21,
    "kokoro-en-bf-isabella" to 22,
    "kokoro-en-bf-lily" to 23,
    "kokoro-en-bm-daniel" to 24,
    "kokoro-en-bm-fable" to 25,
    "kokoro-en-bm-george" to 26,
    "kokoro-en-bm-lewis" to 27
)

internal fun kokoroVoiceParams(voiceId: String): KokoroVoiceParams? = when (voiceId) {
    "kokoro-pf-dora" -> KokoroVoiceParams(sid = 42, lang = "pt-BR")
    "kokoro-pm-alex" -> KokoroVoiceParams(sid = 43, lang = "pt-BR")
    "kokoro-pm-santa" -> KokoroVoiceParams(sid = 44, lang = "pt-BR")
    else -> KOKORO_ENGLISH_SIDS[voiceId]?.let { sid -> KokoroVoiceParams(sid, lang = null) }
}

/**
 * Divide o texto em chunks de no máximo [limite] caracteres, cortando preferencialmente em
 * fim de frase ('.') e depois em espaço. Espelho da lógica privada de OnnxTtsEngine
 * (splitByChars) — mantida como cópia separada para não tocar o caminho Supertonic (zona
 * proibida do V6). Limite calibrado: o frontend espeak do sherpa processou sem crash até
 * ~1200 chars num único texto sem pontuação de frase (teste empírico 2026-09-02); 450 sobra
 * para parágrafos da leitura guiada.
 */
internal fun dividirEmChunks(texto: String, limite: Int = MAX_CHARS_POR_CHUNK): List<String> {
    val trimmed = texto.trim()
    if (trimmed.isEmpty() || trimmed.length <= limite) {
        return if (trimmed.isEmpty()) emptyList() else listOf(trimmed)
    }
    val chunks = mutableListOf<String>()
    var restante = trimmed
    while (restante.isNotEmpty()) {
        if (restante.length <= limite) {
            chunks.add(restante)
            break
        }
        // Corte preferencial em fim de frase; depois no último espaço até o limite.
        // O corte duro respeita EXATAMENTE o limite (nunca emite chunk maior que `limite`,
        // ao contrário da lógica original do OnnxTtsEngine, que podia emitir limite+1).
        val corteBruto = restante.lastIndexOf('.', limite)
            .takeIf { it > limite / 2 }
            ?: restante.lastIndexOf(' ', limite).takeIf { it > 0 }
        val fim = if (corteBruto != null) (corteBruto + 1).coerceAtMost(limite) else limite
        chunks.add(restante.substring(0, fim).trim())
        restante = restante.substring(fim).trim()
    }
    return chunks
}

/**
 * Divide [texto] por ORAÇÃO (mesmo [DivisorDeOracoes] já usado na leitura guiada do Kokoro) em
 * vez de por tamanho bruto de caractere — necessário porque pacotes BYOM/Parte A podem não
 * respeitar pontuação na síntese (ex.: o MMS-TTS do Meta ignora `.`/`!`/`?` completamente,
 * colando as frases sem pausa nenhuma). Sintetizando oração por oração e emendando com o gap de
 * silêncio já existente ([concatenarComGap]), a pausa entre frases vem do lado de fora do
 * modelo em vez de depender dele saber pontuação — funciona independente do pacote respeitar
 * pontuação ou não. Orações anormalmente longas (sem pontuação de frase por um trecho grande)
 * ainda caem no corte por tamanho ([dividirEmChunks]) como rede de segurança, mesmo motivo de
 * sempre (frontend do sherpa testado só até ~1200 chars sem pontuação). Função pura — mesmo
 * padrão de [dividirEmChunks], testável sem instanciar o engine.
 */
internal fun dividirParaByom(texto: String): List<String> {
    val oracoes = DivisorDeOracoes.agruparOracoesCurta(DivisorDeOracoes.dividirEmOracoes(texto))
    if (oracoes.isEmpty()) return dividirEmChunks(texto)
    return oracoes.flatMap { oracao ->
        if (oracao.texto.length <= MAX_CHARS_POR_CHUNK) listOf(oracao.texto)
        else dividirEmChunks(oracao.texto)
    }
}

/** Grava [samples] (float [-1,1]) como WAV PCM 16-bit mono. Mesmo writer do OnnxTtsEngine. */
internal fun escreverWav16BitMono(samples: FloatArray, sampleRate: Int, output: File) {
    val numSamples = samples.size
    val dataSize = numSamples * WAV_BYTES_POR_AMOSTRA

    FileOutputStream(output).use { fos ->
        val header = ByteBuffer.allocate(HEADER_WAV_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(dataSize + WAV_TAMANHO_FIXO_SEM_DADOS)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(WAV_FMT_CHUNK_BYTES)
            putShort(WAV_AUDIO_FORMAT_PCM.toShort())
            putShort(WAV_CANAIS_MONO.toShort())
            putInt(sampleRate)
            putInt(sampleRate * WAV_BYTES_POR_AMOSTRA)
            putShort(WAV_BLOCK_ALIGN.toShort())
            putShort(WAV_BITS_POR_AMOSTRA.toShort())
            put("data".toByteArray())
            putInt(dataSize)
        }
        fos.write(header.array())

        val pcmBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = s.coerceIn(-1.0f, 1.0f)
            pcmBuf.putShort((clamped * AMPLITUDE_MAX_16_BIT).toInt().toShort())
        }
        fos.write(pcmBuf.array())
    }
}

private const val MAX_CHARS_POR_CHUNK = 450
private const val TAMANHO_MIN_ARQUIVO_VALIDO = 500
private const val MAX_TENTATIVAS_PROCESSO_ISOLADO = 3
// Espera antes de cada retentativa após o processo isolado morrer — dá tempo do gerenciador de
// memória do sistema (ex.: ExtM do MIUI) terminar de liberar páginas da rajada de alocação que
// provavelmente causou a morte (ver comentário em sintetizarViaProcessoIsoladoComRetry).
private const val ESPERA_ENTRE_TENTATIVAS_MS = 2_500L
private const val HEADER_WAV_BYTES = 44
private const val GAP_SEGUNDOS_ENTRE_CHUNKS = 0.1f
private const val WAV_FMT_CHUNK_BYTES = 16
private const val WAV_BITS_POR_AMOSTRA = 16
private const val WAV_TAMANHO_FIXO_SEM_DADOS = 36
private const val WAV_AUDIO_FORMAT_PCM = 1
private const val WAV_CANAIS_MONO = 1
private const val WAV_BYTES_POR_AMOSTRA = 2
private const val WAV_BLOCK_ALIGN = 2
private const val AMPLITUDE_MAX_16_BIT = 32767
// Medido em device (Redmi, fp32): RTF ~1,6-2,3 tanto com 4 quanto com 8 threads — o modelo
// não escala além de ~4 neste hardware; mais threads só aquecem. Mantido em 4.
private const val ORT_THREADS_MIN = 2
private const val ORT_THREADS_MAX = 4
private const val RITMO_PORCENTO_BASE = 100f
// Padrões oficiais do sherpa-onnx pra VITS (mesmos defaults documentados na API Kotlin) —
// pacotes BYOM não têm como calibrar isso por ouvido como o time fez com o Kokoro (D4 do V6);
// usar os defaults upstream é o ponto de partida seguro.
private const val VITS_NOISE_SCALE_PADRAO = 0.667f
private const val VITS_NOISE_SCALE_W_PADRAO = 0.8f
private const val VITS_LENGTH_SCALE_PADRAO = 1.0f
private val REGEX_LEXICON_BYOM = Regex("^lexicon-.*\\.txt$")

/** Arquivos obrigatórios do bundle kokoro-multi-lang-v1_0 (FP32). */
private val ARQUIVOS_BUNDLE = listOf(
    "model.onnx",
    "voices.bin",
    "tokens.txt",
    "lexicon-us-en.txt",
    "lexicon-gb-en.txt",
    "lexicon-zh.txt"
)

/** Diretório espeak-ng-data dentro do bundle (fonemização das línguas não-en/zh). */
private const val DIR_ESPEAK_DATA = "espeak-ng-data"

class KokoroTtsEngine(
    private val context: Context,
    /**
     * Quando true (padrão), a síntese é delegada ao processo isolado [SherpaSynthService]
     * via [SherpaProcessClient] (RN-2 do PLANO_MELHORIAS_V6) — um crash nativo do ONNX
     * Runtime (abort()/segfault, incapturável em Kotlin) mata só o processo :sherpa. O
     * próprio serviço cria o engine com `false` para rodar a síntese localmente (dentro do
     * processo filho) sem recursão. Espelho do OnnxTtsEngine.
     */
    private val allowIsolatedProcessDelegation: Boolean = true
) {

    // Sessão ORT única (modelo ~114MB) — criada lazy e reutilizada entre chamadas. Liberada
    // apenas em releaseAll(). O processo :sherpa mantém esta instância viva entre chunks;
    // recriar a sessão a cada chamada seria inviável.
    @Volatile private var tts: OfflineTts? = null

    private val recommendedThreadCount: Int by lazy {
        // Mesmo critério do OnnxTtsEngine: 2–4 threads no ONNX Runtime (XNNPACK) — abaixo
        // subutiliza, acima oversubscription.
        Runtime.getRuntime().availableProcessors().coerceIn(ORT_THREADS_MIN, ORT_THREADS_MAX)
    }

    @Volatile private var lastChunkErrorReason: String? = null

    /** Bundle kokoro baixado e íntegro? Todas as vozes kokoro compartilham o MESMO bundle. */
    fun isModelDownloaded(voiceId: String): Boolean {
        if (!voiceId.startsWith("kokoro-")) return false
        val dir = modelDir()
        val arquivosOk = ARQUIVOS_BUNDLE.all { nome ->
            val f = File(dir, nome)
            f.exists() && f.length() > 0L
        }
        val espeak = File(dir, DIR_ESPEAK_DATA)
        return arquivosOk && espeak.isDirectory && (espeak.list()?.size ?: 0) >= MIN_ARQUIVOS_ESPEAK
    }

    fun modelDir(): File = File(context.filesDir, KOKORO_MODEL_DIR)

    /**
     * Sintetiza [texto] com a voz kokoro [voiceId], gravando WAV PCM 16-bit mono em
     * [outputFile]. Síncrono em thread de IO; uma chamada por chunk do pipeline.
     */
    // Fronteira com nativo (sherpa-onnx): exceções amplas são intencionais — o wrapper JNI
    // pode lançar de formas inesperadas (IllegalArgumentException do require, Runtime do
    // nativo); a falha é propagada como Result.failure para o chamador tratar.
    @Suppress("TooGenericExceptionCaught")
    suspend fun synthesize(texto: String, voiceId: String, outputFile: File): Result<File> =
        withContext(Dispatchers.IO) {
            // Vozes importadas (BYOM, Parte B do V6) usam o formato "<packId>::<vozId>" — o
            // Kokoro Base nunca usa "::" nos próprios ids ("kokoro-pf-dora" etc.), então isso
            // nunca colide com o caminho abaixo. Ver [synthesizeByom].
            if (voiceId.contains("::")) {
                return@withContext synthesizeByom(texto, voiceId, outputFile)
            }
            val params = kokoroVoiceParams(voiceId)
            if (params == null) {
                val msg = "Voice ID '$voiceId' não reconhecido pelo motor Kokoro."
                Log.w(TAG, msg)
                return@withContext Result.failure(IllegalArgumentException(msg))
            }
            if (!isModelDownloaded(voiceId)) {
                val msg = "Modelo Kokoro não baixado. Acesse Configurações → Vozes Offline."
                Log.w(TAG, msg)
                return@withContext Result.failure(IllegalStateException(msg))
            }

            // RN-2: produção sempre pelo processo isolado :sherpa. O SherpaSynthService
            // instancia este engine com allowIsolatedProcessDelegation=false (o serviço JÁ é
            // o processo isolado) e executa o caminho local abaixo.
            if (allowIsolatedProcessDelegation) {
                return@withContext sintetizarViaProcessoIsoladoComRetry(texto, voiceId, outputFile)
            }

            val engine = engine()
            if (engine == null) {
                val msg = lastChunkErrorReason ?: "falha ao inicializar o motor Kokoro"
                return@withContext Result.failure(RuntimeException(msg))
            }

            try {
                val speed = AppPrefs(context).ritmo / RITMO_PORCENTO_BASE
                val sampleRate = engine.sampleRate()
                val partes = mutableListOf<FloatArray>()
                val chunks = dividirEmChunks(texto)
                for ((i, chunk) in chunks.withIndex()) {
                    val audio = sintetizarChunk(engine, chunk, params, speed)
                    if (audio == null || audio.samples.isEmpty()) {
                        val motivo = lastChunkErrorReason ?: "áudio vazio"
                        return@withContext Result.failure(
                            RuntimeException("Falha na síntese do chunk $i: $motivo")
                        )
                    }
                    lastChunkErrorReason = null
                    partes.add(audio.samples)
                }

                escreverWav16BitMono(
                    concatenarComGap(partes, sampleRate),
                    sampleRate,
                    outputFile
                )
                if (!outputFile.exists() || outputFile.length() < TAMANHO_MIN_ARQUIVO_VALIDO) {
                    return@withContext Result.failure(
                        RuntimeException("Arquivo WAV inválido gerado (size=${outputFile.length()}).")
                    )
                }
                Log.i(
                    TAG,
                    "Síntese concluída: voz=$voiceId, ${outputFile.length()} bytes @ $sampleRate Hz"
                )
                Result.success(outputFile)
            } catch (e: Exception) {
                Log.e(TAG, "Erro na síntese kokoro para '$voiceId': ${e.message}", e)
                CrashLogWriter.log(e, "kokoroSynthesize", "voiceId=$voiceId")
                Result.failure(e)
            }
        }

    /**
     * O processo isolado :sherpa pode morrer por pressão de memória TRANSITÓRIA — confirmado em
     * device real (2026-09-16): `ExtM: reportMemPressure` + "flush exist thrashing" (gerenciador
     * de memória do MIUI reagindo a uma rajada de alocação, ex.: carregar o model.onnx) aparece
     * consistentemente pouco antes da morte do processo. Sem espera nenhuma entre tentativas
     * (comportamento anterior), a 2ª tentativa nascia ENQUANTO o sistema ainda estava liberando
     * memória da rajada anterior (visto em log: flush de 8000 páginas ainda em andamento quando o
     * processo novo já tinha sido morto de novo) — nunca tinha chance real de escapar da mesma
     * pressão. Uma pequena espera antes de cada retentativa dá tempo do flush terminar; o client
     * reconecta a um processo NOVO e isso não mascara erros de verdade (modelo corrompido falha
     * de novo com o mesmo motivo e não é retentado pra sempre — MAX_TENTATIVAS_PROCESSO_ISOLADO
     * limita o total).
     */
    private suspend fun sintetizarViaProcessoIsoladoComRetry(
        texto: String,
        voiceId: String,
        outputFile: File
    ): Result<File> {
        for (tentativa in 1..MAX_TENTATIVAS_PROCESSO_ISOLADO) {
            val erro = SherpaProcessClient.synthesize(context, texto, voiceId, outputFile)
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
            Log.w(TAG, "Processo isolado morreu (tentativa $tentativa/$MAX_TENTATIVAS_PROCESSO_ISOLADO), aguardando ${ESPERA_ENTRE_TENTATIVAS_MS}ms e retentando")
            kotlinx.coroutines.delay(ESPERA_ENTRE_TENTATIVAS_MS)
        }
        error("unreachable: loop sempre retorna na última tentativa")
    }

    fun synthesizeSync(texto: String, voiceId: String, outputFile: File): Boolean =
        kotlinx.coroutines.runBlocking {
            synthesize(texto, voiceId, outputFile).isSuccess
        }

    @Suppress("TooGenericExceptionCaught")
    fun releaseAll() {
        try {
            tts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Erro ao liberar a sessão Kokoro: ${t.message}")
        }
        tts = null
        try {
            byomTts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Erro ao liberar a sessão BYOM: ${t.message}")
        }
        byomTts = null
        byomChaveCarregada = null
    }

    // ── BYOM (Parte B do V6) — vozes importadas via .zip, arquitetura kokoro OU vits ─────────
    // Sessão SEPARADA da base (nunca toca em [tts]/[engine]) — o Kokoro Base continua exatamente
    // como testado. Mantém no máximo 1 pacote BYOM carregado por vez (troca ao pedir uma voz de
    // outro pacote): manter N sessões simultâneas arriscaria memória (cada modelo pode passar de
    // 100MB — mesma preocupação já documentada pro bundle Base).

    @Volatile private var byomTts: OfflineTts? = null
    @Volatile private var byomChaveCarregada: String? = null

    private suspend fun synthesizeByom(texto: String, voiceId: String, outputFile: File): Result<File> {
        if (allowIsolatedProcessDelegation) {
            return sintetizarViaProcessoIsoladoComRetry(texto, voiceId, outputFile)
        }
        val resolved = BYOMManager.resolver(context, voiceId)
            ?: return Result.failure(
                IllegalArgumentException("Voz importada '$voiceId' não encontrada (pacote removido?).")
            )
        val engine = byomEngine(resolved)
            ?: return Result.failure(RuntimeException(lastChunkErrorReason ?: "falha ao inicializar pacote BYOM"))
        return try {
            val speed = AppPrefs(context).ritmo / RITMO_PORCENTO_BASE
            val sampleRate = engine.sampleRate()
            val partes = mutableListOf<FloatArray>()
            val chunks = dividirParaByom(texto)
            val params = KokoroVoiceParams(sid = resolved.sid, lang = resolved.lang)
            for ((i, chunk) in chunks.withIndex()) {
                val audio = sintetizarChunk(engine, chunk, params, speed)
                if (audio == null || audio.samples.isEmpty()) {
                    val motivo = lastChunkErrorReason ?: "áudio vazio"
                    return Result.failure(RuntimeException("Falha na síntese do chunk $i: $motivo"))
                }
                lastChunkErrorReason = null
                partes.add(audio.samples)
            }
            escreverWav16BitMono(concatenarComGap(partes, sampleRate), sampleRate, outputFile)
            if (!outputFile.exists() || outputFile.length() < TAMANHO_MIN_ARQUIVO_VALIDO) {
                return Result.failure(
                    RuntimeException("Arquivo WAV inválido gerado (size=${outputFile.length()}).")
                )
            }
            Log.i(TAG, "Síntese BYOM concluída: voz=$voiceId, ${outputFile.length()} bytes @ $sampleRate Hz")
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Erro na síntese BYOM para '$voiceId': ${e.message}", e)
            CrashLogWriter.log(e, "byomSynthesize", "voiceId=$voiceId")
            Result.failure(e)
        }
    }

    // Fronteira com nativo: mesma justificativa do engine() base.
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    private fun byomEngine(resolved: BYOMManager.VozResolvidaByom): OfflineTts? {
        val chave = "${resolved.arquitetura}:${resolved.dir.absolutePath}"
        if (byomChaveCarregada == chave) byomTts?.let { return it }
        try {
            byomTts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Erro ao liberar sessão BYOM anterior: ${t.message}")
        }
        byomTts = null
        byomChaveCarregada = null
        // Kokoro base + um pacote BYOM juntos no processo isolado :sherpa passam de 400MB só
        // em tensores (base ~310MB + BYOM ~100-150MB) — crash nativo real observado em device
        // (usuário alternou entre voz kokoro base e MMS-TTS na mesma sessão do app; o processo
        // :sherpa mantém UM único KokoroTtsEngine vivo entre trocas — SherpaSynthService.engine
        // é lazy/singleton). No máximo 1 modelo pesado (base OU BYOM) residente por vez.
        try {
            tts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Erro ao liberar sessão base ao trocar para BYOM: ${t.message}")
        }
        tts = null
        return try {
            val dir = resolved.dir
            val espeakDir = File(dir, DIR_ESPEAK_DATA).takeIf { it.isDirectory }?.absolutePath ?: ""
            val modelConfig = if (resolved.arquitetura == "vits") {
                OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, "model.onnx").absolutePath,
                        lexicon = File(dir, "lexicon.txt").takeIf { it.exists() }?.absolutePath ?: "",
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = espeakDir,
                        dictDir = "",
                        noiseScale = VITS_NOISE_SCALE_PADRAO,
                        noiseScaleW = VITS_NOISE_SCALE_W_PADRAO,
                        lengthScale = VITS_LENGTH_SCALE_PADRAO
                    ),
                    numThreads = recommendedThreadCount,
                    debug = false
                )
            } else {
                OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = File(dir, "model.onnx").absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = espeakDir,
                        lexicon = dir.listFiles { f -> f.name.matches(REGEX_LEXICON_BYOM) }
                            ?.sortedBy { it.name }
                            ?.joinToString(",") { it.absolutePath } ?: ""
                    ),
                    numThreads = recommendedThreadCount,
                    debug = false
                )
            }
            val config = OfflineTtsConfig(model = modelConfig, maxNumSentences = 1, silenceScale = 0.2f)
            OfflineTts(config = config).also {
                byomTts = it
                byomChaveCarregada = chave
            }
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Falha ao inicializar pacote BYOM '${resolved.dir.name}' (${resolved.arquitetura}): ${t.message}",
                t
            )
            lastChunkErrorReason = t.message ?: t.javaClass.simpleName
            null
        }
    }

    // Fronteira com nativo: a criação da sessão ORT pode lançar Throwable (require do ctor,
    // erro de link/init nativo); tratar como falha de inicialização recuperável.
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    private fun engine(): OfflineTts? {
        tts?.let { return it }
        // Espelho do release cruzado em byomEngine() — no máximo 1 modelo pesado (base OU
        // BYOM) residente por vez no processo isolado :sherpa (ver comentário lá).
        try {
            byomTts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Erro ao liberar sessão BYOM ao trocar para base: ${t.message}")
        }
        byomTts = null
        byomChaveCarregada = null
        val dir = modelDir()
        return try {
            val kokoroConfig = OfflineTtsKokoroModelConfig(
                model = File(dir, "model.onnx").absolutePath,
                voices = File(dir, "voices.bin").absolutePath,
                tokens = File(dir, "tokens.txt").absolutePath,
                dataDir = File(dir, DIR_ESPEAK_DATA).absolutePath,
                // Lexicon en/zh obrigatório (o InitFrontend do sherpa exige lexicon OU lang).
                lexicon = ARQUIVOS_BUNDLE
                    .filter { it.startsWith("lexicon-") }
                    .joinToString(",") { File(dir, it).absolutePath }
            )
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = kokoroConfig,
                    numThreads = recommendedThreadCount,
                    debug = false
                ),
                maxNumSentences = 1,
                silenceScale = 0.2f
            )
            OfflineTts(config = config).also { tts = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao inicializar o motor Kokoro (modelo corrompido?): ${t.message}", t)
            lastChunkErrorReason = t.message ?: t.javaClass.simpleName
            null
        }
    }

    /**
     * Uma chamada ao nativo com sid + lang (RN-1: lang explícito por voz via
     * [GenerationConfig.extra]; o lang do config do modelo fica vazio — o lexicon cobre en/zh).
     */
    // Fronteira com nativo: exceções amplas intencionais (mesmo motivo do synthesize).
    @Suppress("TooGenericExceptionCaught")
    private fun sintetizarChunk(
        engine: OfflineTts,
        texto: String,
        params: KokoroVoiceParams,
        speed: Float
    ): GeneratedAudio? = try {
        engine.generateWithConfig(
            texto,
            GenerationConfig(
                sid = params.sid,
                speed = speed,
                extra = params.lang?.let { mapOf("lang" to it) }
            )
        )
    } catch (t: Throwable) {
        lastChunkErrorReason = t.message ?: t.javaClass.simpleName
        Log.e(TAG, "generateWithConfig falhou: $lastChunkErrorReason", t)
        null
    }

    /** Emenda os chunks com 0,1s de silêncio entre eles (mesma razão do Supertonic: o nativo
     * insere pausas DENTRO da chamada; emendar direto colaria frases de chunks distintos). */
    private fun concatenarComGap(partes: List<FloatArray>, sampleRate: Int): FloatArray {
        val gapSamples = (sampleRate * GAP_SEGUNDOS_ENTRE_CHUNKS).toInt()
        val totalGap = gapSamples * (partes.size - 1).coerceAtLeast(0)
        val all = FloatArray(partes.sumOf { it.size } + totalGap)
        var pos = 0
        for ((idx, parte) in partes.withIndex()) {
            if (idx > 0) pos += gapSamples
            parte.copyInto(all, pos)
            pos += parte.size
        }
        return all
    }

    private companion object {
        const val TAG = "KokoroTtsEngine"
        const val MIN_ARQUIVOS_ESPEAK = 10
    }
}
