package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.AUTO_REWIND_RECUO_CURTO_MS
import com.jonjonesbr.audiobookgen.util.AUTO_REWIND_RECUO_LONGO_MS
import com.jonjonesbr.audiobookgen.util.AutoRewindMode
import com.jonjonesbr.audiobookgen.util.calcularRecuoAutoRewind
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRewindTest {

    private val umMinuto = 60_000L

    @Test fun modoOffNuncaRecua() {
        assertEquals(0, calcularRecuoAutoRewind(AutoRewindMode.OFF, 60 * umMinuto))
    }

    @Test fun pausaCurtaAbaixoDoLimiarNaoRecua() {
        assertEquals(0, calcularRecuoAutoRewind(AutoRewindMode.CURTO, 4 * umMinuto))
    }

    @Test fun modoCurtoRecuaValorCurtoAposCincoMinutos() {
        assertEquals(AUTO_REWIND_RECUO_CURTO_MS, calcularRecuoAutoRewind(AutoRewindMode.CURTO, 5 * umMinuto))
    }

    @Test fun modoCurtoNaoEscalaMesmoAposTrintaMinutos() {
        assertEquals(AUTO_REWIND_RECUO_CURTO_MS, calcularRecuoAutoRewind(AutoRewindMode.CURTO, 60 * umMinuto))
    }

    @Test fun modoLongoRecuaValorCurtoEntreCincoETrintaMinutos() {
        assertEquals(AUTO_REWIND_RECUO_CURTO_MS, calcularRecuoAutoRewind(AutoRewindMode.LONGO, 10 * umMinuto))
    }

    @Test fun modoLongoEscalaParaRecuoLongoAposTrintaMinutos() {
        assertEquals(AUTO_REWIND_RECUO_LONGO_MS, calcularRecuoAutoRewind(AutoRewindMode.LONGO, 30 * umMinuto))
    }

    @Test fun modoLongoRecuaValorLongoAposHoras() {
        assertEquals(AUTO_REWIND_RECUO_LONGO_MS, calcularRecuoAutoRewind(AutoRewindMode.LONGO, 5 * 60 * umMinuto))
    }
}
