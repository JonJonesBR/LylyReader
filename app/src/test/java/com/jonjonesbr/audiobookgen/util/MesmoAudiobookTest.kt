package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MesmoAudiobookTest {

    private val uriCartao = "content://media/external/audio/media/42"
    private val copiaDownloads = "content://media/external/downloads/42"
    private val pastaInterna = "/data/user/0/app/files"
    private val caminhoInterno = "$pastaInterna/livro.mp3"
    private val caminhoDisco = "/storage/emulated/0/Download/livro.mp3"

    private fun cartao(
        uri: String = uriCartao,
        caminho: String? = null,
        disco: String? = caminhoDisco,
        nome: String? = "livro.mp3"
    ) = IdentidadeDoAudiobook(uri, caminho, disco, nome)

    @Test fun casaPelaUriDoCartao() {
        assertTrue(mesmoAudiobook(ChaveDoPlayer(uriCartao), cartao()))
    }

    @Test fun casaPeloCaminhoNoDiscoQuandoOPlayerRecebeOCaminho() {
        assertTrue(mesmoAudiobook(ChaveDoPlayer(caminhoDisco), cartao()))
    }

    @Test fun casaCaminhoInternoPelaCopiaExportadaNoMediaStore() {
        // Tela do livro e mini-player tocam o interno; a Biblioteca lista a cópia em Downloads (mesmo _id).
        val player = ChaveDoPlayer(caminhoInterno, copiaExportada = copiaDownloads, pastaInterna = pastaInterna)
        assertTrue(mesmoAudiobook(player, cartao()))
    }

    @Test fun naoCasaCopiaDeOutroIdMesmoComONomeIgual() {
        val copia = "content://media/external/downloads/43"
        val player = ChaveDoPlayer(caminhoInterno, copiaExportada = copia, pastaInterna = pastaInterna)
        assertFalse(mesmoAudiobook(player, cartao()))
    }

    @Test fun naoCasaIdDeColecaoQueNaoEArquivo() {
        val copia = "content://media/external/audio/albums/42"
        val player = ChaveDoPlayer(caminhoInterno, copiaExportada = copia, pastaInterna = pastaInterna)
        assertFalse(mesmoAudiobook(player, cartao()))
    }

    @Test fun casaCopiaFileComCaminhoComEspaco() {
        // Sem MediaStore (uri file:): a cópia é comparada pelo caminho no disco.
        val player = ChaveDoPlayer(
            caminhoInterno,
            copiaExportada = "file:///storage/emulated/0/Download/meu%20livro.mp3",
            pastaInterna = pastaInterna
        )
        val disco = "/storage/emulated/0/Download/meu livro.mp3"
        assertTrue(mesmoAudiobook(player, cartao(disco = disco, nome = "meu livro.mp3")))
    }

    @Test fun casaConversaoAntigaPeloNomeSemExtensao() {
        // Sem registro da cópia (conversão antiga): o interno e o cartão têm o mesmo nome, com .mp3 e .m4a.
        val player = ChaveDoPlayer(caminhoInterno, copiaExportada = null, pastaInterna = pastaInterna)
        assertTrue(mesmoAudiobook(player, cartao(nome = "livro.m4a")))
    }

    @Test fun naoCasaConversaoAntigaDeOutroNome() {
        val player = ChaveDoPlayer(caminhoInterno, copiaExportada = null, pastaInterna = pastaInterna)
        assertFalse(mesmoAudiobook(player, cartao(nome = "outro.mp3")))
    }

    @Test fun naoCasaPeloNomeQuandoOArquivoNaoEstaNaPastaInterna() {
        val outraPasta = "/storage/emulated/0/Music/livro.mp3"
        val player = ChaveDoPlayer(outraPasta, copiaExportada = null, pastaInterna = pastaInterna)
        assertFalse(mesmoAudiobook(player, cartao()))
    }

    @Test fun naoCasaOutroArquivoDaMesmaPasta() {
        assertFalse(mesmoAudiobook(ChaveDoPlayer("/storage/emulated/0/Download/outro.mp3"), cartao()))
    }

    @Test fun chaveVaziaOuNulaNaoCasa() {
        assertFalse(mesmoAudiobook(ChaveDoPlayer(null), cartao()))
        assertFalse(mesmoAudiobook(ChaveDoPlayer(""), cartao()))
    }
}
