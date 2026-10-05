package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Cache local do mapa de falantes, separado do cache de áudio dos motores TTS. */
class SpeakerAttributionStore(private val directory: File) {
    fun fileForBook(bookIdentity: String): File? =
        bookIdentity.takeIf(String::isNotBlank)?.let(::cacheFile)

    /**
     * Diz se o mapa guardado do livro já tem voz atribuída a algum personagem, sem carregar o livro: a lista de personagens vem
     * antes dos parágrafos no arquivo, então basta ler o começo dele.
     */
    fun temVozesAtribuidas(bookIdentity: String): Boolean {
        val arquivo = fileForBook(bookIdentity)?.takeIf { it.isFile } ?: return false
        return runCatching {
            val cabeca = arquivo.inputStream().use { entrada ->
                val buffer = ByteArray(LEITURA_CABECALHO_BYTES)
                val lidos = entrada.read(buffer).coerceAtLeast(0)
                String(buffer, 0, lidos, Charsets.UTF_8)
            }
            val limite = cabeca.indexOf("\"$KEY_PARAGRAPHS\"").takeIf { it >= 0 } ?: cabeca.length
            cabeca.substring(0, limite).contains("\"voice_id\"")
        }.getOrDefault(false)
    }

    fun load(bookIdentity: String, paragraphs: List<String>): SpeakerAttributionResult? {
        if (bookIdentity.isBlank()) return null
        val file = cacheFile(bookIdentity)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt(KEY_VERSION, -1) != FORMAT_VERSION ||
                json.optInt(KEY_ANALYSIS, 1) != OfflineSpeakerAttributor.ANALYSIS_VERSION ||
                json.optString(KEY_CONTENT) != contentFingerprint(paragraphs)
            ) return null
            val speakers = json.getJSONArray(KEY_SPEAKERS).toSpeakerList()
            val mappedParagraphs = json.getJSONArray(KEY_PARAGRAPHS).toParagraphs()
            if (mappedParagraphs.size != paragraphs.size ||
                mappedParagraphs.indices.any {
                    mappedParagraphs[it].paragraphIndex != it ||
                        mappedParagraphs[it].sourceText != paragraphs[it]
                }
            ) return null
            SpeakerAttributionResult(speakers, mappedParagraphs)
        }.getOrNull()
    }

    /**
     * Vozes já escolhidas (id do personagem → voz) num mapa salvo do MESMO texto, mesmo que ele
     * seja de uma versão antiga do analisador — para não perdê-las ao reanalisar o livro.
     */
    fun previousVoices(bookIdentity: String, paragraphs: List<String>): Map<String, String> {
        if (bookIdentity.isBlank()) return emptyMap()
        val file = cacheFile(bookIdentity)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optString(KEY_CONTENT) != contentFingerprint(paragraphs)) return emptyMap()
            json.getJSONArray(KEY_SPEAKERS).toSpeakerList()
                .mapNotNull { speaker -> speaker.voiceId?.let { speaker.id to it } }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    /** Salva por substituição atômica para que uma interrupção não deixe JSON parcial. */
    fun save(
        bookIdentity: String,
        paragraphs: List<String>,
        result: SpeakerAttributionResult,
        chapters: List<Capitulo> = emptyList()
    ): Boolean {
        if (bookIdentity.isBlank() || result.paragraphs.size != paragraphs.size ||
            result.paragraphs.indices.any { result.paragraphs[it].sourceText != paragraphs[it] }
        ) return false
        return runCatching {
            if (!directory.exists() && !directory.mkdirs()) return false
            val json = JSONObject().apply {
                put(KEY_VERSION, FORMAT_VERSION)
                put(KEY_ANALYSIS, OfflineSpeakerAttributor.ANALYSIS_VERSION)
                put(KEY_CONTENT, contentFingerprint(paragraphs))
                put(KEY_SPEAKERS, JSONArray().apply {
                    result.speakers.forEach { speaker ->
                        put(JSONObject().apply {
                            put("id", speaker.id)
                            put("name", speaker.name)
                            put("utterance_count", speaker.utteranceCount)
                            speaker.voiceId?.let { put("voice_id", it) }
                        })
                    }
                })
                put(KEY_PARAGRAPHS, JSONArray().apply {
                    result.paragraphs.forEach { paragraph ->
                        put(JSONObject().apply {
                            put("index", paragraph.paragraphIndex)
                            put("segments", JSONArray().apply {
                                paragraph.segments.forEach { segment ->
                                    put(JSONObject().apply {
                                        put("speaker_id", segment.speakerId)
                                        put("text", segment.text)
                                    })
                                }
                            })
                            paragraph.sourceText?.let { put("source_text", it) }
                        })
                    }
                })
                put(KEY_CHAPTERS, JSONArray().apply {
                    chapters.forEach { chapter ->
                        put(JSONObject().apply {
                            put("title", chapter.titulo)
                            put("start_paragraph", chapter.indiceParagrafoInicio)
                            put("end_paragraph", chapter.indiceParagrafoFim)
                        })
                    }
                })
            }
            val target = cacheFile(bookIdentity)
            val temporary = File(directory, "${target.name}.tmp")
            FileOutputStream(temporary).use { stream ->
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                temporary.delete()
                return false
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            target.isFile && target.length() > 0L
        }.getOrDefault(false)
    }

    /** Migração de caminho: leva o mapa de personagens para a identidade nova, se ela ainda não tiver um. */
    fun migrarIdentidade(antiga: String, nova: String): Boolean {
        if (antiga.isBlank() || nova.isBlank() || antiga == nova) return false
        val origem = cacheFile(antiga)
        val destino = cacheFile(nova)
        if (!origem.isFile || destino.exists()) return false
        return origem.copyTo(destino, overwrite = false).isFile
    }

    /** Apaga o mapa de personagens do livro (livro removido da biblioteca). */
    fun apagarIdentidade(identidade: String) {
        if (identidade.isNotBlank()) cacheFile(identidade).delete()
    }

    private fun cacheFile(bookIdentity: String): File =
        File(directory, "${sha256(bookIdentity)}.json")

    private fun contentFingerprint(paragraphs: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paragraphs.forEach { paragraph ->
            val bytes = paragraph.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
            digest.update(0xFF.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun JSONArray.toSpeakerList(): List<DetectedSpeaker> = List(length()) { index ->
        getJSONObject(index).let { item ->
            DetectedSpeaker(
                id = item.getString("id"),
                name = item.getString("name"),
                utteranceCount = item.optInt("utterance_count", 0),
                voiceId = item.optString("voice_id").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun JSONArray.toParagraphs(): List<ParagraphSpeakerSegments> = List(length()) { index ->
        getJSONObject(index).let { item ->
            val segments = item.getJSONArray("segments")
            ParagraphSpeakerSegments(
                paragraphIndex = item.getInt("index"),
                segments = List(segments.length()) { segmentIndex ->
                    segments.getJSONObject(segmentIndex).let { segment ->
                        SpeakerSegment(
                            speakerId = segment.getString("speaker_id"),
                            text = segment.getString("text")
                        )
                    }
                },
                sourceText = item.optString("source_text")
            )
        }
    }

    private companion object {
        const val LEITURA_CABECALHO_BYTES = 32 * 1024
        const val FORMAT_VERSION = 2
        const val KEY_VERSION = "format_version"
        const val KEY_ANALYSIS = "analysis_version"
        const val KEY_CONTENT = "content_fingerprint"
        const val KEY_SPEAKERS = "speakers"
        const val KEY_PARAGRAPHS = "paragraphs"
        const val KEY_CHAPTERS = "chapters"
    }
}
