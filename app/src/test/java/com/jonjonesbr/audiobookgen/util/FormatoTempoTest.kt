package com.jonjonesbr.audiobookgen.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class FormatoTempoTest {

    private var localePadrao: Locale? = null

    @Before fun fixaLocale() {
        // Os formatos usam o locale padrão (String.format / format): fixa en-US para ser determinístico.
        localePadrao = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restauraLocale() {
        localePadrao?.let { Locale.setDefault(it) }
    }

    // ── formatarTempo (mini-player: "m:ss", sem horas) ─────────────────────────

    @Test fun tempoZeroViraZeroZeroZero() {
        assertEquals("0:00", formatarTempo(0))
    }

    @Test fun tempoMenosDeUmSegundoViraZeroZero() {
        assertEquals("0:00", formatarTempo(999))
    }

    @Test fun tempoNegativoViraZeroZeroZero() {
        assertEquals("0:00", formatarTempo(-1_000))
    }

    @Test fun tempoUmSegundoExato() {
        assertEquals("0:01", formatarTempo(1_000))
    }

    @Test fun tempoCinquentaENoveSegundos() {
        assertEquals("0:59", formatarTempo(59_000))
    }

    @Test fun tempoSessentaSegundosViraUmMinuto() {
        assertEquals("1:00", formatarTempo(60_000))
    }

    @Test fun tempoUmaHoraExataViraSessentaMinutosSemHoras() {
        assertEquals("60:00", formatarTempo(3_600_000))
    }

    @Test fun tempoGrandeMantemMinutosSemLimite() {
        assertEquals("75:04", formatarTempo(4_504_000))
    }

    @Test fun tempoMaximoDeInteiro() {
        // Int.MAX_VALUE ms = 35791 min e 23 s
        assertEquals("35791:23", formatarTempo(Int.MAX_VALUE))
    }

    // ── formatarDuracao (lista: "m:ss" ou "h:mm:ss") ───────────────────────────

    @Test fun duracaoZeroViraZeroZeroZero() {
        assertEquals("0:00", formatarDuracao(0L))
    }

    @Test fun duracaoMenosDeUmSegundoViraZeroZero() {
        assertEquals("0:00", formatarDuracao(999L))
    }

    @Test fun duracaoAntesDaPrimeiraHoraUsaMinutoESegundo() {
        assertEquals("0:59", formatarDuracao(59_999L))
        assertEquals("59:59", formatarDuracao(3_599_000L))
    }

    @Test fun duracaoUmaHoraExataMostraHoras() {
        assertEquals("1:00:00", formatarDuracao(3_600_000L))
    }

    @Test fun duracaoUmaHoraUmMinutoUmSegundo() {
        assertEquals("1:01:01", formatarDuracao(3_661_000L))
    }

    @Test fun duracaoCemHorasGrandeValor() {
        assertEquals("100:00:00", formatarDuracao(360_000_000L))
    }

    // ── formatarTamanho (base decimal: 1 KB = 1000 B) ──────────────────────────

    @Test fun tamanhoZeroBytes() {
        assertEquals("0B", formatarTamanho(0L))
    }

    @Test fun tamanhoAbaixoDeMilBytesFicaEmBytes() {
        assertEquals("999B", formatarTamanho(999L))
    }

    @Test fun tamanhoAcimaDeMilBytesTruncaParaKbInteiro() {
        // 1023 B na base 1000 já é "1KB" (divisão inteira), não "1023B".
        assertEquals("1KB", formatarTamanho(1023L))
    }

    @Test fun tamanhoMilBytesViraUmKb() {
        assertEquals("1KB", formatarTamanho(1_000L))
    }

    @Test fun tamanhoMilVinteQuatroBytesAindaEhUmKb() {
        assertEquals("1KB", formatarTamanho(1024L))
    }

    @Test fun tamanhoUmMegaDecimalViraUmVirgulaZeroMb() {
        assertEquals("1.0MB", formatarTamanho(1_000_000L))
    }

    @Test fun tamanhoUmMebibyteViraUmVirgulaZeroMb() {
        assertEquals("1.0MB", formatarTamanho(1024L * 1024L))
    }

    @Test fun tamanhoFracionarioArredondaUmaCasa() {
        assertEquals("1.5MB", formatarTamanho(1_500_000L))
        assertEquals("1.2MB", formatarTamanho(1_234_567L))
        assertEquals("2.0MB", formatarTamanho(1_999_999L))
    }

    @Test fun tamanhoGrandeFicaEmMegabytes() {
        assertEquals("2500.0MB", formatarTamanho(2_500_000_000L))
    }
}
