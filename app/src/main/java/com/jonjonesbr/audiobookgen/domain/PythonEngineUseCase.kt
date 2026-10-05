package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.TtsErrorMapper
import com.jonjonesbr.audiobookgen.util.SynthesisWakeLock
import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ── Tipos de Retorno Seguros ──────────────────────────────────────────────

data class CacheInfo(val tamanhoMb: Double, val arquivos: Int)

/** Um capítulo com timestamps (ms) no MP3 final, usado para navegação anterior/próximo (T4.4). */
data class CapituloAudio(val titulo: String, val inicioMs: Long, val fimMs: Long)

sealed class ProcessamentoResult {
    data class Success(
        val caminho: String,
        val duracao: String,
        val tamanhoMb: String,
        val capitulos: List<CapituloAudio> = emptyList()
    ) : ProcessamentoResult()

    data class Error(val mensagem: String) : ProcessamentoResult()
}

sealed class PreviewResult {
    data class Success(val caminho: String) : PreviewResult()
    data class Error(val mensagem: String) : PreviewResult()
}

sealed class SinteseResult {
    data class Success(val caminho: String) : SinteseResult()
    data class Error(val mensagem: String) : SinteseResult()
}

// ── UseCase ─────────────────────────────────────────────────────────────

class PythonEngineUseCase(private val context: Context) {

    companion object {
        private val PYTHON_LOCK = Any()

        /** Tamanho do trecho de texto incluído no log de diagnóstico da síntese. */
        private const val LOG_TEXTO_PREVIEW_CHARS = 80
    }

    private val TAG = "PythonEngineUseCase"

