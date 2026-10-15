package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.VoiceOption
import com.jonjonesbr.audiobookgen.util.PacoteVozes
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogTest {

    @Test fun agrupaIdiomaExatoEDeixaVariantesComoAlternativas() {
        val ptBr = VoiceOption("pt-br", "Brasileira", "pt-BR", false, "edge")
        val ptPt = VoiceOption("pt-pt", "Portuguesa", "pt-PT", false, "edge")
        val english = VoiceOption("en", "English", "en-US", false, "edge")
        val multilingual = VoiceOption("multi", "Multilíngue", "multilingual", false, "gemini")

        val groups = VoiceCatalog.agruparPorIdiomaPreferido(
            listOf(ptBr, ptPt, english, multilingual),
            "pt-BR"
        )

        assertEquals("pt-BR", groups.idiomaPreferido)
        assertEquals(listOf(ptBr), groups.recomendadas)
        assertEquals(listOf(ptPt, english, multilingual), groups.outras)
    }

    @Test fun portuguesSemRegiaoPriorizaPtBrSemPromoverPtPtOuPortuguesGenerico() {
        val ptBr = VoiceOption("pt-br", "Brasileira", "pt-BR", false, "edge")
        val ptPt = VoiceOption("pt-pt", "Portuguesa", "pt-PT", false, "edge")
        val genericPt = VoiceOption("pt", "Português", "pt", false, "kokoro")

        val groups = VoiceCatalog.agruparPorIdiomaPreferido(listOf(ptBr, ptPt, genericPt), "pt")

        assertEquals("pt-BR", groups.idiomaPreferido)
        assertEquals(listOf(ptBr), groups.recomendadas)
        assertEquals(listOf(ptPt, genericPt), groups.outras)
    }

    @Test fun idiomaSemRegiaoRecomendaVariantesDoMesmoIdioma() {
        val voices = listOf(
            VoiceOption("es-es", "Espanhol europeu", "es-ES", false, "edge"),
            VoiceOption("es-mx", "Espanhol mexicano", "es-MX", false, "edge"),
            VoiceOption("en-us", "Inglês", "en-US", false, "edge")
        )

        val groups = VoiceCatalog.agruparPorIdiomaPreferido(voices, "es")

        assertEquals(listOf("es-es", "es-mx"), groups.recomendadas.map { it.id })
        assertEquals(listOf("en-us"), groups.outras.map { it.id })
    }

    @Test fun vozGenericaDoSistemaPermaneceNasAlternativas() {
        val genericVoice = VoiceOption(
            "android-generic", "Voz do sistema", "pt-BR", false, "android", isGenerico = true
        )
        val specificVoice = VoiceOption("pt-br", "Brasileira", "pt-BR", false, "android")

        val groups = VoiceCatalog.agruparPorIdiomaPreferido(
            listOf(genericVoice, specificVoice),
            "pt-BR"
        )

        assertEquals(listOf(specificVoice), groups.recomendadas)
        assertEquals(listOf(genericVoice), groups.outras)
    }

    @Test fun catalogoIncluiTodosOsModelosPiperPtBrComPacotesSeparados() {
        val packages = VoiceCatalog.pacotesRegistrados().filter { it.id.startsWith("piper-ptbr-") }
        assertEquals(listOf("piper-ptbr-cadu", "piper-ptbr-dii", "piper-ptbr-edresson", "piper-ptbr-faber", "piper-ptbr-jeff", "piper-ptbr-miro"), packages.map { it.id })
        assertEquals(6, packages.flatMap { it.vozes }.count { it.language == "pt-BR" })
        assertTrue(packages.all { it.engine == "kokoro" && it.download != null })
        assertTrue(packages.all { it.vozes.single().id == "${it.id}::main-pt" })
        assertEquals("kokoro", VoiceCatalog.effectiveEngine("piper-ptbr-cadu::main-pt", "edge"))
        assertEquals("piper-ptbr-dii", VoiceCatalog.modelRootId("piper-ptbr-dii::main-pt"))
        assertTrue(packages.first { it.id == "piper-ptbr-dii" }.vozes.single().description.contains("CC BY-NC-SA 4.0"))
        assertTrue(packages.first { it.id == "piper-ptbr-miro" }.vozes.single().description.contains("CC BY-NC-SA 4.0"))
    }

    @Test fun catalogoPiperIncluiDuasVozesEnUsEUmaEsMxSobDemanda() {
        val packages = VoiceCatalog.pacotesRegistrados().filter { it.id.startsWith("piper-enus-") || it.id == "piper-esmx-claude-high" }

        assertEquals(
            listOf("piper-enus-ljspeech-medium", "piper-enus-norman-medium", "piper-esmx-claude-high"),
            packages.map { it.id }
        )
        assertTrue(packages.all { it.engine == "kokoro" && it.download != null && it.tamanhoDownloadMb == 68 })
        assertEquals(listOf("en-US", "en-US", "es-MX"), packages.map { it.vozes.single().language })
        assertEquals(
            listOf("piper-enus-ljspeech-medium::main", "piper-enus-norman-medium::main", "piper-esmx-claude-high::main"),
            packages.map { it.vozes.single().id }
        )
        assertTrue(packages.all { it.vozes.single().description.contains("Model repository MIT") })
        assertEquals("kokoro", VoiceCatalog.effectiveEngine("piper-enus-ljspeech-medium::main", "edge"))
        assertEquals("piper-esmx-claude-high", VoiceCatalog.modelRootId("piper-esmx-claude-high::main"))
    }

    @Test fun piperDeOutrosIdiomasFicaEmAlternativasParaUsuarioPtBr() {
        val voices = VoiceCatalog.pacotesRegistrados()
            .filter { it.id.startsWith("piper-") }
            .flatMap { it.vozes }
        val groups = VoiceCatalog.agruparPorIdiomaPreferido(voices, "pt-BR")

        assertEquals(6, groups.recomendadas.size)
        assertEquals(listOf("en-US", "en-US", "es-MX"), groups.outras.map { it.language })
    }

    @Test fun pocketOfereceVozesOfflineParaPtBrInglesEEspanhol() {
        val voices = VoiceCatalog.forEngine("pocket")

        assertEquals(
            listOf("pocket-ptbr-rafael", "pocket-en-alba", "pocket-es-lola"),
            voices.map { it.id }
        )
        assertEquals(listOf("pt-BR", "en-US", "es-ES"), voices.map { it.language })
        assertTrue(voices.all { it.engine == "pocket" })
        assertEquals("pocket", VoiceCatalog.effectiveEngine("pocket-ptbr-rafael", "edge"))
        assertEquals("pocket-ptbr-int8", VoiceCatalog.modelRootId("pocket-ptbr-rafael"))
    }

    @Test fun pocketPriorizaSomenteIdiomaDoUsuarioEDeixaOutrosComoAlternativas() {
        val voices = VoiceCatalog.forEngine("pocket")

        val pt = VoiceCatalog.agruparPorIdiomaPreferido(voices, "pt-BR")
        assertEquals(listOf("pocket-ptbr-rafael"), pt.recomendadas.map { it.id })
        assertEquals(listOf("pocket-en-alba", "pocket-es-lola"), pt.outras.map { it.id })

        val en = VoiceCatalog.agruparPorIdiomaPreferido(voices, "en-US")
        assertEquals(listOf("pocket-en-alba"), en.recomendadas.map { it.id })
        assertEquals(listOf("pocket-ptbr-rafael", "pocket-es-lola"), en.outras.map { it.id })

        val es = VoiceCatalog.agruparPorIdiomaPreferido(voices, "es")
        assertEquals(listOf("pocket-es-lola"), es.recomendadas.map { it.id })
        assertEquals(listOf("pocket-ptbr-rafael", "pocket-en-alba"), es.outras.map { it.id })
    }

    @Test fun seletorPrincipalIncluiVozesPocketComTodosOsIdiomas() {
        val voices = VoiceCatalog.forVoiceSelection()
        val pocket = voices.filter { it.engine == "pocket" }

        assertEquals(
            listOf("pocket-ptbr-rafael", "pocket-en-alba", "pocket-es-lola"),
            pocket.map { it.id }
        )
        val pt = VoiceCatalog.agruparPorIdiomaPreferido(voices, "pt-BR")
        assertTrue(pt.recomendadas.any { it.id == "pocket-ptbr-rafael" })
        assertTrue(pt.outras.any { it.id == "pocket-en-alba" })
        assertTrue(pt.outras.any { it.id == "pocket-es-lola" })
    }

    @Test fun generoDesconhecidoMostraSomenteIdioma() {
        val voice = VoiceOption("piper", "Voz", "pt-BR", false, "kokoro", generoConhecido = false)
        assertEquals("pt-BR", voice.metaLabel("multilíngue", "masculina", "feminina"))
    }

    @Test fun vozNormal() {
        val voice = VoiceOption("test-id", "João", "pt-BR", true, "edge")
        assertEquals("João · pt-BR · masculina", voice.displayLabel("multilíngue", "masculina", "feminina"))
    }

    @Test fun vozMultilingue() {
        val voice = VoiceOption("test-id", "Ava", "multilingual", false, "edge")
        assertEquals("Ava · multilíngue · feminina", voice.displayLabel("multilíngue", "masculina", "feminina"))
    }

    @Test fun vozGenerica() {
        val voice = VoiceOption("test-id", "Voz do Sistema", "pt-BR", false, "android", isGenerico = true)
        assertEquals("Voz do Sistema", voice.displayLabel("multilíngue", "masculina", "feminina"))
    }

    @Test fun vozFeminina() {
        val voice = VoiceOption("test-id", "Maria", "es-ES", false, "edge")
        assertEquals("Maria · es-ES · feminina", voice.displayLabel("multilíngue", "masculina", "feminina"))
    }

    @Test fun metaLabelSemNome() {
        val voice = VoiceOption("test-id", "João", "pt-BR", true, "edge")
        assertEquals("pt-BR · masculina", voice.metaLabel("multilíngue", "masculina", "feminina"))
    }

    @Test fun forEngineEdge() {
        val voices = VoiceCatalog.forEngine("edge")
        assertEquals(15, voices.size)
        assertEquals("edge", voices.first().engine)
    }

    @Test fun forEngineGemini() {
        val voices = VoiceCatalog.forEngine("gemini")
        assertEquals(29, voices.size)
        assertEquals("gemini", voices.first().engine)
    }

    @Test fun forEngineOnnxIncludesSupertonic() {
        val voices = VoiceCatalog.forEngine("onnx")
        assertEquals(12, voices.size) // 12 Supertonic
        assertEquals(12, voices.count { it.id.startsWith("supertonic-") })
    }

    @Test fun effectiveEngineOnnx() {
        assertEquals("onnx", VoiceCatalog.effectiveEngine("supertonic-f1", "edge"))
    }

    @Test fun effectiveEngineAndroid() {
        assertEquals("android", VoiceCatalog.effectiveEngine("android::com.google.android.tts::pt-BR-1", "edge"))
    }

    @Test fun effectiveEngineFallback() {
        assertEquals("edge", VoiceCatalog.effectiveEngine("pt-BR-ThalitaMultilingualNeural", "edge"))
    }

    @Test fun forEngineElevenLabs() {
        val voices = VoiceCatalog.forEngine("elevenlabs")
        assertEquals(10, voices.size)
        assertEquals(10, voices.count { it.engine == "elevenlabs" })
    }

    @Test fun findAnyElevenLabsRachel() {
        val voice = VoiceCatalog.findAny("elevenlabs-21m00Tcm4TlvDq8ikWAM")
        assertEquals("Rachel", voice?.name)
    }

    @Test fun effectiveEngineElevenLabs() {
        assertEquals("elevenlabs", VoiceCatalog.effectiveEngine("elevenlabs-algumid", "edge"))
    }

    @Test fun forEngineKokoro() {
        val voices = VoiceCatalog.forEngine("kokoro")
        // Kokoro base (3 PT-BR + 28 inglês), MMS e nove pacotes Piper usam o mesmo engine.
        assertEquals(41, voices.size)
        assertEquals(41, voices.count { it.engine == "kokoro" })
        // Vozes pt-BR do Kokoro, MMS e os seis pacotes Piper brasileiros.
        assertEquals(10, voices.count { it.language == "pt-BR" })
        // O catálogo bruto ainda traz a voz MMS; quem a esconde sem instalação é a regra abaixo.
        assertTrue(voices.any { it.id == "mms-por::main-pt" })
    }

    @Test fun mmsEhPacoteOcultoSemInstalacaoENaoOsOutrosPacotes() {
        val mms = VoiceCatalog.pacotesRegistrados().first { it.id == "mms-por" }
        assertTrue(mms.ocultoSemInstalacao)
        val outros = VoiceCatalog.pacotesRegistrados().filter { it.id != "mms-por" }
        assertTrue(outros.none { it.ocultoSemInstalacao })
    }

    @Test fun visivelParaDownloadEscondePacoteOcultoSemInstalacao() {
        // Oculto e não instalado: some do catálogo.
        assertFalse(VoiceCatalog.visivelParaDownload(ocultoSemInstalacao = true) { false })
        // Oculto mas já instalado: continua visível (usar e apagar).
        assertTrue(VoiceCatalog.visivelParaDownload(ocultoSemInstalacao = true) { true })
        // Pacote comum: sempre visível.
        assertTrue(VoiceCatalog.visivelParaDownload(ocultoSemInstalacao = false) { false })
    }

    @Test fun visivelParaDownloadNaoConsultaInstalacaoDePacoteComum() {
        var consultou = false
        VoiceCatalog.visivelParaDownload(ocultoSemInstalacao = false) {
            consultou = true
            false
        }
        assertFalse(consultou)
    }

    @Test fun catalogoKokoroIncluiAsVinteEOitoVozesInglesasDoBundle() {
        val voices = VoiceCatalog.forEngine("kokoro").filter { it.id.startsWith("kokoro-en-") }
        val params = voices.mapNotNull {
            com.jonjonesbr.audiobookgen.tts.kokoroVoiceParams(it.id)
        }

        assertEquals(28, voices.size)
        assertEquals(28, params.size)
        assertEquals((0..27).toList(), params.map { it.sid })
        assertTrue(voices.all { it.language == "en-US" || it.language == "en-GB" })
        assertEquals(20, voices.count { it.language == "en-US" })
        assertEquals(8, voices.count { it.language == "en-GB" })
        assertTrue(params.all { it.lang == null })

        val groups = VoiceCatalog.agruparPorIdiomaPreferido(voices, "en-US")
        assertEquals(20, groups.recomendadas.size)
        assertEquals(8, groups.outras.size)
    }

    @Test fun findAnyKokoro() {
        assertEquals("Dora", VoiceCatalog.findAny("kokoro-pf-dora")?.name)
        assertEquals("Alex", VoiceCatalog.findAny("kokoro-pm-alex")?.name)
        assertEquals("Santa", VoiceCatalog.findAny("kokoro-pm-santa")?.name)
    }

    @Test fun effectiveEngineKokoro() {
        assertEquals("kokoro", VoiceCatalog.effectiveEngine("kokoro-pf-dora", "edge"))
    }

    @Test fun modelRootIdKokoroCompartilhaBundle() {
        assertEquals("kokoro", VoiceCatalog.modelRootId("kokoro-pf-dora"))
        assertEquals("kokoro", VoiceCatalog.modelRootId("kokoro-pm-santa"))
    }

    @Test fun previewClipUrlKokoroApontaParaClipHospedado() {
        assertEquals(
            "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/kokoro-preview-pf-dora.mp3",
            VoiceCatalog.previewClipUrl("kokoro-pf-dora")
        )
        assertEquals(
            "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/kokoro-preview-pm-santa.mp3",
            VoiceCatalog.previewClipUrl("kokoro-pm-santa")
        )
        assertEquals(null, VoiceCatalog.previewClipUrl("pt-BR-ThalitaMultilingualNeural"))
    }

    @Test fun previewClipUrlSupertonicApontaParaClipPorEstiloEIdioma() {
        val base = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/"
        // Multilíngue (sem sufixo) e variante PT da mesma voz apontam para o mesmo clipe.
        assertEquals(base + "supertonic-preview-f1-pt.mp3", VoiceCatalog.previewClipUrl("supertonic-f1"))
        assertEquals(base + "supertonic-preview-f1-pt.mp3", VoiceCatalog.previewClipUrl("supertonic-f1-pt"))
        assertEquals(base + "supertonic-preview-m3-pt.mp3", VoiceCatalog.previewClipUrl("supertonic-m3-pt"))
        assertEquals(base + "supertonic-preview-m5-pt.mp3", VoiceCatalog.previewClipUrl("supertonic-m5-pt"))
        // Ids fora do padrão não têm clipe.
        assertEquals(null, VoiceCatalog.previewClipUrl("supertonic-f6-pt"))
        assertEquals(null, VoiceCatalog.previewClipUrl("supertonic-x1"))
    }

    @Test fun previewClipUrlSupertonicMultilinguePorIdioma() {
        val base = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/"
        assertEquals(base + "supertonic-preview-f1-es.mp3", VoiceCatalog.previewClipUrl("supertonic-f1", "es"))
        assertEquals(base + "supertonic-preview-m1-en.mp3", VoiceCatalog.previewClipUrl("supertonic-m1", "en"))
        // Tag de locale é reduzida ao código; idioma sem clipe cai em pt.
        assertEquals(base + "supertonic-preview-f1-es.mp3", VoiceCatalog.previewClipUrl("supertonic-f1", "es-ES"))
        assertEquals(base + "supertonic-preview-f1-pt.mp3", VoiceCatalog.previewClipUrl("supertonic-f1", "fr"))
    }

    @Test fun previewClipUrlSupertonicPtIgnoraIdiomaPedido() {
        val base = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/"
        // Estilos -pt têm só o clipe pt, mesmo quando o aparelho pede en.
        assertEquals(base + "supertonic-preview-f2-pt.mp3", VoiceCatalog.previewClipUrl("supertonic-f2-pt", "en"))
        assertEquals(base + "supertonic-preview-m1-pt.mp3", VoiceCatalog.previewClipUrl("supertonic-m1-pt", "es"))
    }

    @Test fun idiomaPreviaSupertonicSoVariaNosMultilingues() {
        assertEquals("es", VoiceCatalog.idiomaPreviaSupertonic("supertonic-m1", "es"))
        assertEquals("pt", VoiceCatalog.idiomaPreviaSupertonic("supertonic-f1", "de"))
        assertEquals("pt", VoiceCatalog.idiomaPreviaSupertonic("supertonic-f3", "en"))
        assertEquals("pt", VoiceCatalog.idiomaPreviaSupertonic("supertonic-f1-pt", "en"))
    }

    @Test fun clipesSupertonicEsperadosSaoQuatorzeArquivos() {
        // Lista de arquivos a publicar no repo HF: 10 estilos em pt (F1..F5, M1..M5) mais
        // f1/m1 em es e en. f1-pt e m1-pt são o mesmo arquivo do clipe pt multilíngue.
        val esperados = (listOf("f", "m").flatMap { g -> (1..5).map { "$g$it" } })
            .map { "supertonic-preview-$it-pt.mp3" } +
            listOf("f1", "m1").flatMap { e -> listOf("es", "en").map { "supertonic-preview-$e-$it.mp3" } }
        val gerados = VoiceCatalog.forEngine("onnx")
            .flatMap { voz -> listOf("pt", "es", "en").map { VoiceCatalog.previewClipUrl(voz.id, it)!!.substringAfterLast('/') } }
            .distinct()
            .sorted()
        assertEquals(14, gerados.size)
        assertEquals(esperados.sorted(), gerados)
    }

    @Test fun vozesPiperUsamMotorKokoroMasNaoTemClipePreRenderizado() {
        // A amostra do motor "kokoro" só baixa clipe hospedado; sem clipe (Piper), o preview
        // precisa sintetizar localmente (AudiobookViewModel.gerarAmostra).
        val vozes = VoiceCatalog.pacotesRegistrados().filter { it.id.startsWith("piper-") }.flatMap { it.vozes }
        assertEquals(9, vozes.size)
        for (voz in vozes) {
            assertEquals("kokoro", VoiceCatalog.effectiveEngine(voz.id, "edge"))
            assertEquals(null, VoiceCatalog.previewClipUrl(voz.id))
        }
    }

    @Test fun previewClipUrlMmsApontaParaClipHospedado() {
        assertEquals(
            "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main/mms-preview-main-pt.mp3",
            VoiceCatalog.previewClipUrl("mms-por::main-pt")
        )
    }

    @Test fun previewAssetNameSupertonicMultilinguePorIdioma() {
        assertEquals("supertonic-preview-f1-pt.mp3", VoiceCatalog.previewAssetName("supertonic-f1", "pt"))
        assertEquals("supertonic-preview-f1-es.mp3", VoiceCatalog.previewAssetName("supertonic-f1", "es"))
        assertEquals("supertonic-preview-m1-en.mp3", VoiceCatalog.previewAssetName("supertonic-m1", "en"))
        // Tag de locale é reduzida ao código; idioma sem clipe cai em pt.
        assertEquals("supertonic-preview-m1-es.mp3", VoiceCatalog.previewAssetName("supertonic-m1", "es-ES"))
        assertEquals("supertonic-preview-f1-pt.mp3", VoiceCatalog.previewAssetName("supertonic-f1", "fr"))
    }

    @Test fun previewAssetNameSupertonicPtIgnoraIdiomaPedido() {
        assertEquals("supertonic-preview-f2-pt.mp3", VoiceCatalog.previewAssetName("supertonic-f2-pt", "en"))
        assertEquals("supertonic-preview-m1-pt.mp3", VoiceCatalog.previewAssetName("supertonic-m1-pt", "es"))
        assertEquals("supertonic-preview-m3-pt.mp3", VoiceCatalog.previewAssetName("supertonic-m3"))
    }

    @Test fun previewAssetNameNuloParaSupertonicForaDoPadrao() {
        // Kokoro e MMS agora têm nome de clipe (ver previewAssetNameDaFamiliaKokoroMmsEPiper).
        assertEquals(null, VoiceCatalog.previewAssetName("supertonic-f6-pt", "pt"))
        assertEquals(null, VoiceCatalog.previewAssetName("supertonic-x1", "pt"))
    }

    @Test fun catalogoKokoroSincronizadoComEngine() {
        // Guard anti-dessincronização: toda voz "kokoro-*" do catálogo precisa ter sid no
        // engine (kokoroVoiceParams). PT-BR exige lang explícito; inglês usa o lexicon padrão.
        // Vozes de pacote local no formato "<packId>::<vozId>" (BYOM/Parte A, ex.: MMS-TTS)
        // resolvem por um caminho DIFERENTE (BYOMManager.resolver, não kokoroVoiceParams) —
        // testado à parte em BYOMManagerTest, fora do escopo deste guard.
        for (voice in VoiceCatalog.forEngine("kokoro").filter { it.id.startsWith("kokoro-") }) {
            val params = com.jonjonesbr.audiobookgen.tts.kokoroVoiceParams(voice.id)
            assertTrue("voz sem mapeamento no engine: ${voice.id}", params != null)
            val expectedLang = if (voice.language == "pt-BR") "pt-BR" else null
            assertEquals("lang incorreto no engine: ${voice.id}", expectedLang, params?.lang)
        }
    }

    // ── Modelo de pacotes (V6 T2.1 — RN-3: 1 download habilita N vozes) ────────────────

    @Test fun pacoteKokoroHabilitaTresVozes() {
        val dora = VoiceCatalog.pacoteDaVoz("kokoro-pf-dora")
        val alex = VoiceCatalog.pacoteDaVoz("kokoro-pm-alex")
        val santa = VoiceCatalog.pacoteDaVoz("kokoro-pm-santa")
        // As 3 vozes compartilham o MESMO pacote (mesmo download).
        assertEquals("kokoro", dora?.id)
        assertEquals(dora, alex)
        assertEquals(dora, santa)
        assertEquals(31, dora?.vozes?.size)
        // O tamanho do download vive no PACOTE (uma vez), não por voz — modelagem 1→N.
        assertEquals(350, dora?.tamanhoDownloadMb)
        assertEquals(31, dora?.vozes?.count { it.engine == "kokoro" })
    }

    @Test fun pacoteSupertonicTambemEhUmDownloadParaNVozes() {
        val pacote = VoiceCatalog.pacoteDaVoz("supertonic-f1")
        assertEquals("supertonic-m1", pacote?.id)
        assertEquals("onnx", pacote?.engine)
        // 2 multilíngues + 5 F-pt + 5 M-pt = 12 vozes por 1 download.
        assertEquals(12, pacote?.vozes?.size)
        assertEquals(300, pacote?.tamanhoDownloadMb)
    }

    @Test fun pacoteSupertonicV1EhOpcionalSemVozesDuplicadas() {
        val pacote = VoiceCatalog.pacotesRegistrados().firstOrNull { it.id == "supertonic-v1" }
        assertEquals("onnx", pacote?.engine)
        assertEquals(emptyList<VoiceOption>(), pacote?.vozes)
        assertEquals(listOf("en-US"), pacote?.idiomas)
        assertEquals(255, pacote?.tamanhoDownloadMb)
        assertFalse(pacote?.ocultoSemInstalacao ?: true)
        // As F1/M1 continuam pertencendo só ao pacote V2 (sem duplicar no catálogo).
        assertEquals("supertonic-m1", VoiceCatalog.pacoteDaVoz("supertonic-f1")?.id)
        assertEquals(12, VoiceCatalog.forEngine("onnx").size)
        assertEquals(12, VoiceCatalog.forEngine("onnx").map { it.id }.toSet().size)
    }

    @Test fun vozDeNuvemNaoTemPacote() {
        assertEquals(null, VoiceCatalog.pacoteDaVoz("pt-BR-ThalitaMultilingualNeural"))
        assertEquals(null, VoiceCatalog.pacoteDaVoz("elevenlabs-21m00Tcm4TlvDq8ikWAM"))
        assertEquals(null, VoiceCatalog.pacoteDaVoz("android::pkg::voz"))
    }

    @Test fun pacotesDoEngineAgrupaPorMotor() {
        // "kokoro" e "mms-por" (Parte A) são 2 pacotes DISTINTOS que compartilham o motor
        // "kokoro" — cada um é seu próprio download/isPronto/delete, só o motor de síntese é
        // o mesmo (KokoroTtsEngine sabe as 2 arquiteturas, kokoro e vits).
        assertEquals(
            listOf(
                "kokoro", "mms-por", "piper-ptbr-cadu", "piper-ptbr-dii", "piper-ptbr-edresson",
                "piper-ptbr-faber", "piper-ptbr-jeff", "piper-ptbr-miro", "piper-enus-ljspeech-medium",
                "piper-enus-norman-medium", "piper-esmx-claude-high"
            ),
            VoiceCatalog.pacotesDoEngine("kokoro").map { it.id }
        )
        assertEquals(listOf("supertonic-m1", "supertonic-v1"), VoiceCatalog.pacotesDoEngine("onnx").map { it.id })
        assertEquals(emptyList<PacoteVozes>(), VoiceCatalog.pacotesDoEngine("edge"))
    }

    @Test fun registrarPacoteLocalAdicionaSemDuplicar() {
        val pacote = PacoteVozes(
            id = "teste-byom", engine = "kokoro", nomeExibicao = "BYOM teste",
            tamanhoDownloadMb = 50,
            vozes = listOf(
                VoiceOption("teste-byom-v1", "Voz BYOM", "pt-BR", false, "kokoro")
            ),
            isPronto = { true }
        )
        VoiceCatalog.registrarPacoteLocal(pacote)
        VoiceCatalog.registrarPacoteLocal(pacote) // duplicado ignora
        try {
            assertEquals("teste-byom", VoiceCatalog.pacoteDaVoz("teste-byom-v1")?.id)
            assertEquals("kokoro", VoiceCatalog.effectiveEngine("teste-byom-v1", "edge"))
            assertEquals("teste-byom", VoiceCatalog.modelRootId("teste-byom-v1"))
            // A voz do pacote BYOM aparece no catálogo do engine sem tocar na lista estática.
            assertEquals(
                "teste-byom-v1",
                VoiceCatalog.findAny("teste-byom-v1")?.id
            )
        } finally {
            // Restaura o registro (evita vazar estado entre testes).
            VoiceCatalog.removerPacoteLocal("teste-byom")
        }
    }

    @Test fun effectiveEnginePrefixoLegadoForaDoCatalogo() {
        // Equivalência estrita com o pré-T2.1: id de prefixo supertonic/kokoro que NÃO está
        // no catálogo (ex.: prefs de versão antiga com voz removida) resolve pelo prefixo.
        assertEquals("onnx", VoiceCatalog.effectiveEngine("supertonic-voz-antiga", "edge"))
        assertEquals("kokoro", VoiceCatalog.effectiveEngine("kokoro-voz-antiga", "edge"))
        assertEquals("supertonic-m1", VoiceCatalog.modelRootId("supertonic-voz-antiga"))
        assertEquals("kokoro", VoiceCatalog.modelRootId("kokoro-voz-antiga"))
    }

    @Test fun previewAssetNameDaFamiliaKokoroMmsEPiper() {
        assertEquals("kokoro-preview-pf-dora.mp3", VoiceCatalog.previewAssetName("kokoro-pf-dora"))
        assertEquals("kokoro-preview-en-af-alloy.mp3", VoiceCatalog.previewAssetName("kokoro-en-af-alloy"))
        assertEquals("mms-preview-main-pt.mp3", VoiceCatalog.previewAssetName("mms-por::main-pt"))
        assertEquals("piper-preview-piper-ptbr-cadu.mp3", VoiceCatalog.previewAssetName("piper-ptbr-cadu::main-pt"))
        assertEquals(
            "piper-preview-piper-enus-ljspeech-medium.mp3",
            VoiceCatalog.previewAssetName("piper-enus-ljspeech-medium::main")
        )
    }

    @Test fun previewAssetNameNuloParaPocketNuvemEDesconhecidos() {
        assertNull(VoiceCatalog.previewAssetName("pocket-ptbr-rafael"))
        assertNull(VoiceCatalog.previewAssetName("pt-BR-AntonioNeural"))
        assertNull(VoiceCatalog.previewAssetName("piper-inexistente::main"))
    }

    @Test fun previewClipUrlRemotaSoParaKokoroMmsESupertonic() {
        assertTrue(VoiceCatalog.previewClipUrl("kokoro-en-af-alloy")!!.endsWith("/kokoro-preview-en-af-alloy.mp3"))
        assertTrue(VoiceCatalog.previewClipUrl("mms-por::main-pt")!!.endsWith("/mms-preview-main-pt.mp3"))
        assertTrue(VoiceCatalog.previewClipUrl("supertonic-f1", "es")!!.endsWith("/supertonic-preview-f1-es.mp3"))
        assertNull(VoiceCatalog.previewClipUrl("piper-ptbr-cadu::main-pt"))
        assertNull(VoiceCatalog.previewClipUrl("pocket-ptbr-rafael"))
    }

    @Test fun clipeEmbarcadoPrimeiroSupertonicSoSemPacoteBaixado() {
        assertTrue(VoiceCatalog.usarClipeEmbarcadoPrimeiro("supertonic-f1", pacotePronto = false))
        assertFalse(VoiceCatalog.usarClipeEmbarcadoPrimeiro("supertonic-f1", pacotePronto = true))
    }

    @Test fun clipeEmbarcadoPrimeiroPiperSoSemPacoteBaixado() {
        assertTrue(VoiceCatalog.usarClipeEmbarcadoPrimeiro("piper-ptbr-cadu::main-pt", pacotePronto = false))
        assertFalse(VoiceCatalog.usarClipeEmbarcadoPrimeiro("piper-ptbr-cadu::main-pt", pacotePronto = true))
    }

    @Test fun clipeEmbarcadoPrimeiroKokoroEMmsSempre() {
        assertTrue(VoiceCatalog.usarClipeEmbarcadoPrimeiro("kokoro-pf-dora", pacotePronto = false))
        assertTrue(VoiceCatalog.usarClipeEmbarcadoPrimeiro("kokoro-pf-dora", pacotePronto = true))
        assertTrue(VoiceCatalog.usarClipeEmbarcadoPrimeiro("mms-por::main-pt", pacotePronto = false))
        assertTrue(VoiceCatalog.usarClipeEmbarcadoPrimeiro("mms-por::main-pt", pacotePronto = true))
    }

    @Test fun clipeEmbarcadoPrimeiroPocketNuncaTemAsset() {
        assertFalse(VoiceCatalog.usarClipeEmbarcadoPrimeiro("pocket-ptbr-rafael", pacotePronto = false))
        assertFalse(VoiceCatalog.usarClipeEmbarcadoPrimeiro("pocket-ptbr-rafael", pacotePronto = true))
    }

    @Test fun todaVozOfflineComClipeTemArquivoEmbarcado() {
        // Gradle roda os testes com o diretório do módulo `app` como working dir.
        val pasta = java.io.File("src/main/assets/previas")
        assertTrue("pasta de prévias não encontrada: ${pasta.absolutePath}", pasta.isDirectory)
        val vozes = VoiceCatalog.forEngine("onnx") + VoiceCatalog.forEngine("kokoro")
        assertTrue("catálogo offline vazio", vozes.isNotEmpty())
        val problemas = mutableListOf<String>()
        for (voz in vozes) {
            for (idioma in listOf("pt", "es", "en")) {
                val nome = VoiceCatalog.previewAssetName(voz.id, idioma)
                if (nome == null) {
                    problemas.add("${voz.id}: sem nome de clipe")
                } else if (!java.io.File(pasta, nome).isFile) {
                    problemas.add("${voz.id} ($idioma): $nome ausente")
                }
            }
        }
        assertTrue("clipes faltando: $problemas", problemas.isEmpty())
    }
}
