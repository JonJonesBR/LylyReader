package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.domain.SinteseResult
import com.jonjonesbr.audiobookgen.util.TtsRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Costura entre os dois caminhos de síntese de voz (ONNX on-device vs Edge/Gemini via Python) —
 * introduzida na Fase 2 da refatoração para eliminar o `if (motorEfetivo == "onnx") ... else ...`
 * que estava duplicado em [com.jonjonesbr.audiobookgen.service.GuidedPlayerManager] (em `play()`
 * e `prefetch()`).
 */
interface TtsSynthesizer {
    /** Sintetiza (ou reaproveita do cache) e retorna o caminho do áudio, ou null em falha. */
    suspend fun synthesize(texto: String, voz: String, ritmo: Int): String?

    /** Motivo da última falha (diagnóstico) — null se a última tentativa teve sucesso. */
    fun ultimoErro(): String? = null

    companion object {
        /** Escolhe a implementação certa para [motorEfetivo] ("onnx", "android" ou o motor Python). */
        fun para(
            motorEfetivo: String,
            context: Context,
            pythonUseCase: PythonEngineUseCase,
            onnxEngine: () -> OnnxTtsEngine
        ): TtsSynthesizer = PronunciationSynthesizer(
            when (motorEfetivo) {
                "onnx" -> OnnxTtsSynthesizer(context, onnxEngine)
                "kokoro" -> KokoroTtsSynthesizer(context)
                "pocket" -> PocketTtsSynthesizer(context)
                "android" -> AndroidTtsSynthesizer(context)
                "elevenlabs" -> ElevenLabsTtsSynthesizer(context)
                else -> PythonTtsSynthesizer(pythonUseCase, motorEfetivo, SecurePreferences.getGeminiKeys(context))
            }
        ) { com.jonjonesbr.audiobookgen.data.PronunciationStore.get(context) }
    }
}

/** Síntese ONNX on-device (Supertonic), com cache de conteúdo compartilhado. */

/** Síntese Kokoro on-device (Sherpa-ONNX, processo isolado :sherpa), com cache compartilhado. */
class KokoroTtsSynthesizer(private val context: Context) : TtsSynthesizer {
    // Engine próprio (padrão ElevenLabsTtsSynthesizer): com allowIsolatedProcessDelegation=true
    // ele delega ao processo :sherpa — a sessão ORT real vive no serviço.
    private val engine = KokoroTtsEngine(context)
    @Volatile private var ultimoErro: String? = null

    override fun ultimoErro(): String? = ultimoErro

    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
        var mensagemErro: String? = null
        // Vozes Piper (VITS/espeak, mesmo motor "kokoro"): travessão/hífen no início do parágrafo
        // abre o áudio com vazio e um som avulso — mesma limpeza que o Pocket usa. Kokoro não muda.
        val piper = voz.startsWith("piper-")
        val textoFinal = if (piper) {
            withContext(Dispatchers.IO) {
                val idioma = com.jonjonesbr.audiobookgen.util.VoiceCatalog.findAny(voz)?.language ?: "pt-BR"
                val limpo = normalizarViaPython(context, texto, idioma, numerosSoltos = false, semTravessoes = true)
                if (limpo.isBlank() && texto.any { it.isLetterOrDigit() }) texto else limpo
            }
        } else {
            texto
        }
        val cached = TtsChunkCache.getOrSynthesize(context, voz, ritmo, textoFinal) { temporary ->
            if (piper && textoFinal.none { it.isLetterOrDigit() }) {
                temporary.writeBytes(SilentWav.bytes(SILENCIO_MS))
                return@getOrSynthesize true
            }
            engine.synthesize(textoFinal, voz, temporary).fold(
                onSuccess = { true },
                onFailure = { erro -> mensagemErro = erro.message; false }
            )
        }
        return if (cached != null) {
            ultimoErro = null
            cached.absolutePath
        } else {
            ultimoErro = mensagemErro ?: "falha ao sintetizar áudio válido"
            Log.e(TAG, "Kokoro synthesis error: ${ultimoErro}")
            null
        }
    }

    private companion object {
        const val TAG = "KokoroTtsSynthesizer"
        const val SILENCIO_MS = 400
    }
}

/** Síntese Pocket TTS local; guided reading and audiobook generation share the same cache. */
class PocketTtsSynthesizer(private val context: Context) : TtsSynthesizer {
    private val engine = PocketTtsEngine(context)
    @Volatile private var lastError: String? = null

