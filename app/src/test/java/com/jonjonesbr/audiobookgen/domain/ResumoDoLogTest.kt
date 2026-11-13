package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ResumoDoLogTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // Cabeçalho e bloco de informações no formato que o CrashLogWriter e o NativeCrashDetector gravam.
    private fun registroDeSaida(reason: String, description: String): List<String> = listOf(
        "═══════════════════════════════════════════",
        "LYLY READER — LOG DE ERRO",
        "═══════════════════════════════════════════",
        "Data/Hora: 2026-11-01T19-02-38-489Z",
        "App: LylyReader v1.8.74 (build 88)",
        "Dispositivo: Samsung SM-A515",
        "Android: 13 (API 33)",
        "",
        "CONTEXTO: ABNORMAL_EXIT_DETECTED",
        "",
        "INFORMACOES ADICIONAIS:",
        "  reason: $reason",
        "  status: 0x00000000",
        "  pid: 1234",
        "  processName: com.jonjonesbr.audiobookgen.dev",
        "  description: $description",
        "  trace_begin: (no trace)"
    )

    private fun registroComStackTrace(primeiraLinha: String): List<String> = listOf(
        "LYLY READER — LOG DE ERRO",
        "Data/Hora: 2026-11-01T14-50-00-000Z",
        "",
        "STACK TRACE:",
        primeiraLinha,
        "\tat com.jonjonesbr.audiobookgen.domain.Algo.rodar(Algo.kt:12)",
        "Thread: main"
    )

    @Test
    fun `pouca memoria e encerramento normal com motivo proprio`() {
        val resumo = classificarRegistroErro(registroDeSaida("REASON_LOW_MEMORY", "Low memory"))
        assertEquals(TipoRegistroErro.ENCERRAMENTO_NORMAL, resumo.tipo)
        assertTrue(resumo.poucaMemoria)
    }

    @Test
    fun `limpeza do sistema e encerramento normal sem pouca memoria`() {
        val swipe = classificarRegistroErro(registroDeSaida("REASON_OTHER(12)", "SwipeUpClean"))
        assertEquals(TipoRegistroErro.ENCERRAMENTO_NORMAL, swipe.tipo)
        assertFalse(swipe.poucaMemoria)

        val memoria = classificarRegistroErro(registroDeSaida("REASON_SIGNALED", "Clean_Up_Mem"))
        assertEquals(TipoRegistroErro.ENCERRAMENTO_NORMAL, memoria.tipo)
        assertFalse(memoria.poucaMemoria)
    }

    @Test
    fun `atualizacao, parada forcada e kill em background sao encerramento normal`() {
        listOf("installPackageLI", "force-stop", "stop by user", "kill_background").forEach { descricao ->
            val resumo = classificarRegistroErro(registroDeSaida("REASON_OTHER(1)", descricao))
            assertEquals(descricao, TipoRegistroErro.ENCERRAMENTO_NORMAL, resumo.tipo)
        }
    }

    @Test
    fun `crash nativo ou Java e travamento`() {
        val nativo = classificarRegistroErro(registroDeSaida("REASON_CRASH_NATIVE", "signal 11"))
        assertEquals(TipoRegistroErro.TRAVAMENTO, nativo.tipo)
        assertNull(nativo.excecao)

        val java = classificarRegistroErro(registroDeSaida("REASON_CRASH", "uncaught"))
        assertEquals(TipoRegistroErro.TRAVAMENTO, java.tipo)
    }

    @Test
    fun `stack trace de excecao vira travamento com o nome curto da excecao`() {
        val resumo = classificarRegistroComStackTrace("java.lang.IllegalStateException: boom")
        assertEquals(TipoRegistroErro.TRAVAMENTO, resumo.tipo)
        assertEquals("IllegalStateException", resumo.excecao)
    }

    @Test
    fun `excecao sem pacote e sem mensagem tambem e reconhecida`() {
        val resumo = classificarRegistroComStackTrace("ClasseEstranha\$Interna")
        assertEquals(TipoRegistroErro.TRAVAMENTO, resumo.tipo)
        assertEquals("Interna", resumo.excecao)
    }

    @Test
    fun `stack trace sem linha de excecao continua sendo travamento`() {
        val resumo = classificarRegistroComStackTrace("   ")
        assertEquals(TipoRegistroErro.TRAVAMENTO, resumo.tipo)
        assertNull(resumo.excecao)
    }

    @Test
    fun `anr e o app parou de responder`() {
        val resumo = classificarRegistroErro(registroDeSaida("REASON_ANR", "ANR in main"))
        assertEquals(TipoRegistroErro.SEM_RESPOSTA, resumo.tipo)
    }

    @Test
    fun `outros motivos e registros sem nada reconhecivel sao registro tecnico`() {
        val resumo = classificarRegistroErro(registroDeSaida("REASON_EXCESSIVE_RESOURCE_USAGE", "cpu"))
        assertEquals(TipoRegistroErro.OUTRO, resumo.tipo)
        assertEquals(TipoRegistroErro.OUTRO, classificarRegistroErro(emptyList()).tipo)
    }

    @Test
    fun `valor repetido depois do trace_begin nao troca o motivo`() {
        val linhas = registroDeSaida("REASON_ANR", "ANR in main") + listOf("  reason: REASON_LOW_MEMORY")
        assertEquals(TipoRegistroErro.SEM_RESPOSTA, classificarRegistroErro(linhas).tipo)
    }

    @Test
    fun `instante do nome do arquivo usa a hora local gravada`() {
        val esperado = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 1, 19, 2, 38)
            set(Calendar.MILLISECOND, 489)
        }.timeInMillis
        assertEquals(esperado, instanteDoNomeDoLog("lyly_error_2026-11-01T19-02-38-489Z.txt", utc))
        assertNull(instanteDoNomeDoLog("outro_arquivo.txt", utc))
    }

    @Test
    fun `instante do nome com processo de voz ignora o sufixo`() {
        val esperado = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 13, 10, 0, 0)
            set(Calendar.MILLISECOND, 123)
        }.timeInMillis
        assertEquals(esperado, instanteDoNomeDoLog("lyly_error_2026-11-13T10-00-00-123Z_pocket.txt", utc))
        assertEquals(
            esperado,
            instanteDoNomeDoLog("lyly_error_2026-11-13T10-00-00-123Z.txt", utc)
        )
        assertNull(instanteDoNomeDoLog("lyly_error_diag_sintese.txt", utc))
    }

    @Test
    fun `dia relativo distingue hoje, ontem e outros dias`() {
        val agora = instante(2026, Calendar.NOVEMBER, 2, 10, 0)
        assertEquals(DiaRelativo.HOJE, diaRelativo(instante(2026, Calendar.NOVEMBER, 2, 0, 5), agora, utc))
        assertEquals(DiaRelativo.ONTEM, diaRelativo(instante(2026, Calendar.NOVEMBER, 1, 23, 59), agora, utc))
        assertEquals(DiaRelativo.OUTRO, diaRelativo(instante(2026, Calendar.OCTOBER, 31, 23, 59), agora, utc))
    }

    @Test
    fun `ontem atravessa a virada do ano`() {
        val agora = instante(2027, Calendar.JANUARY, 1, 8, 0)
        assertEquals(DiaRelativo.ONTEM, diaRelativo(instante(2026, Calendar.DECEMBER, 31, 22, 0), agora, utc))
    }

    private fun classificarRegistroComStackTrace(primeiraLinha: String): ResumoRegistroErro =
        classificarRegistroErro(registroComStackTrace(primeiraLinha))

    private fun instante(ano: Int, mes: Int, dia: Int, hora: Int, minuto: Int): Long =
        Calendar.getInstance(utc).apply { clear(); set(ano, mes, dia, hora, minuto) }.timeInMillis
}
