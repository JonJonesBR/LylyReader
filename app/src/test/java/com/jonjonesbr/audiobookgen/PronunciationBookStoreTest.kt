package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.PronunciationBookStore
import com.jonjonesbr.audiobookgen.domain.PronunciationDictionary
import com.jonjonesbr.audiobookgen.domain.PronunciationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class PronunciationBookStoreTest {
    private val LIVRO = "livro-pronuncia.epub"

    private val dicionario = PronunciationDictionary(
        listOf(PronunciationEntry("Hermione", "Hermáione"), PronunciationEntry("Nyx", "Níks"))
    )

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

    @Test
    fun `salva e le o dicionario do livro`() {
        comPasta("pronuncia-livro-salvar") { root ->
            val store = PronunciationBookStore(root)

            assertTrue(store.save(LIVRO, dicionario))
            assertTrue(File(root, "${sha256(LIVRO)}.json").isFile)
            assertEquals(dicionario.entries, store.load(LIVRO).entries)
            assertEquals(dicionario.toJson(), store.rawJson(LIVRO))
        }
    }

    @Test
    fun `dicionario ausente ou identidade vazia viram vazio`() {
        comPasta("pronuncia-livro-ausente") { root ->
            val store = PronunciationBookStore(root)

            assertTrue(store.load(LIVRO).isEmpty())
            assertEquals("[]", store.rawJson(LIVRO))
            assertTrue(store.load("").isEmpty())
            assertFalse(store.save("", dicionario))
        }
    }

    @Test
    fun `json invalido vira dicionario vazio`() {
        comPasta("pronuncia-livro-json") { root ->
            File(root, "${sha256(LIVRO)}.json").writeText("{ruim", Charsets.UTF_8)
            val store = PronunciationBookStore(root)

            assertTrue(store.load(LIVRO).isEmpty())
            assertEquals("[]", store.rawJson(LIVRO))
        }
    }

    @Test
    fun `migra o dicionario para a identidade nova sem sobrescrever`() {
        comPasta("pronuncia-livro-migrar") { root ->
            val store = PronunciationBookStore(root)
            store.save("antigo.epub", dicionario)

            assertTrue(store.migrarIdentidade("antigo.epub", "novo.epub"))
            assertEquals(dicionario.entries, store.load("novo.epub").entries)
            assertEquals(dicionario.entries, store.load("antigo.epub").entries)

            store.save("outro.epub", PronunciationDictionary.EMPTY)
            assertFalse(store.migrarIdentidade("antigo.epub", "outro.epub"))
            assertTrue(store.load("outro.epub").isEmpty())
            assertFalse(store.migrarIdentidade("inexistente.epub", "x.epub"))
            assertFalse(store.migrarIdentidade("antigo.epub", "antigo.epub"))
        }
    }

    @Test
    fun `apaga o dicionario do livro`() {
        comPasta("pronuncia-livro-apagar") { root ->
            val store = PronunciationBookStore(root)
            store.save(LIVRO, dicionario)

            store.apagarIdentidade(LIVRO)

            assertFalse(File(root, "${sha256(LIVRO)}.json").exists())
            assertTrue(store.load(LIVRO).isEmpty())
            store.apagarIdentidade("")
        }
    }

    @Test
    fun `cache relê o dicionario quando o arquivo muda por fora`() {
        comPasta("pronuncia-livro-cache-externo") { root ->
            val store = PronunciationBookStore(root)
            store.save(LIVRO, dicionario)
            assertEquals(dicionario.entries, store.load(LIVRO).entries)

            val arquivo = File(root, "${sha256(LIVRO)}.json")
            val novo = PronunciationDictionary(listOf(PronunciationEntry("Vox", "Vóks")))
            arquivo.writeText(novo.toJson(), Charsets.UTF_8)
            arquivo.setLastModified(arquivo.lastModified() + 2_000L)

            assertEquals(novo.entries, store.load(LIVRO).entries)
        }
    }

    @Test
    fun `salvar de novo troca o que o cache devolve`() {
        comPasta("pronuncia-livro-cache-salvar") { root ->
            val store = PronunciationBookStore(root)
            store.save(LIVRO, dicionario)
            assertEquals(dicionario.entries, store.load(LIVRO).entries)

            val novo = PronunciationDictionary(listOf(PronunciationEntry("Vox", "Vóks")))
            store.save(LIVRO, novo)

            assertEquals(novo.entries, store.load(LIVRO).entries)
        }
    }
}
