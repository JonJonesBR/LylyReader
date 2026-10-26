package com.jonjonesbr.audiobookgen.data

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Posição, marcadores e destaques do usuário: um erro em migrar/remover é perda de dados. */
class DadosDoLivroTest {

    private lateinit var raiz: File
    private lateinit var ctx: Context
    private lateinit var antigo: String
    private lateinit var novo: String

    @Before
    fun setUp() {
        raiz = Files.createTempDirectory("dados_livro").toFile()
        ctx = PrefsEmMemoria.contexto(raiz)
        antigo = File(ctx.cacheDir, "entrada_1_livro.txt").apply { writeText("texto") }.absolutePath
        novo = File(ctx.filesDir, "livros/1/livro.txt").apply { parentFile!!.mkdirs(); writeText("texto") }.absolutePath
    }

    @After
    fun tearDown() {
        raiz.deleteRecursively()
    }

    private fun prefs() = ctx.getSharedPreferences(AppPrefs.NAME, Context.MODE_PRIVATE)

    @Test
    fun migrarLevaMarcadoresDestaquesRecentesEPosicaoParaOCaminhoNovo() {
        BookmarkStore.adicionar(ctx, antigo, "Cap. 1", 7)
        HighlightStore.adicionar(ctx, antigo, 3, 0..4, "nota")
        RecentReadingsStore.record(ctx, antigo, "Livro")
        prefs().edit().putInt("reader_scroll_$antigo", 40).apply()

        DadosDoLivro.migrar(ctx, antigo, novo)

        assertEquals(listOf(7), BookmarkStore.listarPara(ctx, novo).map { it.indiceParagrafo })
        assertTrue(BookmarkStore.listarPara(ctx, antigo).isEmpty())
        assertEquals(listOf("nota"), HighlightStore.listarPara(ctx, novo).map { it.nota })
        assertTrue(HighlightStore.listarPara(ctx, antigo).isEmpty())
        assertEquals(listOf(novo), RecentReadingsStore.load(ctx).map { it.caminho })
        assertEquals(40, prefs().getInt("reader_scroll_$novo", -1))
    }

    @Test
    fun migrarNaoSobrescreveAPosicaoMaisAvancadaDoCaminhoNovo() {
        prefs().edit().putInt("reader_scroll_$antigo", 10).putInt("reader_scroll_$novo", 25).apply()

        DadosDoLivro.migrar(ctx, antigo, novo)

        assertEquals(25, prefs().getInt("reader_scroll_$novo", -1))
    }

    @Test
    fun migrarMantemOsMarcadoresQueOCaminhoNovoJaTinha() {
        BookmarkStore.adicionar(ctx, novo, "Já no novo", 2)
        BookmarkStore.adicionar(ctx, antigo, "Do antigo", 9)

        DadosDoLivro.migrar(ctx, antigo, novo)

        assertEquals(listOf(2, 9), BookmarkStore.listarPara(ctx, novo).map { it.indiceParagrafo }.sorted())
    }

    @Test
    fun removerApagaSoOsDadosDoLivroRemovido() {
        BookmarkStore.adicionar(ctx, antigo, "Cap. 1", 7)
        BookmarkStore.adicionar(ctx, novo, "Outro livro", 1)
        HighlightStore.adicionar(ctx, antigo, 3, 0..4)
        prefs().edit().putInt("reader_scroll_$antigo", 40).putInt("reader_scroll_$novo", 5).apply()

        DadosDoLivro.remover(ctx, antigo)

        assertTrue(BookmarkStore.listarPara(ctx, antigo).isEmpty())
        assertTrue(HighlightStore.listarPara(ctx, antigo).isEmpty())
        assertEquals(-1, prefs().getInt("reader_scroll_$antigo", -1))
        assertEquals(listOf(1), BookmarkStore.listarPara(ctx, novo).map { it.indiceParagrafo })
        assertEquals(5, prefs().getInt("reader_scroll_$novo", -1))
    }
}
