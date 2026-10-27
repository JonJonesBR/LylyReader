package com.jonjonesbr.audiobookgen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRepositoryTest {

    /** DAO em memória: o `replaceAll` padrão da interface (clear + insertAll) roda de verdade. */
    private class DaoEmMemoria(var linhas: List<QueueItemEntity> = emptyList()) : QueueItemDao {
        override suspend fun getAll(): List<QueueItemEntity> = linhas
        override suspend fun count(): Int = linhas.size
        override suspend fun clear() {
            linhas = emptyList()
        }
        override suspend fun insertAll(items: List<QueueItemEntity>) {
            linhas = linhas + items
        }
    }

    private fun entidade(id: String, status: String = QueueStatus.PENDENTE.name) = QueueItemEntity(
        id = id,
        nome = "$id.epub",
        caminhoLocal = "/tmp/$id.epub",
        status = status,
        erro = null,
        motor = "edge",
        progressoPct = 0,
        criadoEmMillis = 1,
        iniciadoEmMillis = null,
        finalizadoEmMillis = null,
        caminhoSaida = null,
    )

    private fun prefsComFilaAntiga(json: String): PrefsEmMemoria =
        PrefsEmMemoria().apply {
            edit().putString(com.jonjonesbr.audiobookgen.service.QueueManager.PREF_KEY, json).commit()
        }

    @Test
    fun recargaDoBancoPreservaAOrdemDaFila() = kotlinx.coroutines.runBlocking {
        val dao = DaoEmMemoria(listOf(entidade("b"), entidade("a"), entidade("c")))
        val repo = QueueRepository(dao)

        val itens = repo.loadItems(PrefsEmMemoria())

        assertEquals(listOf("b", "a", "c"), itens.map { it.id })
    }

    @Test
    fun processandoQueFicouNoBancoViraErroInterrompidoEGravaDeVolta() = kotlinx.coroutines.runBlocking {
        // O app fechou no meio de uma conversão: na recarga o item precisa poder ser tentado de novo.
        val dao = DaoEmMemoria(listOf(entidade("a", QueueStatus.PROCESSANDO.name)))
        val repo = QueueRepository(dao)

        val item = repo.loadItems(PrefsEmMemoria()).single()

        assertEquals(QueueStatus.ERRO, item.status)
        assertEquals(ERRO_FILA_INTERROMPIDA, item.erro)
        assertEquals(QueueStatus.ERRO.name, dao.linhas.single().status)
    }

    @Test
    fun bancoComItensIgnoraAFilaAntigaDasPreferencias() = kotlinx.coroutines.runBlocking {
        val dao = DaoEmMemoria(listOf(entidade("novo")))
        val repo = QueueRepository(dao)
        val antigo = prefsComFilaAntiga(
            """[{"id":"antigo","nome":"antigo.epub","caminhoLocal":"/tmp/antigo.epub","status":"PENDENTE"}]"""
        )

        val itens = repo.loadItems(antigo)

        assertEquals(listOf("novo"), itens.map { it.id })
    }

    @Test
    fun bancoVazioMigraAFilaAntigaDasPreferenciasParaORoom() = kotlinx.coroutines.runBlocking {
        val dao = DaoEmMemoria()
        val repo = QueueRepository(dao)
        val antigo = prefsComFilaAntiga(
            """[
                {"id":"um","nome":"um.epub","caminhoLocal":"/tmp/um.epub","status":"CONCLUIDO","progressoPct":100},
                {"id":"dois","nome":"dois.epub","caminhoLocal":"/tmp/dois.epub","status":"PENDENTE"}
            ]"""
        )

        val itens = repo.loadItems(antigo)

        assertEquals(listOf("um", "dois"), itens.map { it.id })
        assertEquals(listOf("um", "dois"), dao.linhas.map { it.id })
        assertEquals(QueueStatus.CONCLUIDO, itens.first().status)
    }

    @Test
    fun semBancoNemFilaAntigaDevolveListaVazia() = kotlinx.coroutines.runBlocking {
        val repo = QueueRepository(DaoEmMemoria())

        val itens = repo.loadItems(PrefsEmMemoria())

        assertTrue(itens.isEmpty())
    }
}
