package com.jonjonesbr.audiobookgen

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.EstadoDownload
import com.jonjonesbr.audiobookgen.service.AndamentoDownload
import com.jonjonesbr.audiobookgen.service.GrupoDownload
import com.jonjonesbr.audiobookgen.service.ItemBaixavel
import com.jonjonesbr.audiobookgen.ui.LinhaDownload
import com.jonjonesbr.audiobookgen.util.ProgressoDownload
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
        LinhaDownload(item, andamento, abreGrupo = false, selecionado = false)

    @Test fun vozClonadaSemCodificadorNaoESelecionavelMasSeApaga() {
        val l = linha(item(GrupoDownload.IMPORTADAS), AndamentoDownload(EstadoDownload.NAO_BAIXADO, bloqueio = 1))
        assertTrue(l.bloqueado)
        assertFalse(l.selecionavel)
        assertTrue(l.apagavelSemEstarPronta)
    }

    @Test fun vozClonadaSemPacoteDoIdiomaSeBaixaESeApaga() {
        val l = linha(item(GrupoDownload.IMPORTADAS), AndamentoDownload(EstadoDownload.NAO_BAIXADO))
        assertFalse(l.bloqueado)
        assertTrue(l.selecionavel)
        assertTrue(l.apagavelSemEstarPronta)
    }

    @Test fun pacoteDoCatalogoNaoBaixadoSoSeBaixa() {
        val l = linha(item(GrupoDownload.MOTORES), AndamentoDownload(EstadoDownload.NAO_BAIXADO))
        assertTrue(l.selecionavel)
        assertFalse(l.apagavelSemEstarPronta)
    }

    @Test fun vozImportadaSemDownloadNaoESelecionavel() {
        val l = linha(item(GrupoDownload.IMPORTADAS, baixavel = false), AndamentoDownload(EstadoDownload.NAO_BAIXADO))
        assertFalse(l.selecionavel)
        assertTrue(l.apagavelSemEstarPronta)
    }
}
