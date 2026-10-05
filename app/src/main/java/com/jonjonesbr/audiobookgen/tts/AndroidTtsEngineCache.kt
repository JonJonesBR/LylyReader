package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.util.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cache de uma única instância de [AndroidTtsEngine], reinicializada só se o pacote do motor
 * mudar. Centraliza o hop obrigatório para [Dispatchers.Main] — `TextToSpeech` precisa de uma
 * thread com Looper para criar seus callbacks — e serializa acesso concorrente (preview,
 * leitura guiada e conversão podem chamar ao mesmo tempo, compartilhando a mesma instância de
 * `TextToSpeech`; sem o [Mutex], um chamador poderia trocar `voice`/`speechRate` no meio da
 * síntese de outro).
 */
object AndroidTtsEngineCache {
    private const val TAG = "AndroidTtsEngineCache"

    private val mutex = Mutex()
    private var cachedPackage: String? = null
    private var cachedEngine: AndroidTtsEngine? = null

    // Só deve ser chamado de dentro de mutex.withLock { withContext(Dispatchers.Main) { ... } }.
    private suspend fun ensureEngine(context: Context, enginePackage: String?): AndroidTtsEngine? {
        if (cachedPackage == enginePackage && cachedEngine != null) return cachedEngine

        cachedEngine?.release()
        val engine = AndroidTtsEngine(context.applicationContext)
        var ok = engine.init(enginePackage)
        if (!ok && enginePackage != null) {
            // Motor pode ter sido desinstalado/atualizado — cai pro motor padrão do sistema em
            // vez de falhar a conversão inteira por causa de um motor que sumiu.
            Log.w(TAG, "Falha ao iniciar motor '$enginePackage', tentando motor padrão do sistema")
            ok = engine.init(null)
        }
        return if (ok) {
            cachedPackage = enginePackage
            cachedEngine = engine
            engine
        } else {
            cachedPackage = null
            cachedEngine = null
            null
        }
    }

    /** Lista as vozes do motor [enginePackage] como [VoiceOption]s prontas para a UI. */
    suspend fun listVoices(context: Context, enginePackage: String?): List<VoiceOption> {
        if (enginePackage == null) return emptyList() // sem pacote não dá pra compor um ID estável
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                val engine = ensureEngine(context, enginePackage) ?: return@withContext emptyList()
                val todasAsVozes = engine.getAvailableVoices()

                // Alguns motores de terceiros (ex.: MultiTTS) expõem uma voz-coringa literalmente
                // chamada "NOT_SET" — convenção (usada por outros apps consumidores de TTS
                // também) para "use a voz que estiver configurada dentro do próprio app do
                // motor". Quando ela existe, o motor tipicamente também expõe centenas de vozes
                // individuais bagunçadas (proxies de vários provedores) — nesse caso, mostra só
                // a genérica em vez de afogar o usuário nessa lista.
                val generica = todasAsVozes.firstOrNull {
                    it.name.equals(AndroidTtsEngine.VOZ_GENERICA_PADRAO, ignoreCase = true)
                }
                if (generica != null) {
                    return@withContext listOf(
                        VoiceOption(
                            id = AndroidVoiceId.encode(enginePackage, generica.name),
                            name = "Voz padrão (configurada no app do motor)",
                            language = generica.locale.toLanguageTag(),
                            isMale = false,
                            engine = "android",
                            isGenerico = true
                        )
                    )
                }

                // TextToSpeech.getVoices() retorna um Set (sem ordem garantida) — ordena por
                // idioma/nome para a lista não aparecer embaralhada na UI.
                todasAsVozes
                    .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
                    .map { v ->
                        VoiceOption(
                            id = AndroidVoiceId.encode(enginePackage, v.name),
                            name = v.label,
                            language = v.locale.toLanguageTag(),
                            isMale = false,
                            engine = "android",
                            precisaBaixarNoSistema = v.precisaBaixar
                        )
                    }
            }
        }
    }

    /** Sintetiza [texto] com a voz [voiceIdComposto] (formato `"android::pkg::nome"`). */
    suspend fun synthesize(
        context: Context,
        voiceIdComposto: String,
        texto: String,
        destino: File,
        speed: Float
    ): Result<File> {
        val (enginePackage, voiceName) = AndroidVoiceId.decode(voiceIdComposto)
            ?: return Result.failure(IllegalArgumentException("ID de voz Android inválido: $voiceIdComposto"))
        return mutex.withLock {
            withContext(Dispatchers.Main) {
                val engine = ensureEngine(context, enginePackage)
                    ?: return@withContext Result.failure(IllegalStateException("Motor '$enginePackage' indisponível"))
                val resultado = engine.synthesize(texto, voiceName, destino, speed)
                if (resultado.isSuccess) return@withContext resultado

                // Vários motores de terceiros degradam depois de algumas sínteses na mesma
                // instância/conexão (passam a falhar mesmo sem rajada, com chamadas espaçadas) —
                // força uma instância nova do motor e tenta mais uma vez antes de desistir.
                Log.w(
                    TAG,
                    "Falha na 1ª tentativa (${resultado.exceptionOrNull()?.message}) — " +
                        "recriando motor '$enginePackage' e tentando de novo"
                )
                cachedEngine?.release()
                cachedEngine = null
                cachedPackage = null
                val engineNovo = ensureEngine(context, enginePackage)
                    ?: return@withContext resultado
                engineNovo.synthesize(texto, voiceName, destino, speed)
            }
        }
    }
}
