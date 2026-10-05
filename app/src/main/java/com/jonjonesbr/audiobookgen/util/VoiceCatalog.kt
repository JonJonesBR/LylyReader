package com.jonjonesbr.audiobookgen.util

import android.content.Context
import com.jonjonesbr.audiobookgen.R
import java.util.Locale

data class VoiceOption(
    val id: String,
    val name: String,
    val language: String,
    val isMale: Boolean,
    val engine: String,
    /** Texto de ajuda exibido na tela de configurações */
    val description: String = "",
    /**
     * Voz-coringa de motores de terceiros (ex.: "NOT_SET" do MultiTTS) que delega a escolha
     * real de voz/idioma para dentro do próprio app do motor — não tem idioma/gênero reais pra
     * mostrar, então [displayLabel] não deve anexar esses sufixos nem o rótulo do motor.
     */
    val isGenerico: Boolean = false,
    /**
     * Voz do motor "android" cujo pacote de dados o próprio motor (ex.: Google TTS) ainda não
     * baixou no aparelho — sintetizar com ela falha até o usuário baixar pelas Configurações do
     * Android. Ver [com.jonjonesbr.audiobookgen.ui.VoiceDownloadFlow].
     */
    val precisaBaixarNoSistema: Boolean = false,
    /** False quando o gênero não está confirmado pela fonte do modelo. */
    val generoConhecido: Boolean = true
) {
    fun displayLabel(context: Context): String =
        displayLabel(
            context.getString(R.string.voice_language_multilingual),
            context.getString(R.string.voice_gender_male),
            context.getString(R.string.voice_gender_female)
        )

    fun displayLabel(multilingualLabel: String, maleLabel: String, femaleLabel: String): String {
        if (isGenerico) return name
        return "$name · ${metaLabel(multilingualLabel, maleLabel, femaleLabel)}"
    }

    /**
     * Só a parte de idioma/gênero (sem o nome) — usada em listas que mostram nome e
     * metadados em linhas separadas (ex.: [VoiceBottomSheet]) para evitar uma única
     * linha longa e com quebra ruim.
     */
    fun metaLabel(context: Context): String =
        metaLabel(
            context.getString(R.string.voice_language_multilingual),
            context.getString(R.string.voice_gender_male),
            context.getString(R.string.voice_gender_female)
        )

    fun metaLabel(multilingualLabel: String, maleLabel: String, femaleLabel: String): String {
        val languageLabel =
            if (language == "multilingual") multilingualLabel
            else language
        if (!generoConhecido) return languageLabel
        val genderLabel = if (isMale) maleLabel else femaleLabel
        return "$languageLabel · $genderLabel"
    }
}

/**
 * UM pacote de download que habilita N vozes (V6 T2.1 — RN-3).
 *
 * A unidade de download é o PACOTE, nunca a voz individual — uma voz nunca é baixada sozinha.
 * Casos reais: Kokoro (1 bundle FP32 ~350MB → Dora/Alex/Santa), Supertonic (1 pacote V2 → 12+
 * vozes) e, no futuro, a Parte A (MMS-por: 1 bundle → 1+ vozes) e a Parte B (BYOM: arquivo
 * importado registra 1+ vozes). O estado de prontidão ([isPronto]) pertence ao pacote.
 */
/** Assinatura de progresso dos downloads de pacote (mesma do Supertonic/Kokoro). */
typealias ProgressoDownload = (nome: String, pct: Float, bytes: Long, total: Long) -> Unit

data class PacoteVozes(
    /** Raiz do modelo (o que o [VoiceCatalog.modelRootId] devolve) — ex.: "kokoro". */
    val id: String,
    /** Motor que sintetiza as vozes do pacote (ex.: "kokoro", "onnx"). */
    val engine: String,
    /** Nome amigável do pacote para UI. */
    val nomeExibicao: String,
    /** Tamanho ÚNICO do download do pacote em MB (não por voz). */
    val tamanhoDownloadMb: Int,
    /** Vozes habilitadas por este download. */
    val vozes: List<VoiceOption>,
    /** Prontidão do DOWNLOAD do pacote (não por voz). */
    val isPronto: (Context) -> Boolean,
    /** Bytes ocupados em disco quando baixado (0 = não baixado). */
    val tamanhoOcupadoBytes: (Context) -> Long = { 0L },
    /** Mecanismo de download do pacote (a UI mostra diálogo/progresso; aqui só o download).
     * Null = sem download (nuvem não entra na gestão offline). */
    val download: (suspend (Context, ProgressoDownload) -> Unit)? = null,
    /** Remove o pacote do disco (restaura o estado "não baixado"). */
    val delete: (Context) -> Unit = {}
)