    override fun ultimoErro(): String? = lastError

    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
        var synthesisError: String? = null
        // O tokenizador do Pocket não lê dígitos: expande números/símbolos como a conversão de
        // audiobook já faz (text_normalizer.py), pois a leitura guiada mandava o texto cru.
        val textoNormalizado = withContext(Dispatchers.IO) {
            normalizarParaPocket(texto, voz) { t, idioma ->
                normalizarViaPython(context, t, idioma, numerosSoltos = true, semTravessoes = true)
            }
        }
        val cached = TtsChunkCache.getOrSynthesize(context, voz, ritmo, textoNormalizado) { temporary ->
            engine.synthesize(textoNormalizado, voz, temporary).fold(
                onSuccess = { true },
                onFailure = { error -> synthesisError = error.message; false }
            )
        }
        return if (cached != null) {
            lastError = null
            cached.absolutePath
        } else {
            lastError = synthesisError ?: "falha ao sintetizar áudio válido"
            Log.e(TAG, "Pocket synthesis error: $lastError")
            null
        }
    }

    private companion object {
        const val TAG = "PocketTtsSynthesizer"
    }
}

/** Normaliza via text_normalizer.py (Chaquopy); se o Python falhar, devolve o texto original. */
internal fun normalizarViaPython(
    context: Context,
    texto: String,
    idioma: String,
    numerosSoltos: Boolean,
    semTravessoes: Boolean
): String = try {
    com.jonjonesbr.audiobookgen.util.PythonInicio.garantir(context)
    Python.getInstance().getModule("text_normalizer")
        .callAttr("normalizar", texto, idioma, numerosSoltos, semTravessoes).toString()
} catch (erro: Exception) {
    Log.w("TtsSynthesizer", "Normalização Python falhou; usando o texto original", erro)
    texto
}

/**
 * Normaliza [texto] para o idioma da voz Pocket [voz] (padrão pt-BR). Se o normalizador devolver
 * vazio, mantém o texto original para nunca sintetizar silêncio por engano. Função pura: o
 * normalizador real (Python) entra por [normalizar].
 */
internal fun normalizarParaPocket(
    texto: String,
    voz: String,
    normalizar: (texto: String, idioma: String) -> String
): String {
    val idioma = PocketTtsModelManager.specForVoice(voz)?.languageTag ?: "pt-BR"
    val resultado = normalizar(texto, idioma)
    // Vazio vindo do normalizador: se o original tem letras/dígitos, mantém-no (nunca sintetizar
    // silêncio por engano); se era só símbolos ("***"), fica vazio e o motor entrega silêncio.
    return if (resultado.isBlank() && texto.any { it.isLetterOrDigit() }) texto else resultado
}

class OnnxTtsSynthesizer(
    private val context: Context,
    private val engine: () -> OnnxTtsEngine
) : TtsSynthesizer {
    private val TAG = "OnnxTtsSynthesizer"
    @Volatile private var ultimoErro: String? = null

    override fun ultimoErro(): String? = ultimoErro

    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
        var mensagemErro: String? = null
        // O Supertonic também lê mal dígitos ("99", "500", "10h30"): escreve os números por extenso,
        // no idioma do texto, antes de sintetizar (mesmo normalizador do Pocket).
        val textoFalado = withContext(Dispatchers.IO) {
            val idioma = if (voz.endsWith("-pt", ignoreCase = true)) "pt-BR"
            else com.jonjonesbr.audiobookgen.util.LanguageDetector.detectLanguage(texto)
            val limpo = normalizarViaPython(context, texto, idioma, numerosSoltos = true, semTravessoes = false)
            if (limpo.isBlank() && texto.any { it.isLetterOrDigit() }) texto else limpo
        }
        val cached = TtsChunkCache.getOrSynthesize(context, voz, ritmo, textoFalado) { temporary ->
            engine().synthesize(textoFalado, voz, temporary).fold(
                onSuccess = { true },
                onFailure = { erro -> mensagemErro = erro.message; false }
            )
        }
        return if (cached != null) {
            ultimoErro = null
            cached.absolutePath
        } else {
            ultimoErro = mensagemErro ?: "falha ao sintetizar áudio válido"
            Log.e(TAG, "ONNX synthesis error: ${ultimoErro}")
            null
        }
    }
}

/** Síntese via API oficial da ElevenLabs (chave própria do usuário), com cache compartilhado. */
class ElevenLabsTtsSynthesizer(private val context: Context) : TtsSynthesizer {
    companion object {
        private const val TAG = "ElevenLabsTtsSynthesizer"
        private const val PREFIXO_VOZ = "elevenlabs-"
    }
    private val engine = ElevenLabsTtsEngine()
    @Volatile private var ultimoErro: String? = null

