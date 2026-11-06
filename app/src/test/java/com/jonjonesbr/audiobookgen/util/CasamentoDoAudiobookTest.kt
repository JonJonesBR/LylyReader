package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CasamentoDoAudiobookTest {

    private val pastaInterna = "/data/user/0/app/files"
    private val caminhoInterno = "$pastaInterna/capitulo.mp3"

    /** Cartão da Biblioteca: uri do MediaStore com o _id dado, e o nome do arquivo. */
    private fun cartao(id: Long, nome: String) = IdentidadeDoAudiobook(
        uri = "content://media/external/audio/media/$id",
        caminho = null,
        caminhoNoDisco = "/storage/emulated/0/Download/$nome",
        nomeArquivo = nome
    )

    /** Conversão antiga: o player toca o interno, sem registro da cópia. */
    private val playerAntigo = ChaveDoPlayer(caminhoInterno, copiaExportada = null, pastaInterna = pastaInterna)

    @Test fun uriDoCartaoCasaDireto() {
        val player = ChaveDoPlayer("content://media/external/audio/media/7")
        assertEquals(Casamento.DIRETO, casamentoDoAudiobook(player, cartao(7, "outro.mp3")))
    }

    @Test fun nomeIgualNaConversaoAntigaCasaPorNome() {
        assertEquals(Casamento.POR_NOME, casamentoDoAudiobook(playerAntigo, cartao(7, "capitulo.m4a")))
    }

    @Test fun umUnicoCartaoComOMesmoNomeEMarcado() {
        val lista = listOf(cartao(7, "capitulo.m4a"), cartao(8, "outro.mp3"))
        assertEquals(setOf(0), indicesDoAudiobookNoPlayer(playerAntigo, lista))
    }

    @Test fun doisCartoesComOMesmoNomeNaoMarcaNenhum() {
        // Dois livros com "capitulo.mp3": o casamento pelo nome apontaria o livro errado, então nenhum é marcado.
        val lista = listOf(cartao(7, "capitulo.mp3"), cartao(8, "capitulo.mp3"), cartao(9, "outro.mp3"))
        assertTrue(indicesDoAudiobookNoPlayer(playerAntigo, lista).isEmpty())
    }

    @Test fun casamentoDiretoValeMesmoComOutroCartaoDeMesmoNome() {
        val player = ChaveDoPlayer("content://media/external/audio/media/8")
        val lista = listOf(cartao(7, "capitulo.mp3"), cartao(8, "capitulo.mp3"))
        assertEquals(setOf(1), indicesDoAudiobookNoPlayer(player, lista))
    }
}
