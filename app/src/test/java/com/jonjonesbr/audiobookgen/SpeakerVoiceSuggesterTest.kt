package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.DetectedSpeaker
import com.jonjonesbr.audiobookgen.domain.SpeakerGender
import com.jonjonesbr.audiobookgen.domain.SpeakerGenderInferrer
import com.jonjonesbr.audiobookgen.domain.SpeakerVoiceSuggester
import com.jonjonesbr.audiobookgen.util.VoiceOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerVoiceSuggesterTest {

    private fun voz(
        id: String,
        masculina: Boolean,
        idioma: String = "pt-BR",
        generoConhecido: Boolean = true,
        generica: Boolean = false,
        baixarNoSistema: Boolean = false,
        motor: String = "kokoro"
    ) = VoiceOption(
        id = id, name = id, language = idioma, isMale = masculina, engine = motor,
        isGenerico = generica, precisaBaixarNoSistema = baixarNoSistema, generoConhecido = generoConhecido
    )

    private fun personagem(id: String, nome: String = id, falas: Int = 5) =
        DetectedSpeaker("character:$id", nome, falas)

    // ── Gênero ────────────────────────────────────────────────────────────────

    @Test fun titulosDefinemOGenero() {
        assertEquals(SpeakerGender.FEMALE, SpeakerGenderInferrer.infer("Dona Rosa", emptyList()))
        assertEquals(SpeakerGender.MALE, SpeakerGenderInferrer.infer("Senhor Silva", emptyList()))
        assertEquals(SpeakerGender.FEMALE, SpeakerGenderInferrer.infer("Rainha", emptyList()))
        assertEquals(SpeakerGender.MALE, SpeakerGenderInferrer.infer("Tio Vânia", emptyList()))
    }

    @Test fun terminacaoDefineOGeneroQuandoNaoHaEvidenciaMelhor() {
        assertEquals(SpeakerGender.FEMALE, SpeakerGenderInferrer.infer("Marina", emptyList()))
        assertEquals(SpeakerGender.MALE, SpeakerGenderInferrer.infer("Pedro", emptyList()))
        assertEquals(SpeakerGender.UNKNOWN, SpeakerGenderInferrer.infer("Alex", emptyList()))
    }

    @Test fun nomesComunsFogemDaRegraDaTerminacao() {
        assertEquals(SpeakerGender.FEMALE, SpeakerGenderInferrer.infer("Isabel", emptyList()))
        assertEquals(SpeakerGender.MALE, SpeakerGenderInferrer.infer("Luca", emptyList()))
    }

    @Test fun artigoAntesDoNomeDesempataQuandoHaEvidenciaSuficiente() {
        val textos = listOf("Ontem vi a Kim no mercado.", "Depois a Kim voltou para casa.")
        assertEquals(SpeakerGender.FEMALE, SpeakerGenderInferrer.infer("Kim", textos))
        val masculino = listOf("Chamei o Ariel.", "O pai levou o Ariel embora.")
        assertEquals(SpeakerGender.MALE, SpeakerGenderInferrer.infer("Ariel", masculino))
    }

    @Test fun umaUnicaOcorrenciaDeArtigoNaoBasta() {
        assertEquals(SpeakerGender.UNKNOWN, SpeakerGenderInferrer.infer("Kim", listOf("Ontem vi a Kim.")))
    }

    // ── Sugestão de vozes ─────────────────────────────────────────────────────

    @Test fun sugereVozesDistintasCompativeisComOGenero() {
        val candidatas = listOf(voz("f1", false), voz("m1", true), voz("f2", false))
        val personagens = listOf(personagem("ana"), personagem("bia"), personagem("caio"))
        val genero = mapOf(
            "character:ana" to SpeakerGender.FEMALE,
            "character:bia" to SpeakerGender.FEMALE,
            "character:caio" to SpeakerGender.MALE
        )

        val resultado = SpeakerVoiceSuggester.suggest(personagens, genero, candidatas, "narrador", "pt-BR") { true }

        assertEquals(listOf("f1", "f2", "m1"), resultado.map { it.voice?.id })
    }

    @Test fun nuncaSugereAVozDoNarrador() {
        val candidatas = listOf(voz("narrador", false), voz("f2", false))
        val resultado = SpeakerVoiceSuggester.suggest(
            listOf(personagem("ana")), mapOf("character:ana" to SpeakerGender.FEMALE),
            candidatas, "narrador", "pt-BR"
        ) { true }
        assertEquals("f2", resultado.single().voice?.id)
    }

    @Test fun ignoraVozesNaoProntasGenericasEQuePrecisamDeDownloadNoSistema() {
        val candidatas = listOf(
            voz("naoPronta", false), voz("generica", false, generica = true),
            voz("sistema", false, baixarNoSistema = true), voz("ok", false)
        )
        val resultado = SpeakerVoiceSuggester.suggest(
            listOf(personagem("ana")), mapOf("character:ana" to SpeakerGender.FEMALE),
            candidatas, null, "pt-BR"
        ) { it.id != "naoPronta" }
        assertEquals("ok", resultado.single().voice?.id)
    }

    @Test fun mantemOIdiomaDoNarradorEPrefereMesmaRegiao() {
        val candidatas = listOf(
            voz("multi", false, idioma = "multilingual"), voz("pt-PT", false, idioma = "pt-PT"),
            voz("en", false, idioma = "en-US"), voz("br", false, idioma = "pt-BR")
        )
        val resultado = SpeakerVoiceSuggester.suggest(
            listOf(personagem("ana"), personagem("bia"), personagem("cida")),
            emptyMap(), candidatas, null, "pt-BR"
        ) { true }
        assertEquals(listOf("br", "pt-PT", "multi"), resultado.map { it.voice?.id })
    }

    @Test fun generoDesconhecidoAceitaQualquerVozEGeneroConfirmadoNaoUsaVozSemGenero() {
        val candidatas = listOf(voz("semGenero", false, generoConhecido = false))
        val desconhecido = SpeakerVoiceSuggester.suggest(
            listOf(personagem("alex")), emptyMap(), candidatas, null, "pt-BR"
        ) { true }
        assertEquals("semGenero", desconhecido.single().voice?.id)

        val feminino = SpeakerVoiceSuggester.suggest(
            listOf(personagem("ana")), mapOf("character:ana" to SpeakerGender.FEMALE),
            candidatas, null, "pt-BR"
        ) { true }
        assertNull(feminino.single().voice)
    }

    @Test fun reaproveitaVozSoQuandoAcabamAsDistintasDoMesmoGenero() {
        val candidatas = listOf(voz("f1", false))
        val genero = mapOf("character:ana" to SpeakerGender.FEMALE, "character:bia" to SpeakerGender.FEMALE)
        val resultado = SpeakerVoiceSuggester.suggest(
            listOf(personagem("ana"), personagem("bia")), genero, candidatas, null, "pt-BR"
        ) { true }
        assertEquals(listOf("f1", "f1"), resultado.map { it.voice?.id })
        assertNotEquals(null, resultado.first().voice)
    }

    @Test fun preferePrimeiroOMotorDoNarradorMasUsaOutrosMotoresSeFaltarem() {
        val candidatas = listOf(
            voz("edge-f", false, motor = "edge"), voz("kokoro-f", false, motor = "kokoro"),
            voz("edge-f2", false, motor = "edge")
        )
        val genero = mapOf("character:ana" to SpeakerGender.FEMALE, "character:bia" to SpeakerGender.FEMALE)
        val resultado = SpeakerVoiceSuggester.suggest(
            listOf(personagem("ana"), personagem("bia")), genero, candidatas,
            narratorVoiceId = null, narratorLanguage = "pt-BR", narratorEngine = "kokoro"
        ) { true }
        assertEquals(listOf("kokoro-f", "edge-f"), resultado.map { it.voice?.id })
    }

    @Test fun semNenhumaVozElegivelDevolveSugestaoSemVoz() {
        val resultado = SpeakerVoiceSuggester.suggest(
            listOf(personagem("ana")), emptyMap(), emptyList(), null, "pt-BR"
        ) { true }
        assertNull(resultado.single().voice)
    }
}
