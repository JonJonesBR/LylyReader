package com.jonjonesbr.audiobookgen.util

import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MS_POR_SEGUNDO = 1000L
private const val SEGUNDOS_POR_HORA = 3600L
private const val SEGUNDOS_POR_MINUTO = 60L
private const val BYTES_POR_KB = 1_000
private const val BYTES_POR_MB = 1_000_000

/** Posição do mini-player: "m:ss" (minutos sem limite, ex.: "75:04"). */
fun formatarTempo(ms: Int): String {
    val msPositivo = ms.coerceAtLeast(0).toLong()
    val min = TimeUnit.MILLISECONDS.toMinutes(msPositivo)
    val sec = TimeUnit.MILLISECONDS.toSeconds(msPositivo) % SEGUNDOS_POR_MINUTO
    return String.format(Locale.getDefault(), "%d:%02d", min, sec)
}

/** Duração do audiolivro na lista: "m:ss", ou "h:mm:ss" a partir de 1 hora. */
fun formatarDuracao(ms: Long): String {
    val s = ms / MS_POR_SEGUNDO
    val h = s / SEGUNDOS_POR_HORA
    val m = (s % SEGUNDOS_POR_HORA) / SEGUNDOS_POR_MINUTO
    val seg = s % SEGUNDOS_POR_MINUTO
    return if (h > 0) "%d:%02d:%02d".format(h, m, seg)
           else       "%d:%02d".format(m, seg)
}

/** Tamanho do arquivo na lista: base decimal (1 KB = 1000 B), "B", "KB" ou "MB" com uma casa. */
fun formatarTamanho(bytes: Long): String {
    return when {
        bytes >= BYTES_POR_MB -> "%.1fMB".format(bytes / BYTES_POR_MB.toDouble())
        bytes >= BYTES_POR_KB -> "%dKB".format(bytes / BYTES_POR_KB)
        else -> "${bytes}B"
    }
}
