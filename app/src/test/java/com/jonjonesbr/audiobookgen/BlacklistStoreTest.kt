package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.data.BlacklistStore
import org.junit.Assert.assertEquals
import org.junit.Test

class BlacklistStoreTest {

    @Test fun parseVazioOuInvalido() {
        assertEquals(emptyList<String>(), BlacklistStore.parse("[]"))
        assertEquals(emptyList<String>(), BlacklistStore.parse("lixo nao-json"))
    }

    @Test fun parseLista() {
        assertEquals(listOf("foo", "bar"), BlacklistStore.parse("""["foo","bar"]"""))
    }

    @Test fun roundTrip() {
        val termos = listOf("Capítulo I", "nota de rodapé")
        assertEquals(termos, BlacklistStore.parse(BlacklistStore.serialize(termos)))
    }

    @Test fun serializeVazio() {
        assertEquals("[]", BlacklistStore.serialize(emptyList()))
    }
}
