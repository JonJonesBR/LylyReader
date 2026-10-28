package com.jonjonesbr.audiobookgen.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GestoArrasteTest {

    @Test fun eixoSoDepoisDaZonaMorta() {
        assertNull(GestoArraste.eixo(5f, 3f, slop = 10f))
        assertEquals(EixoArraste.HORIZONTAL, GestoArraste.eixo(-20f, 4f, slop = 10f))
        assertEquals(EixoArraste.VERTICAL, GestoArraste.eixo(3f, 25f, slop = 10f))
        // Diagonal (rolagem torta): nem troca de tela nem mini player.
        assertEquals(EixoArraste.DIAGONAL, GestoArraste.eixo(20f, 18f, slop = 10f))
    }

    @Test fun completouPorDistanciaOuPorMovimentoRapido() {
        assertTrue(GestoArraste.completou(-100f, 0f, distanciaMin = 80f, velocidadeMin = 500f))
        assertTrue(GestoArraste.completou(-45f, -900f, distanciaMin = 80f, velocidadeMin = 500f))
        // Rápido, mas curto demais; ou rápido no sentido contrário (o dedo voltou).
        assertFalse(GestoArraste.completou(-30f, -900f, distanciaMin = 80f, velocidadeMin = 500f))
        assertFalse(GestoArraste.completou(-60f, 900f, distanciaMin = 80f, velocidadeMin = 500f))
        assertFalse(GestoArraste.completou(-60f, -100f, distanciaMin = 80f, velocidadeMin = 500f))
    }

    @Test fun lateralPercorreAsAbasInternasAntesDeTrocarDeTela() {
        // Biblioteca (tela 0) com 3 abas internas, na primeira.
        assertEquals(DestinoLateral.AbaInterna(1), GestoArraste.destinoLateral(true, 0, 3, 0, 4))
        assertEquals(DestinoLateral.AbaInterna(2), GestoArraste.destinoLateral(true, 1, 3, 0, 4))
        // Da última aba interna segue para a tela seguinte, que abre do começo.
        assertEquals(DestinoLateral.TelaPrincipal(1, ultimaAbaInterna = false), GestoArraste.destinoLateral(true, 2, 3, 0, 4))
        // Voltando da primeira aba interna: tela anterior, aberta na última aba dela.
        assertEquals(DestinoLateral.TelaPrincipal(1, ultimaAbaInterna = true), GestoArraste.destinoLateral(false, 0, 2, 2, 4))
        assertEquals(DestinoLateral.AbaInterna(0), GestoArraste.destinoLateral(false, 1, 2, 2, 4))
    }

    @Test fun telaSemAbasEExtremos() {
        assertEquals(DestinoLateral.TelaPrincipal(2, ultimaAbaInterna = false), GestoArraste.destinoLateral(true, -1, 0, 1, 4))
        assertEquals(DestinoLateral.TelaPrincipal(0, ultimaAbaInterna = true), GestoArraste.destinoLateral(false, -1, 0, 1, 4))
        // Primeira e última telas não dão a volta.
        assertEquals(DestinoLateral.Nenhum, GestoArraste.destinoLateral(false, 0, 3, 0, 4))
        assertEquals(DestinoLateral.Nenhum, GestoArraste.destinoLateral(true, 3, 4, 3, 4))
        // Tela fora da barra (não deveria acontecer): nada.
        assertEquals(DestinoLateral.Nenhum, GestoArraste.destinoLateral(true, -1, 0, -1, 4))
    }
}
