package com.jonjonesbr.audiobookgen

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.EstadoDownload
import com.jonjonesbr.audiobookgen.service.AndamentoDownload
import com.jonjonesbr.audiobookgen.service.GrupoDownload
import com.jonjonesbr.audiobookgen.service.ItemBaixavel
import com.jonjonesbr.audiobookgen.ui.LinhaDownload
import com.jonjonesbr.audiobookgen.util.ProgressoDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinhaDownloadTest {

    private val semNada: suspend (Context, ProgressoDownload) -> Unit = { _, _ -> }

    private fun item(grupo: GrupoDownload, baixavel: Boolean = true) = ItemBaixavel(
        id = "x", nome = "X", grupo = grupo, tamanhoMb = 0,
        isPronto = { false }, ocupadoBytes = { 0L },
        download = semNada.takeIf { baixavel },
        apagar = { }
    )

    private fun linha(item: ItemBaixavel, andamento: AndamentoDownload) =
        LinhaDownload(item, andamento)

    @Test fun vozClonadaSemCodificadorNaoEBaixavelMasSeApaga() {
        val l = linha(item(GrupoDownload.IMPORTADAS), AndamentoDownload(EstadoDownload.NAO_BAIXADO, bloqueio = 1))
        assertTrue(l.bloqueado)
        assertFalse(l.baixavel)
        assertTrue(l.apagavelSemEstarPronta)
    }

    @Test fun vozClonadaSemPacoteDoIdiomaSeBaixaESeApaga() {
        val l = linha(item(GrupoDownload.IMPORTADAS), AndamentoDownload(EstadoDownload.NAO_BAIXADO))
        assertFalse(l.bloqueado)
        assertTrue(l.baixavel)
        assertTrue(l.apagavelSemEstarPronta)
    }

    @Test fun pacoteDoCatalogoNaoBaixadoSoSeBaixa() {
        val l = linha(item(GrupoDownload.MOTORES), AndamentoDownload(EstadoDownload.NAO_BAIXADO))
        assertTrue(l.baixavel)
        assertFalse(l.apagavelSemEstarPronta)
    }

    @Test fun vozDoUsuarioFicaEmMinhasVozesEPacoteProntoNoAparelho() {
        assertEquals(
            com.jonjonesbr.audiobookgen.domain.SecaoDownload.MINHAS_VOZES,
            linha(item(GrupoDownload.IMPORTADAS), AndamentoDownload(EstadoDownload.PRONTO)).secao
        )
        assertEquals(
            com.jonjonesbr.audiobookgen.domain.SecaoDownload.NO_APARELHO,
            linha(item(GrupoDownload.MOTORES), AndamentoDownload(EstadoDownload.PRONTO)).secao
        )
    }

    @Test fun listaSempreTemMinhasVozesComCriarVoz() {
        val lista = com.jonjonesbr.audiobookgen.ui.LinhaLista.montar(
            listOf(linha(item(GrupoDownload.MOTORES), AndamentoDownload(EstadoDownload.NAO_BAIXADO))),
            temAtividade = false
        )
        assertTrue(lista.last() == com.jonjonesbr.audiobookgen.ui.LinhaLista.CriarVoz)
        assertEquals(
            com.jonjonesbr.audiobookgen.ui.LinhaLista.Cabecalho(
                com.jonjonesbr.audiobookgen.domain.SecaoDownload.PARA_BAIXAR, comPausarTudo = false
            ),
            lista.first()
        )
    }

    @Test fun vozImportadaSemDownloadNaoEBaixavel() {
        val l = linha(item(GrupoDownload.IMPORTADAS, baixavel = false), AndamentoDownload(EstadoDownload.NAO_BAIXADO))
        assertFalse(l.baixavel)
        assertTrue(l.apagavelSemEstarPronta)
    }
}
