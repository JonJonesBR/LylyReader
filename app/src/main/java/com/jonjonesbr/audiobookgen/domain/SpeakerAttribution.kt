package com.jonjonesbr.audiobookgen.domain

import java.text.Normalizer

data class SpeakerSegment(val speakerId: String, val text: String)

data class DetectedSpeaker(
    val id: String,
    val name: String,
    val utteranceCount: Int,
    val voiceId: String? = null
)

data class ParagraphSpeakerSegments(
    val paragraphIndex: Int,
    val segments: List<SpeakerSegment>,
    val sourceText: String? = null
)

data class VoiceAssignedSegment(val text: String, val voiceId: String)

data class SpeakerAttributionResult(
    val speakers: List<DetectedSpeaker>,
    val paragraphs: List<ParagraphSpeakerSegments>
) {
    /** Resolve cada segmento para uma voz; ausências sempre usam a voz do narrador. */
    fun voicePlan(paragraphIndex: Int, narratorVoiceId: String): List<VoiceAssignedSegment> {
        val paragraph = paragraphs.firstOrNull { it.paragraphIndex == paragraphIndex } ?: return emptyList()
        val voicesBySpeaker = speakers.associate { it.id to it.voiceId }
        return paragraph.segments.map { segment ->
            VoiceAssignedSegment(
                text = segment.text,
                voiceId = voicesBySpeaker[segment.speakerId]
                    ?.takeIf(String::isNotBlank)
                    ?: narratorVoiceId
            )
        }
    }
}

/**
 * Atribuição local de falas. Fase 1 reconhece quem fala por nome, papel ("o velho") ou pronome
 * (português, inglês e espanhol). Fase 2 resolve as falas sem nome pelo contexto: pronome ↔ gênero
 * do último personagem, continuação no mesmo parágrafo, alternância em diálogo e sujeito da frase
 * de narração vizinha. Só personagens com nome explícito no livro entram no elenco (máx. 6); o que
 * continua ambíguo fica com o narrador.
 */
object OfflineSpeakerAttributor {
    const val NARRATOR_ID = "narrator"
    const val MAX_CHARACTER_SPEAKERS = 15

    /** Subir quando o algoritmo mudar: os mapas em cache de versões anteriores são reanalisados. */
    const val ANALYSIS_VERSION = 3

    /** Parágrafos sem fala depois dos quais a alternância e o pronome deixam de valer. */
    private const val MAX_GAP_PARAGRAPHS = 3

    /** Para alternar falas, a última fala resolvida pode estar a até este nº de parágrafos (narração no meio vale). */
    private const val ALTERNATION_MAX_GAP = 2

    private const val NAME = "[\\p{Lu}][\\p{L}’'\\-]*(?:\\s+[\\p{Lu}][\\p{L}’'\\-]*){0,2}"
    private const val VERBS_PT = "disse|diz|perguntou|pergunta|respondeu|responde|falou|fala|gritou|grita|sussurrou|sussurra|murmurou|murmura|comentou|comenta|retrucou|retruca|chamou|chama|resmungou|resmunga|pediu|pede|ordenou|ordena|exclamou|exclama|questionou|questiona|acrescentou|acrescenta|continuou|continua|insistiu|insiste|declarou|declara|explicou|explica|concluiu|conclui|replicou|replica|balbuciou|gemeu|berrou|bradou|rugiu|cochichou|sugeriu|sugere|ponderou|observou|observa|lembrou|lembra|avisou|alertou|gaguejou|interrompeu|completou|emendou|retorquiu|afirmou|afirma|negou|confessou|admitiu|rebateu|indagou|inquiriu|suspirou|suspira|riu|sorriu|anunciou|prosseguiu|sentenciou|confirmou|provocou|agradeceu|reclamou|protestou|concordou|repetiu|começou|fez|advertiu|prometeu|atalhou|gracejou|brincou|rosnou|grunhiu|cumprimentou"
    private const val VERBS_EN = "said|says|asked|asks|replied|replies|answered|answers|whispered|whispers|shouted|shouts|cried|muttered|murmured|exclaimed|added|adds|continued|explained|demanded|called|yelled|snapped|sighed|laughed|remarked|declared|suggested|insisted|responded|inquired|announced|growled|stammered|gasped"
    private const val VERBS_ES = "dijo|dice|preguntó|pregunta|respondió|susurró|gritó|murmuró|exclamó|añadió|contestó|replicó|comentó|explicó|insistió|declaró|sugirió|ordenó|suspiró|balbuceó|interrumpió|continuó|afirmó"
    private const val VERB = "(?:$VERBS_PT|$VERBS_EN|$VERBS_ES)"
    private const val VERBS_1P = "falei|respondi|perguntei|comentei|retruquei|murmurei|sussurrei|gritei|exclamei|observei|insisti|expliquei|concluí|acrescentei|continuei|sugeri|pedi|ordenei|admiti|confessei|rebati|suspirei|declarei|afirmei|avisei|lembrei|alertei|interrompi|completei|emendei|indaguei|questionei|prossegui|anunciei|fiz|comecei|repeti|concordei|protestei|agradeci|reclamei|provoquei"
    private const val PRONOUN = "(?:ele|ela|he|she|él|ella)"
    private const val ROLE = "(?:velho|velha|homem|mulher|moça|moço|rapaz|garoto|garota|menino|menina|professor|professora|capitão|rei|rainha|doutor|doutora|padre|soldado|guarda|médico|médica|jovem|estranho|estranha|man|woman|boy|girl|stranger)"

