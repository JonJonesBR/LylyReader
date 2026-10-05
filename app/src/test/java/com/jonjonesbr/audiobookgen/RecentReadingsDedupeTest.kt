package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.nomeNormalizadoParaDedupe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecentReadingsDedupeTest {

    @Test fun ignoraDiferencaDeMaiusculasEMinusculas() {
        assertEquals(nomeNormalizadoParaDedupe("Duna"), nomeNormalizadoParaDedupe("duna"))
        assertEquals(nomeNormalizadoParaDedupe("DUNA"), nomeNormalizadoParaDedupe("Duna"))
    }

    @Test fun ignoraEspacosNasPontas() {
        assertEquals(nomeNormalizadoParaDedupe("Duna"), nomeNormalizadoParaDedupe("  Duna  "))
    }

    @Test fun ignoraSufixoDeCopiaDuplicada() {
        // Padrão comum de downloads/importações repetidas do mesmo arquivo: "Duna (1)", "Duna (2)".
        assertEquals(nomeNormalizadoParaDedupe("Duna"), nomeNormalizadoParaDedupe("Duna (1)"))
        assertEquals(nomeNormalizadoParaDedupe("Duna"), nomeNormalizadoParaDedupe("Duna (2)"))
    }

    @Test fun combinaTodasAsVariacoesNoMesmoNome() {
        assertEquals(nomeNormalizadoParaDedupe("Duna"), nomeNormalizadoParaDedupe("  duna (3)  "))
    }

    @Test fun livrosRealmenteDiferentesContinuamDiferentes() {
        assertNotEquals(nomeNormalizadoParaDedupe("Duna"), nomeNormalizadoParaDedupe("Duna Messias"))
    }
}
