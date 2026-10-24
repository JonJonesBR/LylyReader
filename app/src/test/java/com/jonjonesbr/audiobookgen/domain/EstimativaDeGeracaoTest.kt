package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EstimativaDeGeracaoTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val formatoPt = { h: Int -> "$h horas" }
    private val minutosPt = { m: Int -> "$m minutos" }
    private val juntarPt = { a: String, b: String -> "$a e $b" }

    @Test
    fun motorDaVozSegueDespachoDoTts() {
        assertEquals("android", EstimativaDeGeracao.motorDaVoz("android::com.google.tts::pt-br"))
        assertEquals("onnx", EstimativaDeGeracao.motorDaVoz("supertonic-pt"))
        assertEquals("piper", EstimativaDeGeracao.motorDaVoz("piper-ptbr-cadu::main-pt"))
        assertEquals("pocket", EstimativaDeGeracao.motorDaVoz("pocket-en-alba"))
        assertEquals("kokoro", EstimativaDeGeracao.motorDaVoz("kokoro-pf-dora"))
        assertEquals("kokoro", EstimativaDeGeracao.motorDaVoz("mms-por::voz"))
        assertEquals("edge", EstimativaDeGeracao.motorDaVoz("pt-BR-FranciscaNeural"))
    }

    @Test
    fun chaveDeMedicaoBateComAGravadaPeloPython() {
        // Mesma tabela de test_velocidade_motores.py (CHAVES_DE_MEDICAO_ESPERADAS): o Python grava
        // velocidade_motores.json com estas chaves e este lado as lê.
        val esperado = mapOf(
            "piper-ptbr-cadu::main-pt" to "piper",
            "piper-ptbr-faber::main-pt" to "piper",
            "kokoro-pf-dora" to "kokoro",
            "kokoro-en-am-santa" to "kokoro",
            "mms-por::main-pt" to "kokoro",
            "pocket-ptbr-rafael" to "pocket",
            "supertonic-m1" to "onnx",
            "android::pkg::voz" to "android",
            "pt-BR-FranciscaNeural" to "edge"
        )
        for ((voz, chave) in esperado) {
            assertEquals(voz, chave, EstimativaDeGeracao.motorDaVoz(voz))
        }
    }

    @Test
    fun motorDaVozDeNuvemSemEstimativaLocalDevolveNulo() {
        assertNull(EstimativaDeGeracao.motorDaVoz("elevenlabs-rachel"))
        assertNull(EstimativaDeGeracao.motorDaVoz("openrouter-algo"))
        assertNull(EstimativaDeGeracao.motorDaVoz("Aoede", ehGemini = true))
    }

    @Test
    fun parseIgnoraJsonCorrompidoEEntradasInvalidas() {
        assertTrue(EstimativaDeGeracao.parse("{ isto não é json").estimar(100, "edge") == null)
        assertTrue(EstimativaDeGeracao.parse(null).estimar(100, "edge") == null)
        assertTrue(EstimativaDeGeracao.parse("").estimar(100, "edge") == null)

        val e = EstimativaDeGeracao.parse(
            """{"edge": {"ms_por_char": 0}, "onnx": {"ms_por_char": -3}, "piper": {"ms_por_char": "x"},
               "kokoro": {"ms_por_char": 100.0, "amostras": 2}}"""
        )
        assertNull(e.estimar(1000, "edge"))
        assertNull(e.estimar(1000, "onnx"))
        assertNull(e.estimar(1000, "piper"))
        assertEquals(100_000.milliseconds, e.estimar(1000, "kokoro"))
    }

    @Test
    fun lerDoArquivoAusenteDevolveSemMedicoes() {
        val ausente = File(pasta.root, EstimativaDeGeracao.ARQUIVO)
        assertNull(EstimativaDeGeracao.lerDoArquivo(ausente).estimar(10, "edge"))
    }

    @Test
    fun lerDoArquivoLeMedicoesGravadas() {
        val arquivo = File(pasta.root, EstimativaDeGeracao.ARQUIVO)
        arquivo.writeText("""{"edge": {"ms_por_char": 12.5, "amostras": 3}}""")
        val e = EstimativaDeGeracao.lerDoArquivo(arquivo)
        assertEquals(125_000.milliseconds, e.estimar(10_000, "edge"))
    }

    @Test
    fun estimarUsaMedicaoDoAparelhoEMarcaComoNaoAproximada() {
        val e = EstimativaDeGeracao.parse("""{"onnx": {"ms_por_char": 20, "amostras": 1}}""")
        val estimativa = e.estimativa(60_000, "onnx")
        assertEquals(1200_000.milliseconds, estimativa?.duracao)
        assertFalse(estimativa!!.aproximada)
    }

    @Test
    fun estimativaSemMedicaoUsaReferenciaMarcadaComoAproximada() {
        val e = EstimativaDeGeracao.parse(null)
        val estimativa = e.estimativa(1000, "edge")
        assertEquals(1500.milliseconds, estimativa?.duracao)
        assertTrue(estimativa!!.aproximada)
    }

    @Test
    fun estimativaDeMotorDesconhecidoDevolveNulo() {
        assertNull(EstimativaDeGeracao.parse(null).estimativa(1000, "elevenlabs"))
    }

    @Test
    fun estimarComZeroCaracteresDevolveZero() {
        val e = EstimativaDeGeracao.parse("""{"edge": {"ms_por_char": 5}}""")
        assertEquals(0.milliseconds, e.estimar(0, "edge"))
    }

    @Test
    fun formatarMostraHorasEMinutosPorExtenso() {
        val texto = EstimativaDeGeracao.formatar(130.minutes, formatoPt, minutosPt, juntarPt)
        assertEquals("2 horas e 10 minutos", texto)
    }

    @Test
    fun formatarSemHorasMostraSoMinutos() {
        assertEquals("45 minutos", EstimativaDeGeracao.formatar(45.minutes, formatoPt, minutosPt, juntarPt))
    }

    @Test
    fun formatarHoraExataNaoMostraMinutosZero() {
        assertEquals("3 horas", EstimativaDeGeracao.formatar(3.hours, formatoPt, minutosPt, juntarPt))
    }

    @Test
    fun formatarArredondaParaOMinutoMaisProximo() {
        // 1 h 59 min 40 s -> 2 h 00 min -> "2 horas"
        val quase = 1.hours + 59.minutes + 40.seconds
        assertEquals("2 horas", EstimativaDeGeracao.formatar(quase, formatoPt, minutosPt, juntarPt))
    }

    @Test
    fun formatarDuracaoMinimaMostraUmMinuto() {
        assertEquals("1 minutos", EstimativaDeGeracao.formatar(5.milliseconds, formatoPt, minutosPt, juntarPt))
    }

    @Test
    fun avisaSoParaMotorLocalAcimaDeUmaHora() {
        assertTrue(EstimativaDeGeracao.deveAvisarLento("kokoro", 61.minutes))
        assertFalse(EstimativaDeGeracao.deveAvisarLento("kokoro", 1.hours))
        assertFalse(EstimativaDeGeracao.deveAvisarLento("edge", 5.hours))
        assertFalse(EstimativaDeGeracao.deveAvisarLento(null, 5.hours))
    }
}