    private val quotedSpeech = Regex("“[^”]+”|«[^»]+»|\"[^\"]+\"")
    /** Travessão, meias-riscas e barra horizontal usados como marcador de diálogo (cada livro usa um). */
    private const val DASHES = "\\u2012\\u2013\\u2014\\u2015\\u2212"
    /** Marcador de diálogo: travessão fora de palavra, ou hífen simples isolado por espaços. */
    private val dashToken = Regex("(?<![\\p{L}\\p{N}])[$DASHES]|(?:^|(?<=\\s))-(?=\\s)")
    private val leadingDash = Regex("^[\\t ]*(?:[$DASHES]|-(?=\\s))")
    private val afterName = Regex("^[\\s,;:!?$DASHES-]*(?i:$VERB)\\s+(?:\\p{L}+mente\\s+)?(?:(?i:a|o|the|el|la)\\s+)?($NAME)")
    private val afterRole = Regex("^[\\s,;:!?$DASHES-]*(?i:$VERB)\\s+(?i:o|a|the|el|la)\\s+(?i:($ROLE))\\b")
    private const val ADVERBIO = "(?:(?:\\p{L}+mente|também|então|logo|ainda|de novo)\\s+)?"
    private val afterNameVerb = Regex("^[\\s,;:!?$DASHES-]*($NAME)\\s+$ADVERBIO(?i:$VERB)")
    private val afterRoleVerb = Regex("^[\\s,;:!?$DASHES-]*(?i:o|a|the|el|la)\\s+(?i:($ROLE))\\s+$ADVERBIO(?i:$VERB)")
    private val afterPronounVerb = Regex("^[\\s,;:!?$DASHES-]*(?i:($PRONOUN))\\s+$ADVERBIO(?i:$VERB)")
    private val afterFirstPerson = Regex("^[\\s,;:!?$DASHES-]*(?:(?i:$VERB)\\s+(?i:eu)\\b|(?i:$VERBS_1P)\\b|(?i:eu)\\s+$ADVERBIO(?i:(?:$VERB|$VERBS_1P))\\b)")
    private val afterCommonSubject = Regex("^[\\s,;:!?$DASHES-]*(?i:o|a|os|as|um|uma)\\s+\\p{L}+(?:\\s+\\p{L}+)?\\s+$ADVERBIO(?i:$VERB)\\b")
    private val afterPronoun = Regex("^[\\s,;:!?$DASHES-]*(?i:$VERB)\\s+(?i:($PRONOUN))\\b")
    private val beforeName = Regex("($NAME)\\s+(?i:$VERB)\\s*[:;,$DASHES-]?\\s*$")
    private val beforePronoun = Regex("(?i:\\b($PRONOUN))\\s+(?i:$VERB)\\s*[:;,$DASHES-]?\\s*$")
    private val sentenceBreak = Regex("(?<=[.!?…])\\s+")

