package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class SpeakerAttributionStoreTest {
    @Test
    fun `persiste segmentos e vozes e invalida o mapa quando o livro muda`() {
        val root = createTempDirectory("speaker-map-test").toFile()
        try {
            val store = SpeakerAttributionStore(root)
            val paragraphs = listOf("“Eu volto amanhã”, disse Marina.")
            val analyzed = OfflineSpeakerAttributor.analyze(paragraphs)
            val mapped = analyzed.copy(
                speakers = analyzed.speakers.map { it.copy(voiceId = "pt-BR-AntonioNeural") }
            )

            assertEquals(
                true,
                store.save("livro.epub", paragraphs, mapped, listOf(Capitulo("Capítulo 1", 0, 0)))
            )
            assertEquals(mapped, store.load("livro.epub", paragraphs))
            val sidecar = store.fileForBook("livro.epub")!!
            assertEquals("Capítulo 1", org.json.JSONObject(sidecar.readText())
                .getJSONArray("chapters").getJSONObject(0).getString("title"))
            assertNull(store.load("livro.epub", listOf("Texto revisado.")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `informa se o mapa tem voz atribuida sem carregar o livro`() {
        val root = createTempDirectory("speaker-map-voz-test").toFile()
        try {
            val store = SpeakerAttributionStore(root)
            val paragraphs = listOf("“Eu volto amanhã”, disse Marina.")
            val analyzed = OfflineSpeakerAttributor.analyze(paragraphs)
            val semVoz = analyzed.copy(speakers = analyzed.speakers.map { it.copy(voiceId = null) })
            val comVoz = analyzed.copy(speakers = analyzed.speakers.map { it.copy(voiceId = "pt-BR-AntonioNeural") })

            assertEquals(false, store.temVozesAtribuidas("livro.epub"))
            store.save("livro.epub", paragraphs, semVoz)
            assertEquals(false, store.temVozesAtribuidas("livro.epub"))
            store.save("livro.epub", paragraphs, comVoz)
            assertEquals(true, store.temVozesAtribuidas("livro.epub"))
            assertEquals(false, store.temVozesAtribuidas(""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `texto do mapa junta os trechos de origem na ordem do indice`() {
        comPasta("speaker-texto-ordem") { root ->
            gravarJsonDoMapa(root, mapaJson(UM_DOIS))

            assertEquals("Um.\n\nDois.", SpeakerAttributionStore(root).textoDoMapa(LIVRO_MAPA))
        }
    }

    @Test
    fun `texto do mapa e nulo se algum paragrafo nao guardou o texto de origem`() {
        comPasta("speaker-texto-sem-origem") { root ->
            gravarJsonDoMapa(root, mapaJson(UM_DOIS + "," + """{"index":2,"segments":[]}"""))

            assertNull(SpeakerAttributionStore(root).textoDoMapa(LIVRO_MAPA))
        }
    }

    @Test
    fun `texto do mapa e nulo com versao de formato diferente`() {
        comPasta("speaker-texto-formato") { root ->
            gravarJsonDoMapa(root, mapaJson(UM_DOIS, versao = 1))

            assertNull(SpeakerAttributionStore(root).textoDoMapa(LIVRO_MAPA))
        }
    }

    @Test
    fun `texto do mapa e nulo com versao de analise diferente`() {
        comPasta("speaker-texto-analise") { root ->
            gravarJsonDoMapa(root, mapaJson(UM_DOIS, analise = OfflineSpeakerAttributor.ANALYSIS_VERSION - 1))

            assertNull(SpeakerAttributionStore(root).textoDoMapa(LIVRO_MAPA))
        }
    }

    @Test
    fun `texto do mapa e nulo sem mapa salvo`() {
        comPasta("speaker-texto-sem-mapa") { root ->
            assertNull(SpeakerAttributionStore(root).textoDoMapa(LIVRO_MAPA))
        }
    }

    @Test
    fun `texto do mapa e nulo com json invalido`() {
        comPasta("speaker-texto-json") { root ->
            gravarJsonDoMapa(root, "{ruim")

            assertNull(SpeakerAttributionStore(root).textoDoMapa(LIVRO_MAPA))
        }
    }

    @Test
    fun `informa se o mapa salvo tem capitulos`() {
        comPasta("speaker-capitulos-sim") { root ->
            gravarJsonDoMapa(root, mapaJson(UM_DOIS, capitulos = """[{"title":"Cap 1","start_paragraph":0,"end_paragraph":1}]"""))

            assertEquals(true, SpeakerAttributionStore(root).temCapitulos(LIVRO_MAPA))
        }
    }

    @Test
    fun `informa que o mapa sem capitulos ou inexistente nao tem capitulos`() {
        comPasta("speaker-capitulos-nao") { root ->
            assertEquals(false, SpeakerAttributionStore(root).temCapitulos(LIVRO_MAPA))
            gravarJsonDoMapa(root, mapaJson(UM_DOIS))
            assertEquals(false, SpeakerAttributionStore(root).temCapitulos(LIVRO_MAPA))
        }
    }

    private val LIVRO_MAPA = "livro-mapa.epub"

    private val UM_DOIS = """{"index":1,"segments":[],"source_text":"Dois."},""" +
        """{"index":0,"segments":[],"source_text":"Um."}"""

    private fun comPasta(nome: String, bloco: (File) -> Unit) {
        val root = createTempDirectory(nome).toFile()
        try {
            bloco(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(valor: String): String = MessageDigest.getInstance("SHA-256")
        .digest(valor.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun gravarJsonDoMapa(root: File, json: String) {
        File(root, "${sha256(LIVRO_MAPA)}.json").writeText(json, Charsets.UTF_8)
    }

    /** Mapa no formato gravado pela store, com paragrafos já escritos como JSON (controla versões e campos). */
    private fun mapaJson(
        paragrafos: String,
        versao: Int = 2,
        analise: Int = OfflineSpeakerAttributor.ANALYSIS_VERSION,
        capitulos: String = "[]"
    ): String = """{"format_version":$versao,"analysis_version":$analise,"content_fingerprint":"teste",""" +
        """"speakers":[{"id":"n","name":"Narrador","utterance_count":2,"voice_id":"pt-BR-AntonioNeural"}],""" +
        """"paragraphs":[$paragrafos],"chapters":$capitulos}"""
}
