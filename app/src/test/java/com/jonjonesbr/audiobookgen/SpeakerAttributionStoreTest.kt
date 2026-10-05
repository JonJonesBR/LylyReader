package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.OfflineSpeakerAttributor
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.SpeakerAttributionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
}