    /** Palavras que abrem frase com maiúscula mas não são nomes. */
    private val stopTokens = setOf(
        "ele", "ela", "eles", "elas", "eu", "tu", "voce", "nos", "entao", "mas", "e", "o", "a", "os", "as",
        "um", "uma", "he", "she", "they", "i", "we", "you", "it", "the", "el", "la", "ella", "ninguem",
        "alguem", "todos", "agora", "depois", "assim", "porem", "quando", "enquanto", "ai", "ali", "and", "but",
        "then", "so", "y", "pero", "entonces"
    )
    private val titleTokens = setOf(
        "dona", "senhora", "sra", "srta", "senhorita", "seu", "senhor", "sr", "dom", "dr", "dra", "doutor",
        "doutora", "professor", "professora", "capitao", "rei", "rainha", "tio", "tia", "padre", "frei",
        "lord", "lady", "sir", "mr", "mrs", "ms", "miss", "don"
    )
    private val articleTokens = setOf("o", "a", "the", "el", "la")

    /** Quem fala segundo a atribuição de um trecho: um nome, ou só o gênero de um pronome. */
    private data class Attribution(
        val name: String? = null,
        val pronoun: SpeakerGender? = null,
        /** "disse eu", "falei", "respondi": quem fala é o próprio narrador (romance em 1ª pessoa). */
        val firstPerson: Boolean = false,
        /** Sujeito comum fora do elenco ("a colonial disse"): fala do narrador, sem virar falante recente. */
        val other: Boolean = false
    )

    private data class Part(val text: String, val speech: Boolean, val attribution: Attribution?)
    private data class SpeechSpan(
        val containerStart: Int,
        val speechStart: Int,
        val speechEndExclusive: Int,
        val text: String,
        /** Onde a narração continua depois da fala (após o travessão que a fecha, se houver). */
        val resumeAt: Int
    )
    private data class ParsedParagraph(val parts: List<Part>)

    /** Marca no mapa de correções da IA: a fala é do narrador (ou não dá para saber). */
    const val AI_NARRATOR = ""

    /** Uma fala encontrada no texto: [ordinal] é a posição entre as falas do parágrafo (começa em 1). */
    data class SpeechRef(val paragraphIndex: Int, val ordinal: Int, val text: String)

    /** Trecho de um parágrafo, na ordem do texto, marcado como fala ou narração. */
    data class ParagraphPart(val text: String, val speech: Boolean, val ordinal: Int)

    /** Divide o parágrafo nos mesmos trechos que a análise local usa (falas numeradas a partir de 1). */
    fun partsOf(paragraph: String): List<ParagraphPart> {
        var ordinal = 0
        return parseParagraph(paragraph).parts.map { part ->
            ParagraphPart(part.text, part.speech, if (part.speech) ++ordinal else 0)
        }
    }

    /**
     * Analisa o livro. [aiOverrides] (opcional) traz correções vindas da análise por IA, no formato
     * (índice do parágrafo, nº da fala) → nome de quem fala ([AI_NARRATOR] = narrador); elas têm
     * prioridade sobre a atribuição local, que continua valendo para as falas sem correção.
     */
    fun analyze(
        paragraphs: List<String>,
        maxCharacterSpeakers: Int = MAX_CHARACTER_SPEAKERS,
        aiOverrides: Map<Pair<Int, Int>, String> = emptyMap(),
        edits: SpeakerEdits = SpeakerEdits.EMPTY
    ): SpeakerAttributionResult {
        // Correções manuais valem mais que as da IA; fusões trocam o nome antes da contagem de falantes.
        val overrides = aiOverrides + edits.lines
        val parsed = paragraphs.mapIndexed { index, text ->
            val paragraph = parseParagraph(text)
            if (overrides.isEmpty() && edits.aliases.isEmpty()) return@mapIndexed paragraph
            var ordinal = 0
            ParsedParagraph(paragraph.parts.map { part ->
                var current = part
                if (part.speech) {
                    ordinal++
                    overrides[index to ordinal]?.let { name ->
                        current = part.copy(
                            attribution = if (name == AI_NARRATOR) Attribution(firstPerson = true)
                            else Attribution(name = name)
                        )
                    }
                }
                val name = current.attribution?.name
                val merged = name?.let { edits.aliases[speakerId(it)] }
                if (merged != null) current.copy(attribution = current.attribution?.copy(name = merged)) else current
            })
        }

        // Elenco: só quem tem nome explícito, os mais falantes primeiro.
        val counts = linkedMapOf<String, Int>()
        val displayNames = linkedMapOf<String, String>()
        parsed.forEach { paragraph ->
            paragraph.parts.forEach partLoop@{ part ->
                val name = part.attribution?.name ?: return@partLoop
                val id = speakerId(name)
                counts[id] = (counts[id] ?: 0) + 1
                displayNames.putIfAbsent(id, name)
            }
        }
        val allowed = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { displayNames[it.key] })
            .take(maxCharacterSpeakers.coerceIn(0, MAX_CHARACTER_SPEAKERS))
            .map { it.key }
        val allowedSet = allowed.toSet()
        val genders = allowed.associateWith { SpeakerGenderInferrer.infer(displayNames.getValue(it), paragraphs) }

