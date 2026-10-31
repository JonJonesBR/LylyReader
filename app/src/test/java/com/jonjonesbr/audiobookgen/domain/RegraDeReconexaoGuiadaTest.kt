package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RegraDeReconexaoGuiadaTest {

    private class FakeManager(val index: Int)

    @Test fun semManagerExistenteNaoReconecta() {
        assertNull(RegraDeReconexaoGuiada.managerParaReconectar<FakeManager>(null, { "/livro.epub" }, "/livro.epub") { it.index })
    }

    @Test fun outroDocumentoNaoReconecta() {
        val m = FakeManager(3)
        assertNull(RegraDeReconexaoGuiada.managerParaReconectar(m, { "/outro.epub" }, "/livro.epub") { it.index })
    }

    @Test fun caminhoVazioDaSessaoNaoReconecta() {
        val m = FakeManager(3)
        assertNull(RegraDeReconexaoGuiada.managerParaReconectar(m, { "" }, "/livro.epub") { it.index })
    }

    @Test fun indexNegativoNaoReconecta() {
        val m = FakeManager(-1)
        assertNull(RegraDeReconexaoGuiada.managerParaReconectar(m, { "/livro.epub" }, "/livro.epub") { it.index })
    }

    @Test fun mesmoDocumentoComIndexZeroReconecta() {
        val m = FakeManager(0)
        assertSame(m, RegraDeReconexaoGuiada.managerParaReconectar(m, { "/livro.epub" }, "/livro.epub") { it.index })
    }

    @Test fun mesmoDocumentoComIndexPositivoReconecta() {
        val m = FakeManager(7)
        assertSame(m, RegraDeReconexaoGuiada.managerParaReconectar(m, { "/livro.epub" }, "/livro.epub") { it.index })
    }

    @Test fun indexSoEhLidoQuandoAsDuasPrimeirasCondicoesPassam() {
        var leituras = 0
        val m = FakeManager(2)
        RegraDeReconexaoGuiada.managerParaReconectar<FakeManager>(null, { "/a" }, "/a") { leituras++; it.index }
        RegraDeReconexaoGuiada.managerParaReconectar(m, { "/b" }, "/a") { leituras++; it.index }
        assertEquals(0, leituras)
        RegraDeReconexaoGuiada.managerParaReconectar(m, { "/a" }, "/a") { leituras++; it.index }
        assertEquals(1, leituras)
    }

    @Test fun caminhoDaSessaoSoEhLidoQuandoHaManager() {
        var leituras = 0
        RegraDeReconexaoGuiada.managerParaReconectar<FakeManager>(null, { leituras++; "/a" }, "/a") { it.index }
        assertEquals(0, leituras)
        RegraDeReconexaoGuiada.managerParaReconectar(FakeManager(1), { leituras++; "/a" }, "/a") { it.index }
        assertEquals(1, leituras)
    }

    @Test fun leitorDeLivroQueAFilaDeixouParaTrasFicaParaTras() {
        assertTrue(RegraDeReconexaoGuiada.leitorFicouParaTras("/a", "/b", 3, setOf("/a")))
    }

    @Test fun mesmoLivroNaoFicaParaTras() {
        assertFalse(RegraDeReconexaoGuiada.leitorFicouParaTras("/a", "/a", 3, setOf("/a")))
    }

    @Test fun sessaoSemLeituraNaoEnviaAOutroLivro() {
        assertFalse(RegraDeReconexaoGuiada.leitorFicouParaTras("/a", "/b", -1, setOf("/a")))
    }

    @Test fun livroForaDaFilaNaoFicaParaTras() {
        assertFalse(RegraDeReconexaoGuiada.leitorFicouParaTras("/c", "/b", 3, setOf("/a")))
    }

    @Test fun sessaoSemCaminhoNaoFicaParaTras() {
        assertFalse(RegraDeReconexaoGuiada.leitorFicouParaTras("/a", "", 3, setOf("/a")))
    }
}
