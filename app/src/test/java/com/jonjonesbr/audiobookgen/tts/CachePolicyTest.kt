package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.tts.CachePolicy.Arquivo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    private val hora = 60 * 60 * 1000L
    private val agora = 100 * hora

    private fun arq(nome: String, mb: Long, horasAtras: Long) =
        Arquivo(nome, mb * 1024 * 1024, agora - horasAtras * hora)

    @Test fun abaixoDoTetoNaoRemoveNada() {
        val arquivos = listOf(arq("a", 100, 5), arq("b", 100, 3))
        assertTrue(CachePolicy.selecionarParaRemover(arquivos, CachePolicy.tetoEmBytes(1024), agora).isEmpty())
    }

    @Test fun semLimiteNuncaRemove() {
        val arquivos = listOf(arq("a", 5000, 50))
        assertTrue(CachePolicy.selecionarParaRemover(arquivos, CachePolicy.tetoEmBytes(0), agora).isEmpty())
    }

    @Test fun acimaDoTetoRemoveOsMaisAntigosAteOAlvo() {
        // teto 1000 MB → alvo 800 MB. Total 1200 MB: remove o mais antigo (400) e o seguinte (300) → 500.
        val arquivos = listOf(arq("novo", 300, 2), arq("meio", 300, 10), arq("antigo", 300, 30), arq("velho", 300, 40))
        val removidos = CachePolicy.selecionarParaRemover(arquivos, 1000L * 1024 * 1024, agora).map { it.nome }
        assertEquals(listOf("velho", "antigo"), removidos)
    }

    @Test fun paraDeRemoverAssimQueChegaNoAlvo() {
        val arquivos = listOf(arq("a", 500, 50), arq("b", 500, 40), arq("c", 500, 30))
        // teto 1000 → alvo 800: total 1500; tirar "a" já dá 1000 (>800), tirar "b" dá 500 (<=800).
        val removidos = CachePolicy.selecionarParaRemover(arquivos, 1000L * 1024 * 1024, agora).map { it.nome }
        assertEquals(listOf("a", "b"), removidos)
    }

    @Test fun arquivosUsadosRecentementeSaoProtegidos() {
        val recente = Arquivo("recente", 900L * 1024 * 1024, agora - 60_000L)
        val antigo = arq("antigo", 300, 30)
        val removidos = CachePolicy.selecionarParaRemover(listOf(recente, antigo), 1000L * 1024 * 1024, agora)
        assertEquals(listOf("antigo"), removidos.map { it.nome })
    }

    @Test fun seSoHouverProtegidosNaoRemoveNada() {
        val recentes = listOf(Arquivo("a", 900L * 1024 * 1024, agora - 1_000L), Arquivo("b", 900L * 1024 * 1024, agora - 2_000L))
        assertTrue(CachePolicy.selecionarParaRemover(recentes, 1000L * 1024 * 1024, agora).isEmpty())
    }

    @Test fun tetoEmBytesConverteMbEZeroViraSemLimite() {
        assertEquals(1024L * 1024 * 1024, CachePolicy.tetoEmBytes(1024))
        assertEquals(0L, CachePolicy.tetoEmBytes(0))
        assertEquals(CachePolicy.TETO_PADRAO_MB, 1024)
        assertTrue(CachePolicy.TETOS_MB.contains(CachePolicy.TETO_PADRAO_MB))
    }
}
