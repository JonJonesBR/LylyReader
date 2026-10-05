package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.util.VoiceOption
import java.text.Normalizer

enum class SpeakerGender { MALE, FEMALE, UNKNOWN }

/**
 * Gênero PROVÁVEL de um personagem a partir do nome (títulos, nomes comuns, terminação) e do
 * artigo que o precede no texto ("a Marina" / "o Pedro"). É uma heurística: na dúvida devolve
 * [SpeakerGender.UNKNOWN], nunca chuta.
 */
object SpeakerGenderInferrer {
    private val femaleTitles = setOf(
        "dona", "senhora", "sra", "srta", "senhorita", "rainha", "princesa", "tia", "mae", "madre",
        "irma", "condessa", "duquesa", "baronesa", "lady", "miss", "mrs", "ms", "dona", "senora"
    )
    private val maleTitles = setOf(
        "seu", "senhor", "sr", "rei", "principe", "tio", "pai", "padre", "frei", "conde", "duque",
        "barao", "lord", "sir", "mr", "don", "dom", "senor"
    )
    private val femaleNames = setOf(
        "isabel", "raquel", "ruth", "beatriz", "liz", "carmen", "mercedes", "ester", "rachel",
        "elizabeth", "jennifer", "karen", "lois", "hazel", "iris", "abigail", "dolores", "socorro",
        "consuelo", "luz", "rosario", "pilar", "belen", "ines", "estela", "alice", "aline", "eloise"
    )
    private val maleNames = setOf(
        "luca", "joshua", "noah", "nikita", "jonatas", "matias", "elias", "tobias", "josue", "lucas",
        "joao", "davi", "david", "miguel", "rafael", "gabriel", "samuel", "daniel", "manuel", "raul",
        "andre", "felipe", "jose", "jesus", "carlos", "luis", "juan", "pablo", "joe", "jake", "jack",
        "james", "john", "michael", "henry", "george", "arthur", "harry", "ron", "tom", "peter"
    )

    fun infer(speakerName: String, paragraphs: List<String>): SpeakerGender {
        val tokens = speakerName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return SpeakerGender.UNKNOWN
        val first = simplify(tokens.first().trimEnd('.'))
        if (first in femaleTitles) return SpeakerGender.FEMALE
        if (first in maleTitles) return SpeakerGender.MALE

        if (first in femaleNames) return SpeakerGender.FEMALE
        if (first in maleNames) return SpeakerGender.MALE

        articleEvidence(tokens.first(), paragraphs)?.let { return it }

        return when {
            first.endsWith("a") -> SpeakerGender.FEMALE
            first.endsWith("o") -> SpeakerGender.MALE
            else -> SpeakerGender.UNKNOWN
        }
    }

    /** "a Marina" ≫ "o Marina" → feminino (e vice-versa); exige pelo menos 2 ocorrências e o dobro. */
    private fun articleEvidence(givenName: String, paragraphs: List<String>): SpeakerGender? {
        val nome = Regex.escape(givenName)
        val feminino = Regex("(?<![\\p{L}])(?:a|la)\\s+$nome(?![\\p{L}])")
        val masculino = Regex("(?<![\\p{L}])(?:o|el)\\s+$nome(?![\\p{L}])")
        var f = 0
        var m = 0
        for (texto in paragraphs) {
            f += feminino.findAll(texto).count()
            m += masculino.findAll(texto).count()
        }
        return when {
            f >= 2 && f >= 2 * m -> SpeakerGender.FEMALE
            m >= 2 && m >= 2 * f -> SpeakerGender.MALE
            else -> null
        }
    }

    private fun simplify(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}

/** Sugestão de voz para um personagem; [voice] é null quando nenhuma voz elegível foi encontrada. */
data class SuggestedSpeakerVoice(
    val speaker: DetectedSpeaker,
    val gender: SpeakerGender,
    val voice: VoiceOption?
)

/**
 * Sugere (nunca aplica) uma voz por personagem, usando só vozes do motor do narrador que já estão
 * prontas para uso — sem disparar downloads. Vozes distintas sempre que possível, do mesmo idioma
 * do narrador e compatíveis com o gênero provável. Função pura.
 */
object SpeakerVoiceSuggester {
    fun suggest(
        speakers: List<DetectedSpeaker>,
        genders: Map<String, SpeakerGender>,
        candidates: List<VoiceOption>,
        narratorVoiceId: String?,
        narratorLanguage: String?,
        narratorEngine: String? = null,
        isReady: (VoiceOption) -> Boolean
    ): List<SuggestedSpeakerVoice> {
        val eligible = candidates
            .filter { !it.isGenerico && !it.precisaBaixarNoSistema && it.id != narratorVoiceId && isReady(it) }
            .filter { languageCompatible(it.language, narratorLanguage) }
            .withIndex()
            .sortedWith(
                compareBy(
                    { if (narratorEngine != null && it.value.engine == narratorEngine) 0 else 1 },
                    { languageRank(it.value.language, narratorLanguage) },
                    { it.index }
                )
            )
            .map { it.value }

        val used = mutableSetOf<String>()
        return speakers.map { speaker ->
            val gender = genders[speaker.id] ?: SpeakerGender.UNKNOWN
            val matching = eligible.filter { genderMatches(it, gender) }
            val chosen = matching.firstOrNull { it.id !in used } ?: matching.firstOrNull()
            chosen?.let { used += it.id }
            SuggestedSpeakerVoice(speaker, gender, chosen)
        }
    }

    private fun genderMatches(voice: VoiceOption, gender: SpeakerGender): Boolean = when (gender) {
        SpeakerGender.UNKNOWN -> true
        SpeakerGender.MALE -> voice.generoConhecido && voice.isMale
        SpeakerGender.FEMALE -> voice.generoConhecido && !voice.isMale
    }

    private fun primary(language: String?): String? =
        language?.split('-', '_')?.firstOrNull()?.lowercase()?.takeIf { it.isNotBlank() }

    private fun languageCompatible(voiceLanguage: String, narratorLanguage: String?): Boolean {
        if (narratorLanguage.isNullOrBlank() || voiceLanguage.equals("multilingual", ignoreCase = true)) return true
        return primary(voiceLanguage) == primary(narratorLanguage)
    }

    /** 0 = mesma região, 1 = mesmo idioma, 2 = multilíngue. */
    private fun languageRank(voiceLanguage: String, narratorLanguage: String?): Int = when {
        narratorLanguage.isNullOrBlank() -> 1
        voiceLanguage.equals(narratorLanguage, ignoreCase = true) -> 0
        voiceLanguage.equals("multilingual", ignoreCase = true) -> 2
        else -> 1
    }
}
