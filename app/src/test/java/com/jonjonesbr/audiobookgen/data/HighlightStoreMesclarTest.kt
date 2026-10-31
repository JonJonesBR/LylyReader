package com.jonjonesbr.audiobookgen.data

import com.jonjonesbr.audiobookgen.data.HighlightStore.Highlight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Destaques repetidos: tocar de novo no mesmo trecho não pode criar um destaque igual a outro. */
class HighlightStoreMesclarTest {

    private fun destaque(
        id: String,
        livro: String = "/livros/a.epub",
        paragrafo: Int = 3,
        inicio: Int = 0,
        fim: Int = 5,
        nota: String = "",
        ts: Long = 1L,
    ) = Highlight(id, livro, paragrafo, inicio, fim, nota, ts)

    @Test
    fun primeiroDestaqueEAcrescentado() {
        val novo = destaque("1")
        assertEquals(listOf(novo), HighlightStore.mesclarDestaque(emptyList(), novo))
    }

    @Test
    fun mesmoTrechoSemNotaNaoDuplicaEMantemALista() {
        val existente = destaque("1", ts = 10L)
        val atuais = listOf(existente)
        assertEquals(atuais, HighlightStore.mesclarDestaque(atuais, destaque("2", ts = 20L)))
    }

    @Test
    fun mesmoTrechoComNotaSubstituiANotaEMantemOId() {
        val existente = destaque("1", nota = "velha", ts = 10L)
        val resultado = HighlightStore.mesclarDestaque(listOf(existente), destaque("2", nota = "nova", ts = 20L))
        assertEquals(listOf(destaque("1", nota = "nova", ts = 20L)), resultado)
    }

    @Test
    fun notaVaziaNaoApagaANotaDoExistente() {
        val existente = destaque("1", nota = "guardada", ts = 10L)
        val resultado = HighlightStore.mesclarDestaque(listOf(existente), destaque("2", nota = "  ", ts = 20L))
        assertEquals(listOf(existente), resultado)
    }

    @Test
    fun intervalosDiferentesMesmoSobrepostosViramDestaquesSeparados() {
        val existente = destaque("1", inicio = 0, fim = 10)
        val resultado = HighlightStore.mesclarDestaque(listOf(existente), destaque("2", inicio = 5, fim = 15))
        assertEquals(2, resultado.size)
        assertEquals("1", resultado[0].id)
        assertEquals("2", resultado[1].id)
    }

    @Test
    fun mesmoIntervaloEmOutroLivroOuParagrafoTambemViraOutroDestaque() {
        val existente = destaque("1")
        val outroLivro = HighlightStore.mesclarDestaque(listOf(existente), destaque("2", livro = "/livros/b.epub"))
        val outroParagrafo = HighlightStore.mesclarDestaque(listOf(existente), destaque("3", paragrafo = 4))
        assertEquals(2, outroLivro.size)
        assertEquals(2, outroParagrafo.size)
    }

    // ── Pelo store (SharedPreferences em memória) ─────────────────────────────

    private lateinit var raiz: File
    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        raiz = Files.createTempDirectory("destaques").toFile()
        ctx = PrefsEmMemoria.contexto(raiz)
    }

    @After
    fun tearDown() {
        raiz.deleteRecursively()
    }

    @Test
    fun adicionarDuasVezesNoMesmoTrechoGuardaUmSoDestaque() {
        val livro = "/livros/a.epub"
        HighlightStore.adicionar(ctx, livro, 3, 0..4)
        HighlightStore.adicionar(ctx, livro, 3, 0..4)
        assertEquals(1, HighlightStore.listarPara(ctx, livro).size)

        HighlightStore.adicionar(ctx, livro, 3, 0..4, "com nota")
        val lista = HighlightStore.listarPara(ctx, livro)
        assertEquals(1, lista.size)
        assertEquals("com nota", lista[0].nota)
        assertTrue(lista[0].id.isNotBlank())
    }
}
