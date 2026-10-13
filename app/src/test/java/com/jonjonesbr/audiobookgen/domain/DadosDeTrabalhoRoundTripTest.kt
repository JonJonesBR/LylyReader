package com.jonjonesbr.audiobookgen.domain

import androidx.work.Data
import org.junit.Assert.assertEquals
import org.junit.Test

class DadosDeTrabalhoRoundTripTest {

    @Test
    fun `Data guardado e lido de volta mantem a entrada e tira as chaves de saida de workers anteriores`() {
        // "arquivo" é ConversionWorker.KEY_ARQUIVO; literal para não carregar a classe do worker no teste JVM.
        val entrada = Data.Builder()
            .putString("arquivo", "/tmp/livro.txt")
            .putInt("pausa_ms", 300)
            .putBoolean("tom", true)
            .putString("result_caminho", "/tmp/antigo.mp3")
            .putInt("progress_pct", 42)
            .build()

        val json = CodificadorDeDadosDeTrabalho.paraJson(CodificadorDeDadosDeTrabalho.semChavesDeSaida(entrada.keyValueMap))
        val volta = CodificadorDeDadosDeTrabalho.deMapa(json)

        assertEquals(mapOf("arquivo" to "/tmp/livro.txt", "pausa_ms" to 300, "tom" to true), volta)
    }
}