/** Vozes separadas pela correspondência com o idioma atual do app. */
data class GruposVozesPorIdioma(
    val idiomaPreferido: String?,
    val recomendadas: List<VoiceOption>,
    val outras: List<VoiceOption>
)

// Registro de pacotes + consultas de catálogo cresceram de forma legítima com o modelo
// "1 download → N vozes" (T2.1/RN-3) e as APIs de runtime da Parte B — supressão pontual.
@Suppress("TooManyFunctions")
object VoiceCatalog {

    /** Supertonic distribui 5 estilos por gênero (F1–F5 e M1–M5). */
    private const val SUPERTONIC_STYLES_PER_GENDER = 5

    /** As 13 primeiras vozes Gemini da lista são femininas (as demais, masculinas). */
    private const val GEMINI_VOZES_FEMININAS = 13

    // ──────────────────────────────────────────────────────────────────────────
    //  Catálogos fixos (nuvem — não têm download)
    // ──────────────────────────────────────────────────────────────────────────

    // Edge TTS (online, sem download)
    private val edgeVoices = listOf(
        VoiceOption("pt-BR-ThalitaMultilingualNeural", "Thalita",   "pt-BR", false, "edge"),
        VoiceOption("pt-BR-AntonioNeural",             "Antonio",   "pt-BR", true,  "edge"),
        VoiceOption("pt-BR-FranciscaNeural",           "Francisca", "pt-BR", false, "edge"),
        VoiceOption("pt-PT-RaquelNeural",              "Raquel",    "pt-PT", false, "edge"),
        VoiceOption("en-US-AvaMultilingualNeural",     "Ava",       "en-US", false, "edge"),
        VoiceOption("en-US-AndrewMultilingualNeural",  "Andrew",    "en-US", true,  "edge"),
        VoiceOption("en-US-EmmaMultilingualNeural",    "Emma",      "en-US", false, "edge"),
        VoiceOption("en-US-BrianMultilingualNeural",   "Brian",     "en-US", true,  "edge"),
        VoiceOption("en-AU-WilliamMultilingualNeural", "William",   "en-AU", true,  "edge"),
        VoiceOption("fr-FR-VivienneMultilingualNeural","Vivienne",  "fr-FR", false, "edge"),
        VoiceOption("fr-FR-RemyMultilingualNeural",    "Remy",      "fr-FR", true,  "edge"),
        VoiceOption("de-DE-SeraphinaMultilingualNeural","Seraphina","de-DE", false, "edge"),
        VoiceOption("de-DE-FlorianMultilingualNeural", "Florian",   "de-DE", true,  "edge"),
        VoiceOption("it-IT-GiuseppeMultilingualNeural","Giuseppe",  "it-IT", true,  "edge"),
        VoiceOption("ko-KR-HyunsuMultilingualNeural",  "Hyunsu",   "ko-KR", true,  "edge")
    )

    // Gemini TTS (online, requer chave Gemini)
    private val geminiVoices = listOf(
        "Aoede" to "Conversacional", "Kore" to "Energética", "Leda" to "Profissional",
        "Zephyr" to "Brilhante", "Achird" to "Especializada", "Algenib" to "Confiante",
        "Callirrhoe" to "Profissional", "Despina" to "Acolhedora", "Erinome" to "Articulada",
        "Laomedeia" to "Inquisitiva", "Pulcherrima" to "Animada", "Sulafat" to "Persuasiva",
        "Vindemiatrix" to "Calma", "Puck" to "Animado", "Charon" to "Suave", "Orus" to "Maduro",
        "Autonoe" to "Ressonante", "Iapetus" to "Claro", "Umbriel" to "Suave",
        "Achernar" to "Amigável", "Alnilam" to "Energético", "Enceladus" to "Entusiasta",
        "Fenrir" to "Natural", "Gacrux" to "Confiante", "Rasalgethi" to "Conversacional",
        "Sadachbia" to "Grave", "Sadaltager" to "Entusiasta", "Schedar" to "Casual",
        "Zubenelgenubi" to "Poderoso"
    ).mapIndexed { index, (name, _) ->
        VoiceOption(name, name, "multilingual", index >= GEMINI_VOZES_FEMININAS, "gemini")
    }

