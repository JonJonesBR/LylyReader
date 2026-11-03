package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausaParaOuvirTest {

    // ── devePausarParaOuvir ───────────────────────────────────────────────────

    @Test
    fun pausaQuandoEstaTocando() {
        assertTrue(devePausarParaOuvir(GuidedState(0, isPlaying = true, isLoading = false), false, false))
    }

    @Test
    fun pausaQuandoEstaCarregando() {
        assertTrue(devePausarParaOuvir(GuidedState(0, isPlaying = false, isLoading = true), false, false))
    }

    @Test
    fun naoPausaSemIndice() {
        assertFalse(devePausarParaOuvir(GuidedState(-1, isPlaying = true, isLoading = false), false, false))
    }

    @Test
    fun naoPausaJaPausada() {
        assertFalse(devePausarParaOuvir(GuidedState(0, isPlaying = true, isLoading = false), true, false))
    }

    @Test
    fun naoPausaTrocandoDeLivro() {
        assertFalse(devePausarParaOuvir(GuidedState(0, isPlaying = true, isLoading = true), false, true))
    }

    // ── deveRetomarAposOuvir ──────────────────────────────────────────────────

    private fun situacao(
        mesmaSessao: Boolean = true,
        semComandos: Boolean = true,
        indice: Int = 0,
        pausado: Boolean = true,
        trocando: Boolean = false,
        chamada: Boolean = false,
        previa: Boolean = false,
        tela: EstadoDaTela = EstadoDaTela.VIVA,
    ) = SituacaoAoSoltar(
        mesmaSessao = mesmaSessao,
        semComandosDesdeAPausa = semComandos,
        indice = indice,
        pausado = pausado,
        trocandoDeLivro = trocando,
        chamadaEmCurso = chamada,
        previewEmCurso = previa,
        tela = tela,
    )

    @Test
    fun retomaNoCasoBase() {
        assertTrue(deveRetomarAposOuvir(situacao()))
    }

    @Test
    fun naoRetomaComTelaDestruida() {
        assertFalse(deveRetomarAposOuvir(situacao(tela = EstadoDaTela.DESTRUIDA)))
    }

    @Test
    fun naoRetomaDeOutraSessao() {
        assertFalse(deveRetomarAposOuvir(situacao(mesmaSessao = false)))
    }

    @Test
    fun naoRetomaSeMexeramNaReproducao() {
        assertFalse(deveRetomarAposOuvir(situacao(semComandos = false)))
    }

    @Test
    fun naoRetomaSemIndice() {
        assertFalse(deveRetomarAposOuvir(situacao(indice = -1)))
    }

    @Test
    fun naoRetomaSeNaoEstavaPausada() {
        assertFalse(deveRetomarAposOuvir(situacao(pausado = false)))
    }

    @Test
    fun naoRetomaTrocandoDeLivro() {
        assertFalse(deveRetomarAposOuvir(situacao(trocando = true)))
    }

    @Test
    fun naoRetomaComChamadaEmCurso() {
        assertFalse(deveRetomarAposOuvir(situacao(chamada = true)))
    }

    @Test
    fun naoRetomaComPreviaEmCurso() {
        assertFalse(deveRetomarAposOuvir(situacao(previa = true)))
    }

    @Test
    fun retomaNoGiroDeTela() {
        assertTrue(deveRetomarAposOuvir(situacao(tela = EstadoDaTela.GIRO)))
    }

    // ── PausaParaOuvir ────────────────────────────────────────────────────────

    /** Manager simulado: pausa, retoma e conta comandos como o real (o contador soma antes do retorno antecipado). */
    private var pausado = false
    private var comandos = 0L
    private var previa = false
    private var chamada = false
    private var tela = EstadoDaTela.VIVA
    private val estado = MutableStateFlow(GuidedState(0, isPlaying = true, isLoading = false))
    private val manager: GuidedPlayerManager = mockk(relaxed = true) {
        every { state } returns estado
        every { estaPausado } answers { pausado }
        every { trocandoDeLivro } returns false
        every { comandosDeReproducao } answers { comandos }
        every { pause() } answers {
            comandos++
            if (!pausado) {
                pausado = true
                estado.value = GuidedState(0, isPlaying = false, isLoading = false)
            }
        }
        every { resume() } answers {
            comandos++
            pausado = false
            estado.value = GuidedState(0, isPlaying = true, isLoading = false)
        }
    }

    private fun pausaParaOuvir(sessao: GuidedPlayerManager? = manager) = PausaParaOuvir(
        obterManager = { manager },
        previewEmCurso = { previa },
        chamadaEmCurso = { chamada },
        estadoDaTela = { tela },
        sessaoVigente = { sessao },
    )

    @Test
    fun soltarPrimeiraSeguradaNaoRetoma() {
        val pausa = pausaParaOuvir()
        val primeira = pausa.segurar()
        pausa.segurar()

        primeira.soltar()

        verify(exactly = 0) { manager.resume() }
    }

    @Test
    fun soltarUltimaSeguradaRetomaUmaVez() {
        val pausa = pausaParaOuvir()
        val primeira = pausa.segurar()
        val segunda = pausa.segurar()

        primeira.soltar()
        segunda.soltar()

        verify(exactly = 1) { manager.resume() }
    }

    @Test
    fun soltarDuasVezesMesmaSeguradaContaUmaVez() {
        val pausa = pausaParaOuvir()
        val primeira = pausa.segurar()
        val segunda = pausa.segurar()

        primeira.soltar()
        primeira.soltar()
        verify(exactly = 0) { manager.resume() }

        segunda.soltar()
        verify(exactly = 1) { manager.resume() }
    }

    @Test
    fun guiadaJaPausadaNuncaRetoma() {
        pausado = true
        val pausa = pausaParaOuvir()

        pausa.segurar().soltar()

        verify(exactly = 0) { manager.pause() }
        verify(exactly = 0) { manager.resume() }
    }

    @Test
    fun comandoDeReproducaoNoMeioNaoRetoma() {
        val pausa = pausaParaOuvir()
        val segura = pausa.segurar()
        comandos++ // a pessoa mexeu no play ou no pause enquanto ouvia

        segura.soltar()

        verify(exactly = 0) { manager.resume() }
    }

    @Test
    fun pausaSoNoInicioNaoPausaDeNovoComSeguradaAberta() {
        val pausa = pausaParaOuvir()
        pausa.segurar(pausaSoNoInicio = true)
        verify(exactly = 1) { manager.pause() }
        // A pessoa deu play pela notificação com o diálogo aberto: abrir o próximo diálogo não pausa de novo.
        pausado = false
        comandos++
        estado.value = GuidedState(0, isPlaying = true, isLoading = false)

        pausa.segurar(pausaSoNoInicio = true)

        verify(exactly = 1) { manager.pause() }
    }

    @Test
    fun outraSessaoNaoRetoma() {
        val pausa = pausaParaOuvir(sessao = null)

        pausa.segurar().soltar()

        verify(exactly = 0) { manager.resume() }
    }

    @Test
    fun previaEmCursoNaoRetoma() {
        val pausa = pausaParaOuvir()
        val segura = pausa.segurar()
        previa = true

        segura.soltar()

        verify(exactly = 0) { manager.resume() }
    }

    @Test
    fun girarTelaComSeguradaSoltaRetoma() {
        val pausa = pausaParaOuvir()
        val segura = pausa.segurar()
        tela = EstadoDaTela.GIRO

        segura.soltar()

        verify(exactly = 1) { manager.resume() }
    }

    @Test
    fun trocarDeVozDuranteAAmostraNaoRetomaAVozAntiga() {
        val pausa = pausaParaOuvir()
        val amostra = pausa.segurar()
        // A troca de voz segura em volta do pararPreview e do play (ReaderVozController.selecionarVoz).
        val troca = pausa.segurar()
        previa = false // o pararPreview encerra a prévia...
        amostra.soltar() // ...e solta a segurada da amostra antes do play: a voz antiga não pode voltar aqui
        verify(exactly = 0) { manager.resume() }

        comandos++ // o play da voz nova muda o contador de comandos
        troca.soltar()

        verify(exactly = 0) { manager.resume() }
    }

    @Test
    fun telaDestruidaNaoRetoma() {
        val pausa = pausaParaOuvir()
        val segura = pausa.segurar()
        tela = EstadoDaTela.DESTRUIDA

        segura.soltar()

        verify(exactly = 0) { manager.resume() }
    }
}
