package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Correções manuais do usuário na análise de personagens de um livro. Valem mais que a heurística local e que a
 * IA, e ficam salvas para sobreviver a uma nova análise.
 *
 * - [aliases]: personagem fundido em outro (id do personagem → nome que passa a valer), p. ex. "Kote" → "Kvothe".
 * - [lines]: fala corrigida à mão, (parágrafo, nº da fala) → nome de quem fala ([OfflineSpeakerAttributor.AI_NARRATOR] = narrador).
 */
data class SpeakerEdits(
    val aliases: Map<String, String> = emptyMap(),
    val lines: Map<Pair<Int, Int>, String> = emptyMap()
) {
    fun isEmpty() = aliases.isEmpty() && lines.isEmpty()

    /** Passa a tratar [fromId] como [toName]. Aliases que apontavam para [fromId] seguem para o novo destino. */
    fun withMerge(fromId: String, toName: String): SpeakerEdits {
        val moved = aliases.mapValues { (_, target) ->
            if (OfflineSpeakerAttributor.idOf(target) == fromId) toName else target
        }
        return copy(aliases = moved + (fromId to toName))
    }

    fun withoutMerge(fromId: String): SpeakerEdits = copy(aliases = aliases - fromId)

    fun withLine(paragraph: Int, ordinal: Int, name: String): SpeakerEdits =
        copy(lines = lines + ((paragraph to ordinal) to name))

    fun withoutLine(paragraph: Int, ordinal: Int): SpeakerEdits = copy(lines = lines - (paragraph to ordinal))

    companion object {
        val EMPTY = SpeakerEdits()
    }
}

/** Guarda as [SpeakerEdits] por livro, no mesmo espírito do [SpeakerAiStore] (atrelado ao conteúdo do livro). */
class SpeakerEditsStore(private val directory: File) {

    fun load(bookIdentity: String, paragraphs: List<String>): SpeakerEdits {
        val file = fileFor(bookIdentity)
        if (bookIdentity.isBlank() || !file.isFile) return SpeakerEdits.EMPTY
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optString("content") != fingerprint(paragraphs)) return SpeakerEdits.EMPTY
            val aliases = json.optJSONObject("aliases")?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }.orEmpty()
            val lines = mutableMapOf<Pair<Int, Int>, String>()
            json.optJSONObject("lines")?.let { obj ->
                obj.keys().forEach { key ->
                    val (p, n) = key.split(':').map(String::toInt)
                    lines[p to n] = obj.getString(key)
                }
            }
            SpeakerEdits(aliases, lines)
        }.getOrDefault(SpeakerEdits.EMPTY)
    }

    fun save(bookIdentity: String, paragraphs: List<String>, edits: SpeakerEdits): Boolean {
        if (bookIdentity.isBlank()) return false
        if (edits.isEmpty()) {
            fileFor(bookIdentity).delete()
            return true
        }
        return runCatching {
            directory.mkdirs()
            val json = JSONObject()
                .put("content", fingerprint(paragraphs))
                .put("aliases", JSONObject().apply { edits.aliases.forEach { (k, v) -> put(k, v) } })
                .put("lines", JSONObject().apply { edits.lines.forEach { (k, v) -> put("${k.first}:${k.second}", v) } })
            val target = fileFor(bookIdentity)
            val temp = File(directory, target.name + ".tmp")
            FileOutputStream(temp).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            true
        }.getOrDefault(false)
    }

    /** Migração de caminho: leva as correções manuais para a identidade nova, se ela ainda não tiver as suas. */
    fun migrarIdentidade(antiga: String, nova: String): Boolean {
        if (antiga.isBlank() || nova.isBlank() || antiga == nova) return false
        val origem = fileFor(antiga)
        val destino = fileFor(nova)
        if (!origem.isFile || destino.exists()) return false
        return origem.copyTo(destino, overwrite = false).isFile
    }

    /** Apaga as correções do livro (livro removido da biblioteca). */
    fun apagarIdentidade(identidade: String) {
        if (identidade.isNotBlank()) fileFor(identidade).delete()
    }

    private fun fileFor(bookIdentity: String) = File(directory, "edits_" + sha(bookIdentity).take(24) + ".json")

    private fun fingerprint(paragraphs: List<String>) = sha(paragraphs.joinToString("\u0000"))

    private fun sha(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Uma fala de um personagem, para o usuário conferir e corrigir. */
data class SpeechLine(val paragraphIndex: Int, val ordinal: Int, val text: String)

object SpeechLines {
    /**
     * Falas atribuídas a [speakerId] no resultado da análise, em ordem de leitura (no máximo [limit]).
     * O nº da fala é o mesmo usado pelas correções ([SpeakerEdits.lines]).
     */
    fun of(result: SpeakerAttributionResult, paragraphs: List<String>, speakerId: String, limit: Int = 200): List<SpeechLine> {
        val found = mutableListOf<SpeechLine>()
        for (paragraph in result.paragraphs) {
            val owned = paragraph.segments.filter { it.speakerId == speakerId }
            if (owned.isEmpty()) continue
            val text = paragraphs.getOrNull(paragraph.paragraphIndex) ?: continue
            OfflineSpeakerAttributor.partsOf(text).filter { it.speech }.forEach { part ->
                if (owned.any { it.text.contains(part.text.trim()) }) {
                    found += SpeechLine(paragraph.paragraphIndex, part.ordinal, part.text.trim())
                    if (found.size >= limit) return found
                }
            }
        }
        return found
    }
}
