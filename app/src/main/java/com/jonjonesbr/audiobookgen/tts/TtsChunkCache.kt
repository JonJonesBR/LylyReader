package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Cache compartilhado pela guiada e pelos bridges de conversão, com publicação atômica. */
object TtsChunkCache {
    private const val PREFIX = "tts_v2_"
    private const val TAMANHO_HASH_HEX = 16
    private const val TAMANHO_MINIMO_AUDIO = 500L
    // Incrementar quando formato, parâmetros ou comportamento de síntese mudar.
    private const val VERSAO_SINTESE = 1
    private val sintetizacoesEmAndamento = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    private val ultimaVerificacaoTeto = java.util.concurrent.atomic.AtomicLong(0L)
    private const val INTERVALO_VERIFICACAO_TETO_MS = 5 * 60 * 1000L

    private fun dir(context: Context): File = context.cacheDir

    /** BYOM usa ID derivado do hash do pacote; revisar estes tokens ao atualizar modelos oficiais. */
    internal fun revisaoModelo(voice: String): String = when {
        voice.startsWith("supertonic-") -> "supertonic-v2"
        voice.startsWith("kokoro-") -> "kokoro-multilang-v1_0-fp32"
        voice == "pocket-ptbr-rafael" -> "pocket-tts-3.3.0-per-language-tokenizers-v4-decoder-frame-by-frame-rafael-nofilter-pause500-nodash-v2"
        voice.startsWith("pocket-") -> "pocket-tts-3.3.0-per-language-tokenizers-v4-decoder-frame-by-frame-pause500-nodash-v2"
        voice.startsWith("mms-por::") -> "mms-tts-por-v1"
        voice.startsWith("android::") -> "android-engine"
        voice.startsWith("elevenlabs-") -> "elevenlabs-eleven_multilingual_v2"
        voice.contains("::") -> "byom-" + voice.substringBefore("::")
        else -> "python-" + voice.substringBefore('-')
    }

    internal fun hashTexto(texto: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(TAMANHO_HASH_HEX)
    }

    /** Revisão do modelo + opções que mudam o áudio (passos do Pocket diferentes do padrão). */
    internal fun revisaoModelo(context: Context, voice: String): String {
        val passos = runCatching {
            when {
                voice.startsWith("pocket-") -> com.jonjonesbr.audiobookgen.data.AppPrefs(context).pocketPassos
                voice.startsWith("supertonic-") -> com.jonjonesbr.audiobookgen.data.AppPrefs(context).supertonicSteps
                else -> 0
            }
        }.getOrDefault(0)
        return revisaoComPassos(revisaoModelo(voice), voice, passos)
    }

    /**
     * Pocket e Supertonic com passos diferentes do padrão do motor ganham sufixo (o cache padrão não
     * muda); os passos alteram o áudio, então não podem reaproveitar o de outro valor.
     */
    internal fun revisaoComPassos(base: String, voice: String, passos: Int): String {
        val prefs = com.jonjonesbr.audiobookgen.data.AppPrefs
        val padrao = when {
            voice.startsWith("pocket-") -> prefs.POCKET_PASSOS_PADRAO
            voice.startsWith("supertonic-") -> prefs.SUPERTONIC_STEPS_PADRAO
            else -> return base
        }
        return if (passos == padrao) base else "$base-passos$passos"
    }

    fun file(
        context: Context,
        voice: String,
        speedPct: Int,
        text: String,
        modelRevision: String = revisaoModelo(context, voice)
    ): File {
        val chave = "$VERSAO_SINTESE\u0000$modelRevision\u0000$voice\u0000$speedPct\u0000$text"
        return File(dir(context), "tts_v2_" + hashTexto(chave) + ".wav")
    }

