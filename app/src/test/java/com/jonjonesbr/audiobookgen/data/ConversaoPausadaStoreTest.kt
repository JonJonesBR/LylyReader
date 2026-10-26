package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.util.Log
import androidx.work.Data
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConversaoPausadaStoreTest {

    private lateinit var dir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        dir = Files.createTempDirectory("pausada").toFile()
        context = mockk { every { filesDir } returns dir }
    }

    @After
    fun tearDown() {
        unmockkAll()
        dir.deleteRecursively()
    }

    @Test
    fun salvaECarregaAPausaComOsMesmosDados() {
        // "arquivo" e "fila_item_id" são as chaves do ConversionWorker; literais para não carregar a classe no teste JVM.
        val data = Data.Builder().putString("arquivo", "/tmp/livro.epub").putInt("pausa_ms", 300).build()

        assertTrue(ConversaoPausadaStore.salvar(context, data, "Livro", 42, job = "abc"))

        val pausada = ConversaoPausadaStore.carregar(context)!!
        assertEquals("Livro", pausada.titulo)
        assertEquals(42, pausada.pct)
        assertEquals("abc", pausada.job)
        assertEquals("/tmp/livro.epub", pausada.data.getString("arquivo"))
        assertEquals(300, pausada.data.getInt("pausa_ms", 0))
        assertTrue(ConversaoPausadaStore.existe(context))
    }

    @Test
    fun arquivoCorrompidoNaoContaComoPausaEEApagado() {
        val arquivo = File(dir, "conversao_pausada.json")
        arquivo.writeText("{\"titulo\": \"Livro\", \"pct\"")

        assertFalse(ConversaoPausadaStore.existe(context))
        assertFalse(arquivo.exists())
        assertNull(ConversaoPausadaStore.carregar(context))
    }

    @Test
    fun semArquivoNaoHaPausa() {
        assertFalse(ConversaoPausadaStore.existe(context))
        assertNull(ConversaoPausadaStore.carregar(context))
    }

    @Test
    fun limparRemoveAPausa() {
        ConversaoPausadaStore.salvar(context, Data.Builder().build(), "Livro", 1)
        ConversaoPausadaStore.limpar(context)
        assertFalse(ConversaoPausadaStore.existe(context))
    }
}