        // Resolução em ordem de leitura, com memória dos últimos falantes.
        val recent = ArrayDeque<String>()
        var lastDialogueIndex = -MAX_GAP_PARAGRAPHS - 1
        var announced: Attribution? = null
        val speakerIdsByParagraph = parsed.mapIndexed { index, paragraph ->
            if (index - lastDialogueIndex > MAX_GAP_PARAGRAPHS) recent.clear()
            val startsWithSpeech = paragraph.parts.firstOrNull()?.speech == true
            // O anúncio vale só para o parágrafo imediatamente seguinte.
            val announcement = announced.takeIf { startsWithSpeech }
            announced = paragraph.parts.takeIf { it.none { part -> part.speech } }
                ?.let { announcedSpeaker(paragraphs[index]) }
            val paragraphHasAttribution = paragraph.parts.any { it.attribution != null }
            val previousWasDialogue = index - lastDialogueIndex <= ALTERNATION_MAX_GAP
            var paragraphSpeaker: String? = null
            val ids = paragraph.parts.mapIndexed { partIndex, part ->
                if (!part.speech) return@mapIndexed NARRATOR_ID
                val attribution = part.attribution
                    ?: announcement?.takeIf { partIndex == 0 }
                val pronoun = attribution?.pronoun
                val explicitName = attribution?.name
                // Nome explícito fora do elenco ou pronome sem par de gênero: fica com o narrador.
                val resolved: String? = when {
                    attribution?.firstPerson == true || attribution?.other == true -> NARRATOR_ID
                    explicitName != null -> speakerId(explicitName).takeIf { it in allowedSet }
                    pronoun != null -> recent.lastOrNull { genders[it] == pronoun }
                    else -> neighbourSubject(paragraph.parts, partIndex, allowedSet, displayNames)
                        ?: paragraphSpeaker
                        ?: alternation(recent, startsWithSpeech && !paragraphHasAttribution && previousWasDialogue)
                }
                if (resolved != null) {
                    paragraphSpeaker = resolved
                    if (attribution?.other != true) {
                        recent.remove(resolved)
                        recent.add(resolved)
                        lastDialogueIndex = index
                    }
                }
                resolved ?: NARRATOR_ID
            }
            ids
        }

