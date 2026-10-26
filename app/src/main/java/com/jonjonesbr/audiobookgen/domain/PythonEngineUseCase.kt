package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.R
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

    /** [job] é o hash do job no Python (quando a conversão chegou a começar), para poder descartá-lo depois. */
    data class Error(val mensagem: String, val job: String? = null) : ProcessamentoResult()
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

        /** Parágrafos enviados por vez ao Python no relatório de trocas (livro grande não vai inteiro). */
        private const val PARAGRAFOS_POR_LOTE_RELATORIO = 200

        /**
         * Verdadeiro enquanto o worker de conversão roda. Durante a conversão o dicionário do Python é do livro da
         * conversão: leitura, prévia e diálogos não o reescrevem. O worker liga após definir o dicionário e desliga
         * (restaurando o global) no finally.
         */
        @Volatile private var emConversa = false

        fun marcarConversaoAtiva(ativa: Boolean) {
            emConversa = ativa
        }

        fun conversaoAtiva(): Boolean = emConversa
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
     * Aplica configurações globais ao módulo Python. Com [caminhoDoLivro], o dicionário de pronúncia injetado é o
     * global mais o do livro (o do livro vence); sem ele, só o global.
     */
    suspend fun sincronizarConfiguracoes(
        motorTts: String,
        chaveGemini: String,
        pausaMs: Int = 600,
        tomMeiosTons: Int? = null,
        agudosDb: Int? = null,
        caminhoDoLivro: String? = null
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
            // Durante uma conversão, o dicionário do Python já é o do livro da conversão: não o reescreve.
            if (!conversaoAtiva()) sincronizarDicionarioNoMotor(mod, caminhoDoLivro)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar config Python: ${e.message}")
            CrashLogWriter.log(e, "sincronizarConfiguracoes", "motor=$motorTts")
        }
    }

    /**
     * Manda ao motor só o dicionário de pronúncia efetivo (global mais o do livro, se houver). Usar depois de
     * salvar entradas no leitor, para a próxima síntese já valer. Sem livro, o resultado é só o global.
     */
    suspend fun sincronizarDicionario(caminhoDoLivro: String?) = withContext(Dispatchers.IO) {
        // Com conversão em andamento, quem manda no dicionário do Python é o worker (ver marcarConversaoAtiva).
        if (conversaoAtiva()) return@withContext
        try {
            if (!inicializarMotorSeNecessario()) return@withContext
            sincronizarDicionarioNoMotor(Python.getInstance().getModule("audiobook_android"), caminhoDoLivro)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao sincronizar dicionário Python: ${e.message}")
            CrashLogWriter.log(e, "sincronizarDicionario")
        }
    }

    private fun sincronizarDicionarioNoMotor(mod: com.chaquo.python.PyObject, caminhoDoLivro: String?) {
        val dicionarioDoLivro = com.jonjonesbr.audiobookgen.data.PronunciationBookStore.doApp(context)
            .load(caminhoDoLivro.orEmpty())
        val dicionarioEfetivo = DicionarioDoLivro.efetivo(
            com.jonjonesbr.audiobookgen.data.PronunciationStore.get(context), dicionarioDoLivro
        )
        mod.callAttr("set_pronunciations", dicionarioEfetivo.toJson())
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
                return@withContext PreviewResult.Error(context.getString(R.string.python_motor_offline))
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
                PreviewResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(erroRaw)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao gerar preview", e)
            CrashLogWriter.log(e, "gerarPreviewVoz", "voz=$voz, motor=$motor")
            PreviewResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(e.message ?: "Falha crítica no preview")))
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
        try {
            if (!inicializarMotorSeNecessario()) {
                return@withContext ProcessamentoResult.Error(context.getString(R.string.python_motor_offline))
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
                return@withContext ProcessamentoResult.Error(context.getString(R.string.python_payload_incompleto))
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
                ProcessamentoResult.Error(mensagemComDetalhe(erroRaw), jobDo(resultado))
            }

        } catch (e: CancellationException) {
            throw e // propaga para que o WorkManager marque como CANCELLED
        } catch (e: PyException) {
            CrashLogWriter.log(e, "processarArquivo(PyException)", "voz=$voz, motor=$motor, arquivo=$arquivo")
            ProcessamentoResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(e.message ?: "Falha no Python")))
        } catch (e: Exception) {
            CrashLogWriter.log(e, "processarArquivo", "voz=$voz, motor=$motor, arquivo=$arquivo")
            ProcessamentoResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(e.message ?: "Falha interna local")))
        }
    }

    /** Mensagem amigável; quando o erro não é reconhecido, anexa o texto bruto do motor para o diagnóstico. */
    private fun mensagemComDetalhe(erroRaw: String): String {
        val res = TtsErrorMapper.toUserMessageRes(erroRaw)
        val base = context.getString(res)
        return if (res == R.string.erro_tts_generico && erroRaw.isNotBlank()) "$base (${erroRaw.take(200)})" else base
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
        try {
            if (!inicializarMotorSeNecessario()) {
                return@withContext ProcessamentoResult.Error(context.getString(R.string.python_motor_offline))
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
            ) ?: return@withContext ProcessamentoResult.Error(context.getString(R.string.python_payload_incompleto))
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
                ProcessamentoResult.Error(mensagemComDetalhe(erroRaw), jobDo(resultado))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PyException) {
            CrashLogWriter.log(e, "processarTexto(PyException)", "voz=$voz, motor=$motor, titulo=$titulo")
            ProcessamentoResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(e.message ?: "Falha no Python")))
        } catch (e: Exception) {
            CrashLogWriter.log(e, "processarTexto", "voz=$voz, motor=$motor, titulo=$titulo")
            ProcessamentoResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(e.message ?: "Falha interna local")))
        }
    }

    /**
     * Cancela o processamento em andamento. Com [descartar] = true, o Python também apaga o job ao parar
     * (cancelamento definitivo); sem ele, o job fica guardado para retomar (pausa).
     */
    fun cancelar(descartar: Boolean = false) {
        try {
            if (Python.isStarted()) {
                val mod = Python.getInstance().getModule("audiobook_android")
                mod.callAttr(if (descartar) "cancelar_e_descartar" else "cancelar")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao tentar cancelar: ${e.message}")
            CrashLogWriter.log(e, "cancelar")
        }
    }

    /**
     * Corrige o tamanho (MB) da entrada mais recente do histórico, depois da compressão WAV -> AAC. Só troca se a
     * entrada ainda tiver o tamanho [antigo]. Devolve false se não trocou ou a conversa com o Python falhou; nunca lança.
     */
    suspend fun atualizarTamanhoHistorico(antigo: String, novo: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) return@withContext false
            Python.getInstance().getModule("audiobook_android")
                .callAttr("atualizar_tamanho_historico", antigo, novo).toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar tamanho no histórico: ${e.message}")
            CrashLogWriter.log(e, "atualizarTamanhoHistorico")
            false
        }
    }

    /**
     * Junta o que já foi gerado da conversão em andamento (previa.mp3). Devolve o caminho, ou null se não
     * há trecho pronto ou a conversa com o Python falhou; nunca lança.
     */
    suspend fun gerarPrevia(): String? = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) return@withContext null
            Python.getInstance().getModule("audiobook_android").callAttr("gerar_previa")
                .toString().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gerar prévia: ${e.message}")
            CrashLogWriter.log(e, "gerarPrevia")
            null
        }
    }

    /**
     * Apaga o job dir de uma conversão pausada. Devolve false se não deu (job inválido ou falha); nunca lança.
     * É suspend porque inicializa o motor Python se ele ainda não subiu (app reaberto com a conversão pausada).
     */
    suspend fun descartarJob(job: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!inicializarMotorSeNecessario()) return@withContext false
            Python.getInstance().getModule("audiobook_android").callAttr("descartar_job", job).toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao descartar job: ${e.message}")
            CrashLogWriter.log(e, "descartarJob")
            false
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
                return@withContext SinteseResult.Error(context.getString(R.string.python_motor_offline))
            }
            val mod = Python.getInstance().getModule("audiobook_android")
            val res = mod.callAttr("sintetizar_paragrafo_sync", texto, voz, velocidade, "+0Hz", motor, chaveGemini)
                ?: return@withContext SinteseResult.Error(context.getString(R.string.python_payload_vazio))
            val ok = res.callAttr("get", "ok")?.toBoolean() ?: false
            if (ok) {
                val caminho = res.callAttr("get", "caminho")?.toString() ?: ""
                SinteseResult.Success(caminho)
            } else {
                val erroRaw = res.callAttr("get", "erro")?.toString() ?: "Erro desconhecido"
                SinteseResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(erroRaw)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao sintetizar parágrafo", e)
            CrashLogWriter.log(
                e,
                "sintetizarParagrafo",
                "voz=$voz, motor=$motor, text='${texto.take(LOG_TEXTO_PREVIEW_CHARS)}'"
            )
            SinteseResult.Error(context.getString(TtsErrorMapper.toUserMessageRes(e.message ?: "Falha crítica na síntese")))
        } finally {
            SynthesisWakeLock.release()
        }
    }

    /**
     * Relatório de conferência (antes → depois) da normalização que a síntese faria com esta voz. Não
     * sintetiza nada. Processa em lotes de parágrafos. Em falha devolve lista vazia (e registra no log).
     * O dicionário de pronúncia é o que estiver sincronizado no Python (ver sincronizarDicionario).
     */
    suspend fun relatorioDeTrocas(texto: String, voz: String): List<TrocaNormalizador> = withContext(Dispatchers.IO) {
        try {
            if (!inicializarMotorSeNecessario()) return@withContext emptyList()
            val mod = Python.getInstance().getModule("audiobook_android")
            texto.lines()
                .filter { it.isNotBlank() }
                .chunked(PARAGRAFOS_POR_LOTE_RELATORIO)
                .flatMap { lote ->
                    val json = mod.callAttr("relatorio_normalizacao_json", lote.joinToString("\n"), voz).toString()
                    trocasDoRelatorio(json)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao gerar relatório de trocas: ${e.message}")
            CrashLogWriter.log(e, "relatorioDeTrocas")
            emptyList()
        }
    }

    private fun trocasDoRelatorio(json: String): List<TrocaNormalizador> {
        val obj = JSONObject(json)
        if (!obj.optBoolean("ok", false)) {
            Log.e(TAG, "Relatório de trocas falhou: ${obj.optString("erro")}")
            return emptyList()
        }
        val arr = obj.optJSONArray("trocas") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            TrocaNormalizador(item.getString("antes"), item.getString("depois"))
        }
    }
}

/** Hash do job devolvido pelo Python nos erros; ausente quando o erro veio antes de o job existir. */
private fun jobDo(resultado: com.chaquo.python.PyObject): String? =
    resultado.callAttr("get", "job")?.toString()?.takeIf { it.isNotBlank() && it != "None" }

/** Converte a lista Python de capítulos (T4.4: timestamps de início/fim em ms) pro tipo Kotlin. */
private fun parseCapitulos(resultado: com.chaquo.python.PyObject): List<CapituloAudio> =
    resultado.callAttr("get", "capitulos")?.asList()?.mapNotNull { capPy ->
        val titulo = capPy.callAttr("get", "titulo")?.toString() ?: return@mapNotNull null
        val inicioMs = capPy.callAttr("get", "inicio_ms")?.toLong() ?: return@mapNotNull null
        val fimMs = capPy.callAttr("get", "fim_ms")?.toLong() ?: return@mapNotNull null
        CapituloAudio(titulo, inicioMs, fimMs)
    } ?: emptyList()
