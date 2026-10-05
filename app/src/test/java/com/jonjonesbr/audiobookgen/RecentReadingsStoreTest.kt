package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.RecentReadingsStore
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentReadingsStoreTest {

    @Test fun removePrefixoEntradaEExtensao() {
        assertEquals("MeuLivro", RecentReadingsStore.nomeLimpo("/cache/entrada_1719000000_MeuLivro.epub"))
    }

    @Test fun preservaEspacosNoNome() {
        assertEquals("Dom Casmurro", RecentReadingsStore.nomeLimpo("/cache/entrada_42_Dom Casmurro.pdf"))
    }

    @Test fun semPrefixoSoRemoveExtensao() {
        assertEquals("plain", RecentReadingsStore.nomeLimpo("/cache/plain.txt"))
    }

    @Test fun semExtensaoMantemNome() {
        assertEquals("README", RecentReadingsStore.nomeLimpo("/cache/entrada_7_README"))
    }
}