        val speakers = allowed.map { id -> DetectedSpeaker(id, displayNames.getValue(id), counts.getValue(id)) }
        val mapped = parsed.mapIndexed { index, paragraph ->
            val ids = speakerIdsByParagraph[index]
            val segments = paragraph.parts.mapIndexed { partIndex, part -> SpeakerSegment(ids[partIndex], part.text) }
                .fold(mutableListOf<SpeakerSegment>()) { acc, next ->
                    val previous = acc.lastOrNull()
                    if (previous != null && previous.speakerId == next.speakerId) {
                        acc[acc.lastIndex] = previous.copy(text = "${previous.text} ${next.text}".trim())
                    } else if (next.text.isNotBlank()) {
                        acc += next.copy(text = next.text.trim())
                    }
                    acc
                }
            ParagraphSpeakerSegments(index, segments, paragraphs[index])
        }
        return SpeakerAttributionResult(speakers, mapped)
    }

    /** Diálogo de dois: a fala sem atribuição é do interlocutor do último falante. */
    private fun alternation(recent: ArrayDeque<String>, applicable: Boolean): String? {
        if (!applicable || recent.size < 2) return null
        val last = recent.last()
        return recent.lastOrNull { it != last }
    }

    /** "Maria olhou para ele. “Vamos.”" / "“Vamos.” Maria sorriu." — sujeito conhecido na frase vizinha. */
    private fun neighbourSubject(
        parts: List<Part>,
        partIndex: Int,
        allowed: Set<String>,
        displayNames: Map<String, String>
    ): String? {
        val before = parts.getOrNull(partIndex - 1)?.takeIf { !it.speech }
            ?.text?.split(sentenceBreak)?.lastOrNull()
        val after = parts.getOrNull(partIndex + 1)?.takeIf { !it.speech }
            ?.text?.split(sentenceBreak)?.firstOrNull()
        return listOf(before, after).firstNotNullOfOrNull { sentence ->
            sentence?.let { startingSubject(it, allowed, displayNames) }
        }
    }

    private fun startingSubject(sentence: String, allowed: Set<String>, displayNames: Map<String, String>): String? {
        val trimmed = sentence.trimStart(' ', ',', ';', ':', '—', '-')
        return allowed.firstOrNull { id ->
            val name = displayNames[id] ?: return@firstOrNull false
            trimmed.startsWith(name) && trimmed.getOrNull(name.length)?.let { it.isWhitespace() } == true
        }
    }

    /** Parágrafo sem fala que termina em "Fulano disse:" — anuncia quem fala no parágrafo seguinte. */
    private fun announcedSpeaker(text: String): Attribution? {
        val tail = text.takeLast(110)
        beforeName.find(tail)?.groupValues?.getOrNull(1)?.let { cleanName(it) }?.let { return it }
        beforePronoun.find(tail)?.groupValues?.getOrNull(1)?.let { return pronounAttribution(it) }
        return null
    }

    private fun parseParagraph(text: String): ParsedParagraph {
        if (text.isBlank()) return ParsedParagraph(emptyList())
        val quoted = quotedSpeech.findAll(text).map { match ->
            SpeechSpan(match.range.first, match.range.first, match.range.last + 1, match.value, match.range.last + 1)
        }.toList()
        val dashed = dashSpans(text, quoted)
        val spans = (quoted + dashed).sortedBy { it.containerStart }
        val parts = mutableListOf<Part>()
        var cursor = 0
        spans.forEach { span ->
            if (span.containerStart < cursor) return@forEach
            addPart(parts, false, null, text.substring(cursor, span.containerStart))
            val before = text.substring(maxOf(0, span.speechStart - 100), span.speechStart)
            val after = text.substring(
                span.speechEndExclusive,
                minOf(text.length, span.speechEndExclusive + 101)
            )
            addPart(parts, true, attributionAfter(after) ?: attributionBefore(before), span.text)
            cursor = span.resumeAt
        }
        addPart(parts, false, null, text.substring(cursor))
        if (parts.isEmpty()) addPart(parts, false, null, text)
        return ParsedParagraph(parts)
    }

    /**
     * Falas marcadas por travessão. Em toda linha que ABRE com marcador, os marcadores alternam
     * abre/fecha: `― A ― disse Cob. ― B` tem duas falas (A e B), a segunda no meio da linha.
     */
    private fun dashSpans(text: String, quoted: List<SpeechSpan>): List<SpeechSpan> {
        val result = mutableListOf<SpeechSpan>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline
            val line = text.substring(lineStart, lineEnd)
            if (leadingDash.containsMatchIn(line)) {
                val tokens = dashToken.findAll(line).map { it.range }.toList()
                var k = 0
                while (k < tokens.size) {
                    val open = tokens[k]
                    val close = tokens.getOrNull(k + 1)
                    val raw = line.substring(open.last + 1, close?.first ?: line.length)
                    val spoken = raw.trim()
                    if (spoken.isNotEmpty()) {
                        val start = lineStart + open.last + 1 + raw.indexOf(spoken)
                        val endExclusive = start + spoken.length
                        if (quoted.none { it.speechStart in start until endExclusive }) {
                            result += SpeechSpan(
                                containerStart = lineStart + open.first,
                                speechStart = start,
                                speechEndExclusive = endExclusive,
                                text = spoken,
                                resumeAt = lineStart + (close?.last?.plus(1) ?: line.length)
                            )
                        }
                    }
                    k += 2
                }
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        return result
    }

    private fun attributionAfter(context: String): Attribution? {
        afterFirstPerson.find(context)?.let { return Attribution(firstPerson = true) }
        afterName.find(context)?.groupValues?.getOrNull(1)?.let { cleanName(it) }?.let { return it }
        afterRole.find(context)?.groupValues?.getOrNull(1)?.let { return roleAttribution(context, it) }
        afterNameVerb.find(context)?.groupValues?.getOrNull(1)?.let { cleanName(it) }?.let { return it }
        afterRoleVerb.find(context)?.groupValues?.getOrNull(1)?.let { return roleAttribution(context, it) }
        // Sujeito comum que não é papel conhecido nem personagem do elenco: fica com o narrador.
        if (afterCommonSubject.containsMatchIn(context)) return Attribution(other = true)
        afterPronoun.find(context)?.groupValues?.getOrNull(1)?.let { return pronounAttribution(it) }
        afterPronounVerb.find(context)?.groupValues?.getOrNull(1)?.let { return pronounAttribution(it) }
        return null
    }

    private fun attributionBefore(context: String): Attribution? {
        beforeName.find(context)?.groupValues?.getOrNull(1)?.let { cleanName(it) }?.let { return it }
        beforePronoun.find(context)?.groupValues?.getOrNull(1)?.let { return pronounAttribution(it) }
        return null
    }

    /** "o velho" → personagem "O velho". */
    private fun roleAttribution(context: String, role: String): Attribution {
        val article = Regex("(?i)\\b(o|a|the|el|la)\\s+${Regex.escape(role)}").find(context)?.groupValues?.get(1) ?: "o"
        return Attribution(name = "${article.replaceFirstChar { it.uppercase() }} ${role.lowercase()}")
    }

    private fun pronounAttribution(token: String): Attribution? = when (simplify(token)) {
        "ele", "he", "el" -> Attribution(pronoun = SpeakerGender.MALE)
        "ela", "she", "ella" -> Attribution(pronoun = SpeakerGender.FEMALE)
        else -> null
    }

    /** Tira palavras de abertura de frase ("Então Maria" → "Maria"); só pronome vira atribuição por gênero. */
    private fun cleanName(raw: String): Attribution? {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        var index = 0
        var pronoun: Attribution? = null
        while (index < tokens.size && simplify(tokens[index]) in stopTokens) {
            pronoun = pronoun ?: pronounAttribution(tokens[index])
            index++
        }
        val name = tokens.drop(index).joinToString(" ")
        return when {
            name.isNotBlank() -> Attribution(name = name)
            else -> pronoun
        }
    }

    private fun addPart(target: MutableList<Part>, speech: Boolean, attribution: Attribution?, text: String) {
        val normalized = text.trim()
        if (normalized.isNotEmpty()) target += Part(normalized, speech, attribution)
    }

    private fun simplify(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Identidade estável: "Dona Maria", "Maria" e "Maria Clara" são o mesmo personagem. */
    /** Id estável de um personagem a partir do nome (usado também pelas fusões manuais). */
    fun idOf(name: String): String = speakerId(name)

    private fun speakerId(name: String): String {
        val tokens = name.trim().split(Regex("\\s+")).map(::simplify).filter { it.isNotBlank() }
        val key = when {
            tokens.isEmpty() -> "unknown"
            tokens.first() in articleTokens -> tokens.joinToString("-")
            else -> tokens.dropWhile { it in titleTokens && tokens.size > 1 }.firstOrNull() ?: tokens.first()
        }
        val slug = key.replace(Regex("[^a-z0-9\\-]+"), "-").trim('-')
        return "character:${slug.ifBlank { "unknown" }}"
    }
}