    // ElevenLabs (online, requer chave própria) — vozes "premade" públicas, modelo
    // eleven_multilingual_v2 (cobre pt-BR entre outros ~29 idiomas).
    private val elevenLabsVoices = listOf(
        Triple("21m00Tcm4TlvDq8ikWAM", "Rachel", false) to "Calma, narração",
        Triple("pNInz6obpgDQGcFmaJgB", "Adam", true) to "Grave, narração",
        Triple("EXAVITQu4vr4xnSDxMaL", "Sarah", false) to "Suave",
        Triple("ErXwobaYiN019PkySvjV", "Antoni", true) to "Narração",
        Triple("MF3mGyEYCl7XYWbV9V6O", "Elli", false) to "Emotiva",
        Triple("TxGEqnHWrfWFTfGW9XjX", "Josh", true) to "Grave, narração",
        Triple("onwK4e9ZLuTAKqWW03F9", "Daniel", true) to "Autoritativa",
        Triple("ThT5KcBeYPX3keUQqHPh", "Dorothy", false) to "Agradável",
        Triple("XB0fDUnXU5powFXDhCwa", "Charlotte", false) to "Envolvente",
        Triple("N2lVS1w4EtoT3dr4eOWO", "Callum", true) to "Intensa"
    ).map { (info, desc) ->
        val (id, nome, masculina) = info
        VoiceOption(
            "elevenlabs-$id", nome, "multilingual", masculina, "elevenlabs",
            description = desc
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Pacotes de download locais (RN-3 / T2.1): UM download habilita N vozes.
    //  Registro é copy-on-write — a Parte B (BYOM) registra pacotes em runtime via
    //  [registrarPacoteLocal] sem tocar nesta lista.
    // ──────────────────────────────────────────────────────────────────────────

    // Supertonic (offline, ONNX, ~300MB — pacote V2 multilíngue en/ko/es/pt/fr).
    // As 10 vozes PT-BR (F1–F5 e M1–M5) usam estilos embutidos no APK
    // (assets/supertonic/v2/voice_styles); só o modelo precisa de download.
    private val supertonicVoices: List<VoiceOption> = buildList {
        add(
            VoiceOption(
                "supertonic-f1", "F1 (Supertonic 3)", "multilingual", false, "onnx",
                description = "Voz feminina multilíngue · Supertonic 3"
            )
        )
        add(
            VoiceOption(
                "supertonic-m1", "M1 (Supertonic 3)", "multilingual", true, "onnx",
                description = "Voz masculina multilíngue · Supertonic 3"
            )
        )
        for (n in 1..SUPERTONIC_STYLES_PER_GENDER) {
            add(
                VoiceOption(
                    "supertonic-f$n-pt", "F$n PT (Supertonic 3)", "pt-BR", false, "onnx",
                    description = "Voz feminina em Português · Supertonic 3 · estilo embutido no app"
                )
            )
        }
        for (n in 1..SUPERTONIC_STYLES_PER_GENDER) {
            add(
                VoiceOption(
                    "supertonic-m$n-pt", "M$n PT (Supertonic 3)", "pt-BR", true, "onnx",
                    description = "Voz masculina em Português · Supertonic 3 · estilo embutido no app"
                )
            )
        }
    }

    // Kokoro (offline, Sherpa-ONNX local, FP32 ~350MB — download na 1ª seleção).
    // Vozes pt-BR validadas por ouvido com lang="pt-BR" explícito (RN-1 do V6).
    // sid/lang por voz vivem no KokoroTtsEngine.kokoroVoiceParams — a lista e o engine são
    // mantidos em sincronia pelo teste KokoroTtsEngineTest/VoiceCatalogTest.
    private val kokoroVoices = listOf(
        VoiceOption(
            "kokoro-pf-dora", "Dora", "pt-BR", false, "kokoro",
            description = "Voz Kokoro (Sherpa-ONNX local) · Português do Brasil"
        ),
        VoiceOption(
            "kokoro-pm-alex", "Alex", "pt-BR", true, "kokoro",
            description = "Voz Kokoro (Sherpa-ONNX local) · Português do Brasil"
        ),
        VoiceOption(
            "kokoro-pm-santa", "Santa", "pt-BR", true, "kokoro",
            description = "Voz Kokoro (Sherpa-ONNX local) · Português do Brasil"
        )
    ) + listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore",
        "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky", "am_adam",
        "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
        "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis"
    ).map { voiceId ->
        val prefix = voiceId.substringBefore('_')
        val name = voiceId.substringAfter('_').replaceFirstChar { it.uppercase() }
        val catalogId = voiceId.replace('_', '-')
        val language = if (prefix.startsWith("b")) "en-GB" else "en-US"
        VoiceOption(
            id = "kokoro-en-$catalogId",
            name = name,
            language = language,
            isMale = prefix.endsWith("m"),
            engine = "kokoro"
        )
    }

    // MMS-TTS Português (offline, Sherpa-ONNX local, VITS — V6 Parte A, ~100MB).
    // Voz única (modelo single-speaker do Meta) — id "main-pt" é o vozIdInterno que o
    // BYOMManager reconstrói a partir do manifesto.json do bundle (id "main" + sufixo "-pt"
    // porque idioma começa com "pt" — ver BYOMManager.resolver/construirPacote). Roteia pelo
    // MESMO caminho BYOM do KokoroTtsEngine (voiceId com "::"), arquitetura "vits" — não é um
    // motor novo, reaproveita o suporte a VITS que a Parte B já trouxe.
    private val mmsPtVoices = listOf(
        VoiceOption(
            "mms-por::main-pt", "Português (MMS)", "pt-BR", false, "kokoro",
            description = "Voz MMS-TTS (Meta, Sherpa-ONNX local) · Português · CC-BY-NC 4.0"
        )
    )

    // Pocket TTS 3.3 (ONNX INT8, presets públicos fixos, sem áudio de referência/clonagem).
    // Cada língua baixa seu próprio pacote para que a lista possa priorizar o locale do app e
    // não induza o usuário a escolher uma voz treinada para outra língua.
    private val pocketVoices = listOf(
        VoiceOption(
            "pocket-ptbr-rafael", "Rafael", "pt-BR", true, "pocket",
            description = "Pocket TTS 3.3 · Português · preset público fixo · offline"
        ),
        VoiceOption(
            "pocket-en-alba", "Alba", "en-US", false, "pocket",
            description = "Pocket TTS 3.3 · English · fixed public preset · offline"
        ),
        VoiceOption(
            "pocket-es-lola", "Lola", "es-ES", false, "pocket",
            description = "Pocket TTS 3.3 · Español · preset público fijo · offline"
        )
    )

    @Volatile
    private var pacotesLocais: List<PacoteVozes> = listOf(
        PacoteVozes(
            id = "supertonic-m1",
            engine = "onnx",
            nomeExibicao = "Supertonic (ONNX local)",
            tamanhoDownloadMb = 300,
            vozes = supertonicVoices,
            isPronto = { ctx ->
                com.jonjonesbr.audiobookgen.tts.OnnxModelManager(ctx)
                    .isModelDownloaded("supertonic-m1")
            },
            tamanhoOcupadoBytes = { ctx ->
                tamanhoDiretorioEmBytes(java.io.File(ctx.filesDir, "v2"))
            },
            download = { ctx, progresso ->
                com.jonjonesbr.audiobookgen.tts.SupertonicAssetManager.downloadV2(ctx, progresso)
            },
            delete = { ctx ->
                com.jonjonesbr.audiobookgen.tts.SupertonicAssetManager.deleteVersion(ctx, "v2")
                // Estilos embutidos voltam com o APK (mesmo comportamento do
                // deleteAllOfflineModels do OnnxModelManager).
                com.jonjonesbr.audiobookgen.tts.SupertonicAssetManager.copyBundledVoiceStyles(ctx, "v2")
            }
        ),
        PacoteVozes(
            id = "kokoro",
            engine = "kokoro",
            nomeExibicao = "Kokoro (Sherpa-ONNX local)",
            tamanhoDownloadMb = 350,
            vozes = kokoroVoices,
            isPronto = { ctx -> com.jonjonesbr.audiobookgen.tts.KokoroModelManager.isReady(ctx) },
            tamanhoOcupadoBytes = { ctx ->
                com.jonjonesbr.audiobookgen.tts.KokoroModelManager.tamanhoOcupado(ctx)
            },
            download = { ctx, progresso ->
                com.jonjonesbr.audiobookgen.tts.KokoroModelManager.download(ctx, progresso)
            },
            delete = { ctx -> com.jonjonesbr.audiobookgen.tts.KokoroModelManager.delete(ctx) }
        ),
        PacoteVozes(
            id = "mms-por",
            engine = "kokoro",
            nomeExibicao = "MMS-TTS Português (Sherpa-ONNX local)",
            tamanhoDownloadMb = 100,
            vozes = mmsPtVoices,
            isPronto = { ctx -> com.jonjonesbr.audiobookgen.tts.MmsPtModelManager.isReady(ctx) },
            tamanhoOcupadoBytes = { ctx ->
                com.jonjonesbr.audiobookgen.tts.MmsPtModelManager.tamanhoOcupado(ctx)
            },
            download = { ctx, progresso ->
                com.jonjonesbr.audiobookgen.tts.MmsPtModelManager.download(ctx, progresso)
            },
            delete = { ctx -> com.jonjonesbr.audiobookgen.tts.MmsPtModelManager.delete(ctx) }
        )
    ) + listOf(
        PacoteVozes(
            id = "pocket-ptbr-int8",
            engine = "pocket",
            nomeExibicao = "Pocket TTS · Português do Brasil",
            tamanhoDownloadMb = 132,
            vozes = listOf(pocketVoices[0]),
            isPronto = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.isReady(ctx, "pocket-ptbr-int8")
            },
            tamanhoOcupadoBytes = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.tamanhoOcupado(ctx, "pocket-ptbr-int8")
            },
            download = { ctx, progresso ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.download(ctx, "pocket-ptbr-int8", progresso)
            },
            delete = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.delete(ctx, "pocket-ptbr-int8")
            }
        ),
        PacoteVozes(
            id = "pocket-en-int8",
            engine = "pocket",
            nomeExibicao = "Pocket TTS · English",
            tamanhoDownloadMb = 132,
            vozes = listOf(pocketVoices[1]),
            isPronto = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.isReady(ctx, "pocket-en-int8")
            },
            tamanhoOcupadoBytes = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.tamanhoOcupado(ctx, "pocket-en-int8")
            },
            download = { ctx, progresso ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.download(ctx, "pocket-en-int8", progresso)
            },
            delete = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.delete(ctx, "pocket-en-int8")
            }
        ),
        PacoteVozes(
            id = "pocket-es-int8",
            engine = "pocket",
            nomeExibicao = "Pocket TTS · Español",
            tamanhoDownloadMb = 132,
            vozes = listOf(pocketVoices[2]),
            isPronto = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.isReady(ctx, "pocket-es-int8")
            },
            tamanhoOcupadoBytes = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.tamanhoOcupado(ctx, "pocket-es-int8")
            },
            download = { ctx, progresso ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.download(ctx, "pocket-es-int8", progresso)
            },
            delete = { ctx ->
                com.jonjonesbr.audiobookgen.tts.PocketTtsModelManager.delete(ctx, "pocket-es-int8")
            }
        )
    ) + com.jonjonesbr.audiobookgen.tts.PiperVitsModelManager.pacotes()

    /** Registra um pacote de vozes local em runtime (Parte B — BYOM). Copy-on-write. */
    @Synchronized
    fun registrarPacoteLocal(pacote: PacoteVozes) {
        if (pacotesLocais.none { it.id == pacote.id }) {
            pacotesLocais = pacotesLocais + pacote
        }
    }

    /** Remove um pacote registrado em runtime (gestão de modelos — Parte B/BYOM). */
    @Synchronized
    fun removerPacoteLocal(id: String) {
        pacotesLocais = pacotesLocais.filterNot { it.id == id }
    }

    /** Voz → pacote que a habilita (1 download → N vozes). Null = sem download (nuvem). */
    fun pacoteDaVoz(vozId: String): PacoteVozes? =
        pacotesLocais.firstOrNull { pacote -> pacote.vozes.any { it.id == vozId } }

    fun pacotesDoEngine(engine: String): List<PacoteVozes> =
        pacotesLocais.filter { it.engine == engine }

    /** Todos os pacotes registrados (built-ins + BYOM em runtime) — gestão de Ajustes. */
    fun pacotesRegistrados(): List<PacoteVozes> = pacotesLocais

    private fun vozesDosPacotes(engine: String): List<VoiceOption> =
        pacotesLocais.filter { it.engine == engine }.flatMap { it.vozes }

    // ──────────────────────────────────────────────────────────────────────────
    //  API pública
    // ──────────────────────────────────────────────────────────────────────────

    fun forEngine(engine: String): List<VoiceOption> = when (engine) {
        "edge" -> edgeVoices
        "gemini" -> geminiVoices
        "elevenlabs" -> elevenLabsVoices
        else -> vozesDosPacotes(engine).ifEmpty { edgeVoices }
    }

    /** Voice options exposed by the main in-app selector (Android voices have a separate flow). */
    fun forVoiceSelection(): List<VoiceOption> =
        listOf("edge", "onnx", "kokoro", "pocket", "gemini", "elevenlabs")
            .flatMap(::forEngine)

    fun defaultFor(engine: String): VoiceOption = forEngine(engine).first()

    /**
     * Separa vozes explicitamente identificadas com o idioma do app das demais.
     * Para português sem região, o app prioriza pt-BR; uma voz genérica "pt" ou
     * multilíngue não é apresentada como especificamente brasileira.
     */
    fun agruparPorIdiomaPreferido(
        voices: List<VoiceOption>,
        idiomaPreferido: String?
    ): GruposVozesPorIdioma {
        val preferido = parseLanguageTag(idiomaPreferido)?.let { locale ->
            if (locale.language.equals("pt", ignoreCase = true) && locale.country.isBlank()) {
                Locale.forLanguageTag("pt-BR")
            } else {
                locale
            }
        }
        val (recomendadas, outras) = if (preferido == null) {
            emptyList<VoiceOption>() to voices
        } else {
            voices.partition { voice ->
                if (voice.isGenerico) return@partition false
                val localeVoz = parseLanguageTag(voice.language) ?: return@partition false
                localeVoz.language.equals(preferido.language, ignoreCase = true) &&
                    (preferido.country.isBlank() ||
                        localeVoz.country.equals(preferido.country, ignoreCase = true))
            }
        }
        return GruposVozesPorIdioma(
            idiomaPreferido = preferido?.toLanguageTag(),
            recomendadas = recomendadas,
            outras = outras
        )
    }

    private fun parseLanguageTag(tag: String?): Locale? {
        val normalized = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }
            ?: return null
        if (normalized.equals("multilingual", ignoreCase = true)) return null
        return Locale.forLanguageTag(normalized).takeIf {
            it.language.isNotBlank() && !it.language.equals("und", ignoreCase = true)
        }
    }

    fun find(engine: String, id: String?): VoiceOption? =
        forEngine(engine).firstOrNull { it.id == id }

    /** Busca uma voz pelo ID em todos os engines (útil ao trocar de motor TTS). */
    fun findAny(id: String?): VoiceOption? =
        (edgeVoices + geminiVoices + elevenLabsVoices +
            pacotesLocais.flatMap { it.vozes }).firstOrNull { it.id == id }

    /**
     * Resolve o motor que realmente vai sintetizar [voiceId], que pode divergir do motor salvo
     * em preferências ([fallbackMotor]). Vozes de pacote local resolvem pelo PACOTE (BYOM e
     * motores novos herdam isso automaticamente); nuvem/Android resolvem por prefixo.
     */
    fun effectiveEngine(voiceId: String, fallbackMotor: String): String {
        // Pacote primeiro (cobre BYOM/motores novos sem prefixo); depois os prefixos legados
        // exatos do pré-T2.1 — equivalência ESTRITA também para ids de prefixo fora do
        // catálogo (ex.: voz supertonic salva em prefs de versão antiga com voz removida).
        pacoteDaVoz(voiceId)?.let { return it.engine }
        return when {
            com.jonjonesbr.audiobookgen.tts.AndroidVoiceId.isAndroidVoice(voiceId) -> "android"
            voiceId.startsWith("supertonic-") -> "onnx"
            voiceId.startsWith("kokoro-") -> "kokoro"
            voiceId.startsWith("pocket-") -> "pocket"
            voiceId.startsWith("elevenlabs-") -> "elevenlabs"
            else -> fallbackMotor
        }
    }

    /** Retorna a raiz do modelo/pacote que a voz compartilha (ex.: kokoro → "kokoro"). */
    fun modelRootId(voiceId: String): String {
        pacoteDaVoz(voiceId)?.let { return it.id }
        return when {
            voiceId.startsWith("supertonic-") -> "supertonic-m1"
            voiceId.startsWith("kokoro-") -> "kokoro"
            else -> voiceId
        }
    }

    /**
     * Prontidão de download da voz: delega ao PACOTE (todas as vozes do mesmo pacote ficam
     * prontas juntas). Voz de nuvem (sem pacote) = sempre pronto.
     */
    fun modeloProntoParaVoz(vozId: String, context: Context): Boolean =
        pacoteDaVoz(vozId)?.isPronto?.invoke(context) ?: true

    private const val PREVIEW_TEXT_PT = "Olá! Esta é uma prévia da voz selecionada para a leitura do seu livro."
    private const val PREVIEW_TEXT_EN = "Hello! This is a preview of the selected voice for reading your book."
    private const val PREVIEW_TEXT_ES = "¡Hola! Esta es una vista previa de la voz seleccionada para leer tu libro."
    private const val PREVIEW_TEXT_FR = "Bonjour ! Voici un aperçu de la voix choisie pour la lecture de votre livre."
    private const val PREVIEW_TEXT_DE = "Hallo! Dies ist eine Vorschau der gewählten Stimme zum Vorlesen Ihres Buches."
    private const val PREVIEW_TEXT_IT = "Ciao! Questa è un'anteprima della voce scelta per la lettura del tuo libro."
    private const val PREVIEW_TEXT_KO = "안녕하세요! 선택한 목소리로 책을 읽어드리는 미리듣기입니다."

    /**
     * URL do clipe de preview pré-renderizado (vozes offline Kokoro/MMS-TTS, ambas via
     * KokoroTtsEngine): o app baixa só o clipe (poucos KB) em vez de exigir o bundle completo
     * antes de o usuário ouvir a voz. Os clipes são gerados com o mesmo texto de preview e
     * hospedados no mesmo repo HF do bundle (Kokoro = V6 T1.5; MMS-TTS = V6 Parte A).
     */
    fun previewClipUrl(vozId: String): String? {
        val nomeArquivo = when {
            vozId.startsWith("kokoro-") -> "kokoro-preview-${vozId.removePrefix("kokoro-")}.mp3"
            vozId == "mms-por::main-pt" -> "mms-preview-main-pt.mp3"
            else -> return null
        }
        return "${com.jonjonesbr.audiobookgen.tts.KokoroModelManager.BASE_URL}/$nomeArquivo"
    }

    /**
     * Frase de teste no idioma da própria voz — usada no preview (▶) em vez de um texto em
     * inglês fixo, que soava estranho em vozes não-inglesas (ex.: o preview do Supertonic
     * multilíngue detectava "en" pelo texto e testava um idioma diferente do usado de fato
     * na leitura guiada, que é majoritariamente PT).
     */
    fun previewSampleText(voice: VoiceOption): String = when {
        voice.language.startsWith("pt") -> PREVIEW_TEXT_PT
        voice.language.startsWith("es") -> PREVIEW_TEXT_ES
        voice.language.startsWith("fr") -> PREVIEW_TEXT_FR
        voice.language.startsWith("de") -> PREVIEW_TEXT_DE
        voice.language.startsWith("it") -> PREVIEW_TEXT_IT
        voice.language.startsWith("ko") -> PREVIEW_TEXT_KO
        voice.language.startsWith("en") -> PREVIEW_TEXT_EN
        // "multilingual" (Gemini, Edge *MultilingualNeural, Supertonic F1/M1/genéricas):
        // usa PT como padrão — é o idioma predominante na leitura guiada deste app.
        else -> PREVIEW_TEXT_PT
    }
}


private fun tamanhoDiretorioEmBytes(diretorio: java.io.File): Long {
    if (!diretorio.exists()) return 0L
    return diretorio.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
