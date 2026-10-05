package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.VoiceAssignedSegment
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerAttributorTest {
    @Test
    fun `atribui fala entre aspas ao personagem nomeado e mantem narracao`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("“Eu volto amanhã”, disse Marina. Ela fechou a porta.")
        )

        assertEquals(listOf("Marina"), result.speakers.map { it.name })
        assertEquals(
            listOf(
                "character:marina" to "“Eu volto amanhã”",
                "narrator" to ", disse Marina. Ela fechou a porta."
            ),
            result.paragraphs.single().segments.map { it.speakerId to it.text }
        )
    }

    @Test
    fun `atribui dialogo com travessao quando o verbo identifica o personagem`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("— Não vá! — gritou Pedro."))

        assertEquals(listOf("Pedro"), result.speakers.map { it.name })
        assertEquals(
            listOf("character:pedro" to "Não vá!", "narrator" to "gritou Pedro."),
            result.paragraphs.single().segments.map { it.speakerId to it.text }
        )
    }

    @Test
    fun `fala sem atribuicao explicita permanece com narrador`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("“Volto amanhã”, respondeu ele."))

        assertEquals(0, result.speakers.size)
        assertEquals(listOf("narrator"), result.paragraphs.single().segments.map { it.speakerId })
    }

    @Test
    fun `limita a quinze personagens e usa narrador para os demais`() {
        val names = listOf(
            "Ana", "Bia", "Caio", "Dora", "Enzo", "Fátima", "Guto", "Hugo",
            "Iara", "Jonas", "Kátia", "Lúcio", "Marta", "Nuno", "Olga", "Paulo"
        )
        val result = OfflineSpeakerAttributor.analyze(
            names.map { name -> "“Olá”, disse $name." }
        )

        assertEquals(15, result.speakers.size)
        assertEquals(
            15,
            result.paragraphs.count { it.segments.first().speakerId.startsWith("character:") }
        )
        assertEquals(1, result.paragraphs.count { it.segments.first().speakerId == "narrator" })
    }

    @Test
    fun `plano de sintese usa voz selecionada e cai no narrador quando nao foi mapeada`() {
        val analyzed = OfflineSpeakerAttributor.analyze(listOf("“Volto”, disse Marina."))
        val mapped = analyzed.copy(
            speakers = analyzed.speakers.map { it.copy(voiceId = "kokoro-pf-dora") }
        )

        assertEquals(
            listOf(
                VoiceAssignedSegment("“Volto”", "kokoro-pf-dora"),
                VoiceAssignedSegment(", disse Marina.", "voice-narrator")
            ),
            mapped.voicePlan(0, "voice-narrator")
        )
    }

    private fun falantes(result: com.jonjonesbr.audiobookgen.domain.SpeakerAttributionResult) =
        result.paragraphs.map { paragraph -> paragraph.segments.map { it.speakerId } }

    @Test
    fun `resolve pronome ela e ele pelo genero do ultimo personagem`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf(
                "“Vamos”, disse Marina.",
                "“Não quero”, respondeu Pedro.",
                "“Então tudo bem”, disse ela.",
                "“Melhor assim”, disse ele."
            )
        )
        assertEquals("character:marina", falantes(result)[2].first())
        assertEquals("character:pedro", falantes(result)[3].first())
    }

    @Test
    fun `pronome sem personagem do mesmo genero fica com o narrador`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("“Vamos”, disse Pedro.", "“Sim”, respondeu ela.")
        )
        assertEquals("narrator", falantes(result)[1].first())
    }

    @Test
    fun `dialogo de dois alterna as falas sem atribuicao`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("— Oi — disse Ana.", "— Olá — respondeu Beto.", "— Tudo bem?", "— Tudo.", "— Que bom.")
        )
        assertEquals(
            listOf("character:ana", "character:beto", "character:ana", "character:beto", "character:ana"),
            result.paragraphs.map { it.segments.first().speakerId }
        )
    }

    @Test
    fun `alternancia nao atravessa paragrafos de narracao longos`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf(
                "— Oi — disse Ana.", "— Olá — respondeu Beto.",
                "A noite caiu.", "O vento soprava.", "As luzes se apagaram.", "Tudo ficou quieto.",
                "— E agora?"
            )
        )
        assertEquals("narrator", falantes(result).last().first())
    }

    @Test
    fun `segunda fala do mesmo paragrafo continua com o mesmo personagem`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("“Não”, disse Ana, “eu não vou.”"))
        assertEquals(
            listOf("character:ana", "narrator", "character:ana"),
            result.paragraphs.single().segments.map { it.speakerId }
        )
    }

    @Test
    fun `sujeito da frase de narracao vizinha identifica quem fala`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("“Vamos”, disse Marina.", "“Certo”, disse Pedro.", "Marina olhou para ele. “Preciso ir.”")
        )
        assertEquals("character:marina", result.paragraphs[2].segments.first { it.text.contains("Preciso") }.speakerId)
    }

    @Test
    fun `papel como o velho vira personagem`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("“Cuidado”, disse o velho."))
        assertEquals(listOf("O velho"), result.speakers.map { it.name })
    }

    @Test
    fun `palavra de abertura de frase nao entra no nome`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("Então Maria disse: “Vamos”"))
        assertEquals(listOf("Maria"), result.speakers.map { it.name })
    }

    @Test
    fun `pronome no inicio da frase nao vira personagem`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("Ele disse: “Vamos”", "Ela respondeu: “Sim”"))
        assertEquals(0, result.speakers.size)
    }

    @Test
    fun `reconhece verbos de fala em ingles e espanhol`() {
        val ingles = OfflineSpeakerAttributor.analyze(listOf("\"Let's go,\" said Mary."))
        assertEquals(listOf("Mary"), ingles.speakers.map { it.name })
        val espanhol = OfflineSpeakerAttributor.analyze(listOf("—Vámonos —dijo Pedro."))
        assertEquals(listOf("Pedro"), espanhol.speakers.map { it.name })
    }

    @Test
    fun `titulo e nome completo contam como o mesmo personagem`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("“Sim”, disse Dona Maria.", "“Não”, disse Maria.", "“Talvez”, disse Maria Clara.")
        )
        assertEquals(1, result.speakers.size)
        assertEquals(3, result.speakers.single().utteranceCount)
    }

    @Test
    fun `reconhece barra horizontal, meia-risca e hifen como marcador de dialogo`() {
        val barra = OfflineSpeakerAttributor.analyze(listOf("― Isso mesmo ― disse Cob."))
        assertEquals(listOf("Cob"), barra.speakers.map { it.name })
        val meiaRisca = OfflineSpeakerAttributor.analyze(listOf("– Vou embora – disse Ana."))
        assertEquals(listOf("Ana"), meiaRisca.speakers.map { it.name })
        val hifen = OfflineSpeakerAttributor.analyze(listOf("- Vou embora - disse Beto."))
        assertEquals(listOf("Beto"), hifen.speakers.map { it.name })
    }

    @Test
    fun `hifen dentro de palavra nao e marcador de dialogo`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("- Peguei o guarda-chuva - disse Ana."))
        assertEquals("Peguei o guarda-chuva", result.paragraphs.single().segments.first().text)
        assertEquals(listOf("Ana"), result.speakers.map { it.name })
    }

    @Test
    fun `segunda fala no meio da linha depois da atribuicao continua com o mesmo personagem`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("― Isso mesmo ― disse Cob, com ar de aprovacao. ― O Chandriano.")
        )
        assertEquals(
            listOf("character:cob", "narrator", "character:cob"),
            result.paragraphs.single().segments.map { it.speakerId }
        )
    }

    @Test
    fun `reconhece Nome verbo depois da fala e verbos como fez e repetiu`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf(
                "– Diga logo – Harry disse.",
                "― Anpauen, Reshi ― fez Bast.",
                "- Almoço? - repetiu Kelaritan."
            )
        )
        assertEquals(setOf("Harry", "Bast", "Kelaritan"), result.speakers.map { it.name }.toSet())
    }

    @Test
    fun `nome fora do elenco nao vira outro personagem por alternancia`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("- A - disse Ana.", "- B - disse Beto.", "- C - disse Ana.", "- D - Caio disse."),
            maxCharacterSpeakers = 2
        )
        assertEquals("narrator", result.paragraphs[3].segments.first().speakerId)
    }

    @Test
    fun `paragrafo que termina em Fulano disse anuncia a fala seguinte`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf(
                "- Sim - disse Sheerin.",
                "- Talvez - disse Beenay.",
                "Voltando-se para Athor, Sheerin disse:",
                "- O senhor permite uma pequena experiencia?"
            )
        )
        assertEquals("character:sheerin", result.paragraphs[3].segments.first().speakerId)
    }

    @Test
    fun `fala em primeira pessoa e do narrador e entra na alternancia`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("- A - disse Ana.", "- B - respondi.", "- C?", "- D.", "- E - comentei.")
        )
        assertEquals(listOf("Ana"), result.speakers.map { it.name })
        assertEquals(
            listOf("character:ana", "narrator", "character:ana", "narrator", "narrator"),
            result.paragraphs.map { it.segments.first().speakerId }
        )
    }

    @Test
    fun `disse eu tambem e primeira pessoa`() {
        val result = OfflineSpeakerAttributor.analyze(listOf("- Vamos - disse Ana.", "- Vou - disse eu."))
        assertEquals(listOf("character:ana", "narrator"), result.paragraphs.map { it.segments.first().speakerId })
    }

    @Test
    fun `eu antes do verbo e sujeito comum fora do elenco ficam com o narrador`() {
        val result = OfflineSpeakerAttributor.analyze(
            listOf("- A - disse Ana.", "- B - eu falei.", "- C - a colonial disse.", "- D?")
        )
        assertEquals(listOf("Ana"), result.speakers.map { it.name })
        assertEquals(
            listOf("character:ana", "narrator", "narrator", "character:ana"),
            result.paragraphs.map { it.segments.first().speakerId }
        )
    }
}
