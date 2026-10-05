package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AjustesDaConversaoRegrasTest {
    private fun montar(
        escolhaVoz: String? = null, escolhaMotor: String? = null, escolhaRitmo: Int? = null,
        livroVoz: String? = null, livroMotor: String? = null, livroVelocidade: Float? = null,
        perfil: PerfilAudioLivro? = null,
        globalVoz: String? = "voz-global", globalMotor: String = "edge", globalRitmo: Int = 100
    ) = AjustesDaConversaoRegras.montar(
        escolhaVoz, escolhaMotor, escolhaRitmo, livroVoz, livroMotor, livroVelocidade, perfil,
        globalVoz, globalMotor, globalRitmo
    ) { motor -> "padrao-$motor" }

    @Test
    fun `sem livro nem escolha usa os globais`() {
        val a = montar()
        assertEquals("voz-global", a.voz)
        assertEquals("edge", a.motor)
        assertEquals(100, a.ritmo)
        assertNull(a.tomMeiosTons)
    }

    @Test
    fun `perfil do livro vale mais que o global`() {
        val a = montar(livroVoz = "voz-livro", livroMotor = "onnx", livroVelocidade = 1.25f)
        assertEquals("voz-livro", a.voz)
        assertEquals("onnx", a.motor)
        assertEquals(125, a.ritmo)
    }

    @Test
    fun `escolha na hora vale mais que o perfil do livro`() {
        val a = montar(escolhaVoz = "voz-painel", escolhaMotor = "pocket", escolhaRitmo = 80, livroVoz = "voz-livro", livroMotor = "onnx", livroVelocidade = 1.5f)
        assertEquals("voz-painel", a.voz)
        assertEquals("pocket", a.motor)
        assertEquals(80, a.ritmo)
    }

    @Test
    fun `voz do livro nao e usada com outro motor`() {
        val a = montar(escolhaMotor = "pocket", livroVoz = "voz-livro", livroMotor = "onnx")
        assertEquals("padrao-pocket", a.voz)
    }

    @Test
    fun `voz global nao e usada com outro motor`() {
        val a = montar(escolhaMotor = "onnx", globalVoz = "voz-global", globalMotor = "edge")
        assertEquals("padrao-onnx", a.voz)
    }

    @Test
    fun `ritmo e limitado a faixa permitida`() {
        assertEquals(RITMO_CONVERSAO_MAX, montar(livroVelocidade = 3.5f).ritmo)
        assertEquals(RITMO_CONVERSAO_MIN, montar(escolhaRitmo = 10).ritmo)
    }

    @Test
    fun `tom e agudos vem do perfil de audio do livro quando existe`() {
        val a = montar(perfil = PerfilAudioLivro(tomMeiosTons = 2, agudosDb = 3))
        assertEquals(2, a.tomMeiosTons)
        assertEquals(3, a.agudosDb)
    }
}