    /**
     * Inicializa o motor Python de forma assíncrona, caso não esteja inicializado.
     * Retorna true se iniciou corretamente.
     */
    suspend fun inicializarMotorSeNecessario(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Duas telas/tarefas podem pedir o motor ao mesmo tempo na abertura fria: sem a trava, as duas viam
            // "ainda não iniciou", e a segunda `start` falhava com "Python already started" (leitor mostrava erro).
            com.jonjonesbr.audiobookgen.util.PythonInicio.garantir(context)
            val py = Python.getInstance()
            val mod = py.getModule("audiobook_android")
            mod.callAttr("init", context.filesDir.absolutePath, context.cacheDir.absolutePath)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar Python: ${e.message}")
            CrashLogWriter.log(e, "inicializarMotorSeNecessario")
            false
        }
    }

    /**
     * Aplica configurações globais ao módulo Python.
     */
    suspend fun sincronizarConfiguracoes(
        motorTts: String,
        chaveGemini: String,
        pausaMs: Int = 600,
        tomMeiosTons: Int? = null,
        agudosDb: Int? = null
    ) = withContext(Dispatchers.IO) {
        try {
            if (!inicializarMotorSeNecessario()) return@withContext
            val mod = Python.getInstance().getModule("audiobook_android")
            mod.callAttr("set_motor", motorTts)
            mod.callAttr("set_config", "edge_pausa_entre_blocos_ms", pausaMs)
            val prefs = com.jonjonesbr.audiobookgen.data.AppPrefs(context)
            // Perfil de áudio do livro (quando a conversão veio dele) vale mais que os ajustes globais.
            mod.callAttr("set_config", "timbre_tom_meios_tons", tomMeiosTons ?: prefs.tomVozMeiosTons)
            mod.callAttr("set_config", "timbre_agudos_db", agudosDb ?: prefs.suavizarAgudosDb)
            mod.callAttr("set_gemini_keys", chaveGemini)
            mod.callAttr("set_pronunciations", com.jonjonesbr.audiobookgen.data.PronunciationStore.rawJson(context))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar config Python: ${e.message}")
            CrashLogWriter.log(e, "sincronizarConfiguracoes", "motor=$motorTts")
        }
    }

    /** Ajusta o bitrate de saída (kbps) do áudio gerado. */
    suspend fun setBitrate(kbps: Int) = withContext(Dispatchers.IO) {
        try {
            if (!inicializarMotorSeNecessario()) return@withContext
            Python.getInstance().getModule("audiobook_android")
                .callAttr("set_config", "audio_bitrate_kbps", kbps)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ajustar bitrate: ${e.message}")
        }
    }

    /** Consulta o tamanho atual do cache de áudio do lado Python (chunks de Edge/Gemini). */
    suspend fun obterInfoCache(): CacheInfo? = withContext(Dispatchers.IO) {
        try {
            if (!inicializarMotorSeNecessario()) return@withContext null
            val mod = Python.getInstance().getModule("audiobook_android")
            val json = JSONObject(mod.callAttr("get_cache_info").toString())
            CacheInfo(json.getDouble("tamanho_mb"), json.getInt("arquivos"))
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao consultar cache: ${e.message}")
            null
        }
    }

    /**
     * Limpa o cache de áudio do lado Python. Retorna a quantidade de arquivos removidos.
     * Propaga exceções (o chamador decide se aborta também a limpeza do cache Kotlin).
     */
    suspend fun limparCachePython(): Int = withContext(Dispatchers.IO) {
        inicializarMotorSeNecessario()
        Python.getInstance().getModule("audiobook_android").callAttr("limpar_cache").toInt()
    }

    /**
     * Gera um preview (amostra) da voz solicitada.
     */
    suspend fun gerarPreviewVoz(
        voz: String,
        velocidade: String,
        motor: String,
        chaveGemini: String,
        texto: String = ""
    ): PreviewResult = withContext(Dispatchers.IO) {
        SynthesisWakeLock.acquire(context)
        try {
            if (!inicializarMotorSeNecessario()) {
                return@withContext PreviewResult.Error("Motor Python offline.")
            }
            val mod = Python.getInstance().getModule("audiobook_android")

            // Garante o motor base desta chamada
            mod.callAttr("set_motor", motor)
            mod.callAttr("set_gemini_keys", chaveGemini)
            
            val res = mod.callAttr("preview_voz_sync", voz, velocidade, texto)
            val ok = res.callAttr("get", "ok")?.toBoolean() ?: false
            
            if (ok) {
                val caminho = res.callAttr("get", "caminho")?.toString() ?: ""
                PreviewResult.Success(caminho)
            } else {
                val erroRaw = res.callAttr("get", "erro")?.toString() ?: "Erro desconhecido"
                PreviewResult.Error(TtsErrorMapper.toUserMessage(erroRaw))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao gerar preview", e)
            CrashLogWriter.log(e, "gerarPreviewVoz", "voz=$voz, motor=$motor")
            PreviewResult.Error(TtsErrorMapper.toUserMessage(e.message ?: "Falha crítica no preview"))
        } finally {
            SynthesisWakeLock.release()
        }
    }

    /**
     * Dispara o processamento pesado e síncrono do arquivo inteiro.
     * Utiliza callback bridge nativo para relatar progresso em tempo real pra ViewModel.
     */
    suspend fun processarArquivo(
        arquivo: String,
        voz: String,
        velocidade: String,
        estilo: String,
        saida: String,
        motor: String,
        chaveGemini: String,
        onProgress: (Int, String) -> Unit
    ): ProcessamentoResult = withContext(Dispatchers.IO) {
        kotlinx.coroutines.yield() // CancellationException se o usuário cancelou
        SynthesisWakeLock.acquire(context)
        try {
            if (!inicializarMotorSeNecessario()) {
                return@withContext ProcessamentoResult.Error("Motor Python offline.")
            }

            val mod = Python.getInstance().getModule("audiobook_android")
            mod.callAttr("set_motor", motor)
            mod.callAttr("set_gemini_keys", chaveGemini)

            // Callback Java <-> Python para reports nativos
            val callbackBridge = com.chaquo.python.PyObject.fromJava(
                object : java.util.function.BiConsumer<Int, String> {
                    override fun accept(pct: Int, msg: String) {
                        onProgress(pct, msg)
                    }
                }
            )

            val jsonStr = com.jonjonesbr.audiobookgen.data.AppPrefs(context).blacklistWordsJson

            // O Python bloqueia esta IO Thread até terminar tudo
            val resultado = mod.callAttr(
                "processar_arquivo_sync",
                arquivo, voz, velocidade, "+0Hz", estilo, saida, callbackBridge, jsonStr
            )

            if (resultado == null) {
                return@withContext ProcessamentoResult.Error("Python retornou um payload incompleto.")
            }

            val ok = resultado.callAttr("get", "ok")?.toBoolean() ?: false
            if (ok) {
                ProcessamentoResult.Success(
                    caminho = resultado.callAttr("get", "caminho")?.toString() ?: "",
                    duracao = resultado.callAttr("get", "duracao")?.toString() ?: "",
                    tamanhoMb = resultado.callAttr("get", "tamanho_mb")?.toString() ?: "?",
                    capitulos = parseCapitulos(resultado)
                )
            } else {
                val erroRaw = resultado.callAttr("get", "erro")?.toString() ?: ""
                ProcessamentoResult.Error(TtsErrorMapper.toUserMessage(erroRaw))
            }

        } catch (e: CancellationException) {
            throw e // propaga para que o WorkManager marque como CANCELLED
        } catch (e: PyException) {
            CrashLogWriter.log(e, "processarArquivo(PyException)", "voz=$voz, motor=$motor, arquivo=$arquivo")
            ProcessamentoResult.Error(TtsErrorMapper.toUserMessage(e.message ?: "Falha no Python"))
        } catch (e: Exception) {
            CrashLogWriter.log(e, "processarArquivo", "voz=$voz, motor=$motor, arquivo=$arquivo")
            ProcessamentoResult.Error(TtsErrorMapper.toUserMessage(e.message ?: "Falha interna local"))
        } finally {
            SynthesisWakeLock.release()
        }
    }

    /** Converte texto já extraído, preservando parágrafos e mapa de vozes do leitor. */
    suspend fun processarTexto(
        texto: String,
        titulo: String,
        voz: String,
        velocidade: String,
        estilo: String,
        saida: String,
        motor: String,
        chaveGemini: String,
        speakerMapPath: String? = null,
        onProgress: (Int, String) -> Unit
    ): ProcessamentoResult = withContext(Dispatchers.IO) {
        kotlinx.coroutines.yield()
        SynthesisWakeLock.acquire(context)
        try {
            if (!inicializarMotorSeNecessario()) {
                return@withContext ProcessamentoResult.Error("Motor Python offline.")
            }
            val mod = Python.getInstance().getModule("audiobook_android")
            mod.callAttr("set_motor", motor)
            mod.callAttr("set_gemini_keys", chaveGemini)
            val callbackBridge = com.chaquo.python.PyObject.fromJava(
                java.util.function.BiConsumer<Int, String> { pct, msg -> onProgress(pct, msg) }
            )
            val blacklist = com.jonjonesbr.audiobookgen.data.AppPrefs(context).blacklistWordsJson
            val resultado = mod.callAttr(
                "processar_texto_sync",
                texto,
                voz,
                velocidade,
                "+0Hz",
                estilo,
                titulo,
                saida,
                callbackBridge,
                blacklist,
                speakerMapPath.orEmpty()
            ) ?: return@withContext ProcessamentoResult.Error("Python retornou um payload incompleto.")
            val ok = resultado.callAttr("get", "ok")?.toBoolean() ?: false
            if (ok) {
                ProcessamentoResult.Success(
                    caminho = resultado.callAttr("get", "caminho")?.toString() ?: "",
                    duracao = resultado.callAttr("get", "duracao")?.toString() ?: "",
                    tamanhoMb = resultado.callAttr("get", "tamanho_mb")?.toString() ?: "?",
                    capitulos = parseCapitulos(resultado)
                )
            } else {
                val erroRaw = resultado.callAttr("get", "erro")?.toString() ?: ""
                ProcessamentoResult.Error(TtsErrorMapper.toUserMessage(erroRaw))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PyException) {
            CrashLogWriter.log(e, "processarTexto(PyException)", "voz=$voz, motor=$motor, titulo=$titulo")
            ProcessamentoResult.Error(TtsErrorMapper.toUserMessage(e.message ?: "Falha no Python"))
        } catch (e: Exception) {
            CrashLogWriter.log(e, "processarTexto", "voz=$voz, motor=$motor, titulo=$titulo")
            ProcessamentoResult.Error(TtsErrorMapper.toUserMessage(e.message ?: "Falha interna local"))
        } finally {
            SynthesisWakeLock.release()
        }
    }

    /**
     * Cancela o processamento em andamento.
     */
    fun cancelar() {
        try {
            if (Python.isStarted()) {
                val mod = Python.getInstance().getModule("audiobook_android")
                mod.callAttr("cancelar")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tentar cancelar: ${e.message}")
            CrashLogWriter.log(e, "cancelar")
        }
    }

    /**
     * Sintetiza um parágrafo individual de forma síncrona, consultando o cache do Python.
     */
    suspend fun sintetizarParagrafo(
        texto: String,
        voz: String,
        velocidade: String,
        motor: String,
        chaveGemini: String
    ): SinteseResult = withContext(Dispatchers.IO) {
        SynthesisWakeLock.acquire(context)
        try {
            if (!inicializarMotorSeNecessario()) {
                return@withContext SinteseResult.Error("Motor Python offline.")
            }
            val mod = Python.getInstance().getModule("audiobook_android")
            val res = mod.callAttr("sintetizar_paragrafo_sync", texto, voz, velocidade, "+0Hz", motor, chaveGemini)
                ?: return@withContext SinteseResult.Error("Python retornou um payload vazio.")
            val ok = res.callAttr("get", "ok")?.toBoolean() ?: false
            if (ok) {
                val caminho = res.callAttr("get", "caminho")?.toString() ?: ""
                SinteseResult.Success(caminho)
            } else {
                val erroRaw = res.callAttr("get", "erro")?.toString() ?: "Erro desconhecido"
                SinteseResult.Error(TtsErrorMapper.toUserMessage(erroRaw))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao sintetizar parágrafo", e)
            CrashLogWriter.log(
                e,
                "sintetizarParagrafo",
                "voz=$voz, motor=$motor, text='${texto.take(LOG_TEXTO_PREVIEW_CHARS)}'"
            )
            SinteseResult.Error(TtsErrorMapper.toUserMessage(e.message ?: "Falha crítica na síntese"))
        } finally {
            SynthesisWakeLock.release()
        }
    }
}

/** Converte a lista Python de capítulos (T4.4: timestamps de início/fim em ms) pro tipo Kotlin. */
private fun parseCapitulos(resultado: com.chaquo.python.PyObject): List<CapituloAudio> =
    resultado.callAttr("get", "capitulos")?.asList()?.mapNotNull { capPy ->
        val titulo = capPy.callAttr("get", "titulo")?.toString() ?: return@mapNotNull null
        val inicioMs = capPy.callAttr("get", "inicio_ms")?.toLong() ?: return@mapNotNull null
        val fimMs = capPy.callAttr("get", "fim_ms")?.toLong() ?: return@mapNotNull null
        CapituloAudio(titulo, inicioMs, fimMs)
    } ?: emptyList()
