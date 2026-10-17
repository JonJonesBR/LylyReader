package com.jonjonesbr.audiobookgen.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogReidratacaoTest {

    private fun pacote(id: String) = PacoteVozes(
        id = id,
        engine = "teste",
        nomeExibicao = "Pacote de teste",
        tamanhoDownloadMb = 1,
        vozes = listOf(VoiceOption("$id-v1", "Voz de teste", "pt-BR", false, "teste")),
        isPronto = { true }
    )

    @Test(timeout = 4000)
    fun leituraFeitaDuranteAReidratacaoEsperaOPacoteRegistrado() {
        val segurar = CountDownLatch(1)
        VoiceCatalog.reidratarEmSegundoPlano {
            segurar.await(3, TimeUnit.SECONDS)
            VoiceCatalog.registrarPacoteLocal(pacote("teste-reidratacao-a"))
        }
        val lidos = AtomicReference<List<PacoteVozes>>()
        val leitor = Thread { lidos.set(VoiceCatalog.pacotesRegistrados()) }
        try {
            leitor.start()
            Thread.sleep(200)
            assertTrue("a leitura deveria estar esperando a reidratação", leitor.isAlive)
            segurar.countDown()
            leitor.join(3000)
            assertTrue(lidos.get().any { it.id == "teste-reidratacao-a" })
        } finally {
            segurar.countDown()
            VoiceCatalog.removerPacoteLocal("teste-reidratacao-a")
        }
    }

    @Test(timeout = 4000)
    fun leituraDentroDoProprioBlocoNaoTrava() {
        val concluido = CountDownLatch(1)
        val lidos = AtomicReference<List<PacoteVozes>>()
        VoiceCatalog.reidratarEmSegundoPlano {
            VoiceCatalog.registrarPacoteLocal(pacote("teste-reidratacao-b"))
            lidos.set(VoiceCatalog.pacotesRegistrados())
            concluido.countDown()
        }
        try {
            assertTrue("o bloco não terminou (leitura interna travou?)",
                concluido.await(3, TimeUnit.SECONDS))
            assertTrue(lidos.get().any { it.id == "teste-reidratacao-b" })
        } finally {
            VoiceCatalog.removerPacoteLocal("teste-reidratacao-b")
        }
    }

    @Test(timeout = 4000)
    fun excecaoNoBlocoNaoDeixaLeitoresEsperandoParaSempre() {
        VoiceCatalog.reidratarEmSegundoPlano {
            throw IllegalStateException("falha de teste")
        }
        val inicio = System.currentTimeMillis()
        VoiceCatalog.pacotesRegistrados()
        assertTrue(System.currentTimeMillis() - inicio < 3000)
    }
}