    override fun ultimoErro(): String? = ultimoErro

    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
        val apiKey = SecurePreferences.getElevenLabsKey(context)
        val vozReal = voz.removePrefix(PREFIXO_VOZ)
        var mensagemErro: String? = null
        val cached = TtsChunkCache.getOrSynthesize(context, voz, ritmo, texto) { temporary ->
            engine.synthesizeSync(texto, vozReal, apiKey, ritmo, temporary).fold(
                onSuccess = { true },
                onFailure = { erro -> mensagemErro = erro.message; false }
            )
        }
        return if (cached != null) {
            ultimoErro = null
            cached.absolutePath
        } else {
            ultimoErro = mensagemErro ?: "falha ao sintetizar áudio válido"
            Log.e(TAG, "ElevenLabs synthesis error: ${ultimoErro}")
            null
        }
    }
}

/** Síntese via motor TTS nativo Android (vozes instaladas no aparelho), com cache compartilhado. */
class AndroidTtsSynthesizer(private val context: Context) : TtsSynthesizer {
    companion object {
        private const val TAG = "AndroidTtsSynthesizer"
        private const val RITMO_BASE = 100f
        private const val MAX_TENTATIVAS = 2

        // Falhas efêmeras do motor (timeout, rede, serviço momentaneamente indisponível) —
        // ver descreverCodigoTts() em AndroidTtsEngine — valem retry. Erros de configuração
        // (voz inexistente, motor não instalado, ID inválido) são permanentes e não se
        // resolvem tentando de novo.
        private val PADROES_ERRO_TRANSITORIO = listOf(
            "timeout", "ERROR_NETWORK", "ERROR_SERVICE", "erro genérico (ERROR)"
        )

        private fun ehErroTransitorio(msg: String): Boolean =
            PADROES_ERRO_TRANSITORIO.any { msg.contains(it, ignoreCase = true) }
    }
    @Volatile private var ultimoErro: String? = null

    override fun ultimoErro(): String? = ultimoErro

    // Um único "return" final (guard clause do cache + resultado se/senão) mantém a função
    // dentro do limite de 2 returns do detekt, sem perder o early-exit do cache hit.
    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
        val speed = ritmo / RITMO_BASE
        var ultimaMensagem = "motivo desconhecido"
        val arquivo = TtsChunkCache.getOrSynthesize(context, voz, ritmo, texto) { arquivoTemporario ->
            var sucesso = false
            var tentativa = 1
            var deveParar = false
            while (!sucesso && !deveParar && tentativa <= MAX_TENTATIVAS) {
                val res = AndroidTtsEngineCache.synthesize(context, voz, texto, arquivoTemporario, speed)
                sucesso = res.isSuccess
                if (!sucesso) {
                    ultimaMensagem = res.exceptionOrNull()?.message ?: "motivo desconhecido"
                    val podeTentarDeNovo = tentativa < MAX_TENTATIVAS &&
                        ehErroTransitorio(ultimaMensagem)
                    if (podeTentarDeNovo) {
                        Log.w(
                            TAG,
                            "Falha transitória (tentativa $tentativa/$MAX_TENTATIVAS): " +
                                "$ultimaMensagem — tentando de novo"
                        )
                    } else {
                        deveParar = true
                    }
                } else {
                    ultimaMensagem = ""
                }
                tentativa++
            }
            sucesso
        }
        return if (arquivo != null) {
            ultimoErro = null
            arquivo.absolutePath
        } else {
            Log.e(TAG, "Android TTS synthesis error: $ultimaMensagem")
            ultimoErro = ultimaMensagem
            null
        }
    }
}

/** Síntese via Python (Edge TTS / Gemini TTS), delegada ao [PythonEngineUseCase]. */
class PythonTtsSynthesizer(
    private val pythonUseCase: PythonEngineUseCase,
    private val motor: String,
    private val geminiKey: String
) : TtsSynthesizer {
    private val TAG = "PythonTtsSynthesizer"
    @Volatile private var ultimoErro: String? = null

    override fun ultimoErro(): String? = ultimoErro

    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
        val velocidade = TtsRate.fromRitmo(ritmo)
        return when (val result = pythonUseCase.sintetizarParagrafo(texto, voz, velocidade, motor, geminiKey)) {
            is SinteseResult.Success -> {
                ultimoErro = null
                result.caminho
            }
            is SinteseResult.Error -> {
                // result.mensagem já chega mapeada por TtsErrorMapper — sem isso o usuário
                // só via "motivo desconhecido" mesmo com diagnóstico real disponível.
                Log.e(TAG, "Synthesis error: ${result.mensagem}")
                ultimoErro = result.mensagem
                null
            }
        }
    }
}