    /** Aceita apenas áudio WAV/MP3 completo, nunca arquivo parcial. */
    internal fun arquivoValido(file: File): Boolean {
        if (!file.isFile || file.length() < TAMANHO_MINIMO_AUDIO) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(12)
                if (input.read(header) < 4) return false
                val wav = header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
                    header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))
                val mp3Tag = header.copyOfRange(0, 3).contentEquals("ID3".toByteArray(Charsets.US_ASCII))
                val mp3Frame = (header[0].toInt() and 0xFF) == 0xFF &&
                    (header[1].toInt() and 0xE0) == 0xE0
                wav || mp3Tag || mp3Frame
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Chamadas simultâneas para a mesma chave aguardam um único produtor. O áudio é gerado
     * em temporário no mesmo diretório e só é publicado após validação por rename atômico.
     */
    suspend fun getOrSynthesize(
        context: Context,
        voice: String,
        speedPct: Int,
        text: String,
        synthesize: suspend (File) -> Boolean
    ): File? {
        val target = file(context, voice, speedPct, text)
        if (arquivoValido(target)) {
            target.setLastModified(System.currentTimeMillis())  // uso recente: entra por último na fila de limpeza
            return target
        }

        val key = target.absolutePath
        val pending = CompletableDeferred<File?>()
        val active = sintetizacoesEmAndamento.putIfAbsent(key, pending)
        if (active != null) return active.await()?.takeIf(::arquivoValido)

        var published: File? = null
        var temporary: File? = null
        try {
            if (arquivoValido(target)) {
                target.setLastModified(System.currentTimeMillis())
                published = target
                return target
            }

            val parent = target.parentFile ?: return null
            if (!parent.exists() && !parent.mkdirs()) return null
            temporary = File.createTempFile("tts_", ".tmp", parent)
            if (!synthesize(temporary) || !arquivoValido(temporary)) return null

            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            temporary = null
            if (arquivoValido(target)) {
                published = target
                aparaSeNecessario(context)
                return target
            }
            target.delete()
            return null
        } finally {
            temporary?.delete()
            sintetizacoesEmAndamento.remove(key, pending)
            pending.complete(published)
        }
    }

    /** Adaptador síncrono para Chaquopy; copia o áudio validado ao arquivo temporário do job. */
    fun synthesizeSync(
        context: Context,
        voice: String,
        speedPct: Int,
        text: String,
        output: File,
        synthesize: (File) -> Boolean
    ): Boolean = runBlocking {
        val cached = getOrSynthesize(context, voice, speedPct, text) { temporary ->
            synthesize(temporary)
        } ?: return@runBlocking false
        cached.copyTo(output, overwrite = true)
        output.exists() && arquivoValido(output)
    }

    /** Arquivos de áudio publicados (não conta os temporários em andamento). */
    private fun arquivosPublicados(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }?.toList().orEmpty()

    /** Tamanho total dos áudios em cache, em bytes. */
    fun tamanhoBytes(context: Context): Long =
        arquivosPublicados(context).sumOf { it.length() } + arquivosPython(context).sumOf { it.length() }

    /**
     * Apaga os áudios usados há mais tempo até o total voltar a ~80% de [tetoBytes]
     * (ver [CachePolicy]). 0 = sem limite. Devolve quantos arquivos removeu.
     */
    fun aparar(context: Context, tetoBytes: Long, agora: Long = System.currentTimeMillis()): Int {
        // O teto vale para os dois caches de áudio juntos: o das vozes do leitor (tts_v2_*) e o
        // dos trechos das vozes online gerados na conversão de audiobook (Python, audio_chunks/*.bin).
        val arquivos = arquivosPublicados(context).associateBy { "tts/" + it.name } +
            arquivosPython(context).associateBy { "py/" + it.name }
        val remover = CachePolicy.selecionarParaRemover(
            arquivos.map { (chave, arquivo) -> CachePolicy.Arquivo(chave, arquivo.length(), arquivo.lastModified()) },
            tetoBytes, agora
        )
        return remover.count { arquivos[it.nome]?.delete() == true }
    }

    private fun arquivosPython(context: Context): List<File> =
        File(context.cacheDir, "audio_chunks").listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toList().orEmpty()

    /** Limpeza pelo teto salvo nas preferências; no máximo uma verificação a cada 5 minutos. */
    fun aparaSeNecessario(context: Context) {
        val agora = System.currentTimeMillis()
        val anterior = ultimaVerificacaoTeto.get()
        if (agora - anterior < INTERVALO_VERIFICACAO_TETO_MS || !ultimaVerificacaoTeto.compareAndSet(anterior, agora)) return
        val tetoMb = runCatching { com.jonjonesbr.audiobookgen.data.AppPrefs(context).tetoCacheAudioMb }
            .getOrDefault(CachePolicy.TETO_PADRAO_MB)
        runCatching { aparar(context, CachePolicy.tetoEmBytes(tetoMb)) }
    }

    /** Remove entradas publicadas e temporários da versão atual do cache. */
    fun clear(context: Context): Int {
        var n = 0
        dir(context)
            .listFiles { f -> f.name.startsWith(PREFIX) || f.name.startsWith("tts_") && f.name.endsWith(".tmp") }
            ?.forEach { if (it.delete()) n++ }
        return n
    }
}
