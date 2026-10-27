package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA
import com.jonjonesbr.audiobookgen.data.PrefsEmMemoria
import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.service.QueueManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QueueManagerPersistenciaTest {
    @Before
    fun setUp() {
        QueueManager.items.clear()
    }

    @After
    fun tearDown() {
        QueueManager.items.clear()
    }

    @Test
    fun salvarECarregarPelasPreferenciasRecuperaAFilaInteira() {
        val prefs = PrefsEmMemoria()
        val pendente = QueueManager.adicionar("a.epub", "/tmp/a.epub", "edge")
        val concluido = QueueManager.adicionar("b.epub", "/tmp/b.epub", "gemini")
        QueueManager.marcarConcluido(concluido.id, "/tmp/b.mp3")
        QueueManager.salvar(prefs)

        QueueManager.items.clear()
        QueueManager.carregar(prefs)

        assertEquals(listOf(pendente.id, concluido.id), QueueManager.items.map { it.id })
        val restaurado = QueueManager.items[1]
        assertEquals(QueueStatus.CONCLUIDO, restaurado.status)
        assertEquals("/tmp/b.mp3", restaurado.caminhoSaida)
        assertEquals("gemini", restaurado.motor)
    }

    @Test
    fun carregarSemNadaSalvoMantemAFilaAtual() {
        val item = QueueManager.adicionar("a.epub", "/tmp/a.epub")

        QueueManager.carregar(PrefsEmMemoria())

        assertEquals(listOf(item), QueueManager.items)
    }

    @Test
    fun jsonNuloOuEmBrancoDevolveListaVazia() {
        assertTrue(QueueManager.itemsFromJson(null).isEmpty())
        assertTrue(QueueManager.itemsFromJson("").isEmpty())
        assertTrue(QueueManager.itemsFromJson("   ").isEmpty())
    }

    @Test
    fun jsonInvalidoNaoQuebraERetornaListaVazia() {
        assertTrue(QueueManager.itemsFromJson("[{").isEmpty())
        assertTrue(QueueManager.itemsFromJson("{}").isEmpty())
        assertTrue(QueueManager.itemsFromJson("texto qualquer").isEmpty())
    }

    @Test
    fun entradaInvalidaNoMeioDescartaSoElaEMantemAsValidas() {
        val json = """[
            {"id":"a","nome":"um.epub","caminhoLocal":"/tmp/1.epub","status":"PENDENTE"},
            {"nome":"sem-id.epub","caminhoLocal":"/tmp/2.epub","status":"PENDENTE"},
            {"id":"c","nome":"tres.epub","caminhoLocal":"/tmp/3.epub","status":"CONCLUIDO"}
        ]"""

        val itens = QueueManager.itemsFromJson(json)

        assertEquals(listOf("a", "c"), itens.map { it.id })
        assertEquals("um.epub", itens[0].nome)
        assertEquals("tres.epub", itens[1].nome)
        assertEquals(QueueStatus.CONCLUIDO, itens[1].status)
    }

    @Test
    fun jsonCorrompidoNoMeioDaListaDevolveListaVazia() {
        assertTrue(QueueManager.itemsFromJson("""[{"id":"a","nome":"um.epub"""").isEmpty())
    }

    @Test
    fun idRepetidoMantemSoAPrimeiraOcorrencia() {
        val json = """[
            {"id":"x","nome":"primeiro.epub","caminhoLocal":"/tmp/1.epub","status":"PENDENTE"},
            {"id":"x","nome":"segundo.epub","caminhoLocal":"/tmp/2.epub","status":"CONCLUIDO"}
        ]"""

        val itens = QueueManager.itemsFromJson(json)

        assertEquals(1, itens.size)
        assertEquals("primeiro.epub", itens.single().nome)
        assertEquals(QueueStatus.PENDENTE, itens.single().status)
    }

    @Test
    fun statusDesconhecidoViraPendenteECamposAusentesTemValorPadrao() {
        val json = """[{"id":"x","nome":"a.epub","caminhoLocal":"/tmp/a.epub","status":"DESCONHECIDO"}]"""

        val item = QueueManager.itemsFromJson(json).single()

        assertEquals(QueueStatus.PENDENTE, item.status)
        assertEquals(0, item.progressoPct)
        assertNull(item.erro)
        assertNull(item.caminhoSaida)
    }

    @Test
    fun concluidoSemProgressoSalvoRestauraCemPorCento() {
        val json = """[{"id":"x","nome":"a.epub","caminhoLocal":"/tmp/a.epub","status":"CONCLUIDO"}]"""

        val item = QueueManager.itemsFromJson(json).single()

        assertEquals(QueueStatus.CONCLUIDO, item.status)
        assertEquals(100, item.progressoPct)
    }

    @Test
    fun processandoAntigoViraErroInterrompidoComMarcador() {
        val json = """[{"id":"x","nome":"a.epub","caminhoLocal":"/tmp/a.epub","status":"PROCESSANDO","progressoPct":30}]"""

        val item = QueueManager.itemsFromJson(json).single()

        assertEquals(QueueStatus.ERRO, item.status)
        assertEquals(ERRO_FILA_INTERROMPIDA, item.erro)
        assertEquals(30, item.progressoPct)
    }

    @Test
    fun historicoSalvoFicaComOsCinquentaUltimosItens() {
        for (i in 0 until 60) {
            QueueManager.items.add(QueueItem(id = "item-$i", nome = "$i.epub", caminhoLocal = "/tmp/$i.epub"))
        }

        val json = QueueManager.toJson()
        QueueManager.items.clear()
        QueueManager.carregarJson(json)

        assertEquals(50, QueueManager.items.size)
        assertEquals("item-10", QueueManager.items.first().id)
        assertEquals("item-59", QueueManager.items.last().id)
    }
}
