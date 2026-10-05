package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Vozes criadas pelo usuário a partir de um áudio de referência (clonagem com o Pocket TTS).
 *
 * O áudio de referência (WAV 24 kHz mono) e o índice ficam SÓ no armazenamento privado do app:
 * nada é enviado a servidor nenhum. O id da voz carrega o idioma (`pocket-custom-pt-ab12cd34`) para
 * que qualquer processo (inclusive o `:pocket`) saiba qual pacote de idioma usar sem ler o índice.
 */
object PocketCustomVoices {
    const val PREFIX = "pocket-custom-"
    /**
     * Duração da referência usada pelo motor. O modelo tem memória de 512 posições (12,5 por segundo) para
     * voz + texto + fala gerada; os presets públicos usam ~10 s (126 posições). Com 15 s ou mais a geração
     * estoura essa memória e sai só ruído e palavras soltas (medido no Redmi: 20 s e 30 s quebram, 10 s funciona).
     */
    const val MAX_SECONDS = 10
    /** Abaixo de ~3 s o timbre sai instável. */
    const val MIN_SECONDS = 3
    /** A gravação pelo microfone pode ir até aqui; o app escolhe o melhor trecho de [MAX_SECONDS]. */
    const val MAX_RECORD_SECONDS = 20
    /** Quanto de um arquivo importado é lido para procurar o melhor trecho. */
    const val MAX_IMPORT_SECONDS = 90
    const val SAMPLE_RATE = 24_000

    data class Voice(val id: String, val name: String, val languageTag: String, val createdAt: Long)

    private val idiomas = mapOf("pt" to "pt-BR", "en" to "en-US", "es" to "es-ES")

    fun isCustom(voiceId: String) = voiceId.startsWith(PREFIX)

    /** Tag de idioma (pt-BR / en-US / es-ES) embutida no id; null se não for voz clonada. */
    fun languageTagOf(voiceId: String): String? {
        if (!isCustom(voiceId)) return null
        return idiomas[voiceId.removePrefix(PREFIX).substringBefore('-')]
    }

    fun dir(context: Context) = File(File(context.filesDir, "pockettts"), "custom_voices")

    private fun index(context: Context) = File(dir(context), "index.json")

    /**
     * Áudio de referência da voz. Vozes criadas por versões anteriores podem ter até 30 s — longas demais
     * para o motor —, então aqui elas são reduzidas uma única vez ao melhor trecho de [MAX_SECONDS].
     */
    fun file(context: Context, voiceId: String): File? {
        val arquivo = File(dir(context), "$voiceId.wav").takeIf { isCustom(voiceId) && it.isFile } ?: return null
        VoiceReferenceAudio.aparar(arquivo)
        return arquivo
    }

    @Synchronized
    fun list(context: Context): List<Voice> {
        val arquivo = index(context)
        if (!arquivo.isFile) return emptyList()
        return runCatching {
            val itens = JSONArray(arquivo.readText(Charsets.UTF_8))
            (0 until itens.length()).mapNotNull { i ->
                val o = itens.getJSONObject(i)
                val voz = Voice(
                    o.getString("id"), o.getString("name"), o.getString("language"), o.optLong("created")
                )
                voz.takeIf { file(context, it.id) != null }
            }
        }.getOrDefault(emptyList())
    }

    /** Cria a voz a partir de [wav] (já em 24 kHz mono PCM16). Devolve a voz registrada. */
    @Synchronized
    fun add(context: Context, name: String, languageTag: String, wav: File): Voice {
        val curto = idiomas.entries.firstOrNull { it.value == languageTag }?.key
            ?: throw IllegalArgumentException("Idioma não suportado: $languageTag")
        val id = PREFIX + curto + "-" + UUID.randomUUID().toString().take(8)
        val pasta = dir(context).apply { mkdirs() }
        wav.copyTo(File(pasta, "$id.wav"), overwrite = true)
        val voz = Voice(id, name.trim().ifEmpty { id }, languageTag, System.currentTimeMillis())
        salvar(context, list(context) + voz)
        return voz
    }

    @Synchronized
    fun rename(context: Context, voiceId: String, newName: String) {
        salvar(context, list(context).map { if (it.id == voiceId) it.copy(name = newName.trim().ifEmpty { it.name }) else it })
    }

    @Synchronized
    fun remove(context: Context, voiceId: String) {
        File(dir(context), "$voiceId.wav").delete()
        // embeddings em cache do motor ficam ao lado do áudio (mesmo prefixo)
        dir(context).listFiles { f -> f.name.startsWith(voiceId) }?.forEach { it.delete() }
        salvar(context, list(context).filterNot { it.id == voiceId })
    }

    private fun salvar(context: Context, vozes: List<Voice>) {
        val itens = JSONArray()
        vozes.forEach {
            itens.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("language", it.languageTag).put("created", it.createdAt)
            )
        }
        dir(context).mkdirs()
        index(context).writeText(itens.toString(), Charsets.UTF_8)
    }
}
