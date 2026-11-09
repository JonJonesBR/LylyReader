package com.jonjonesbr.audiobookgen.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnóstico da síntese de voz e da leitura guiada, só no APK de teste (id `.dev`).
 *
 * Cada linha traz o horário, o processo, o assunto e os campos, mais o estado do aparelho (temperatura da
 * bateria, estado térmico, memória livre): serve para ver se a lentidão acompanha o aquecimento, se um trecho
 * foi refeito e quais trechos saem rápidos demais. O arquivo tem o prefixo dos logs de erro, então aparece em
 * "Logs de erro" e sai junto com eles. Fica no app principal e no processo isolado do Supertonic (que escrevem
 * no mesmo arquivo, em modo de acréscimo). Nos outros APKs não faz nada.
 */
object DiagnosticoSintese {
    private const val TAG = "DiagnosticoSintese"
    private const val NOME_ARQUIVO = "lyly_error_diag_sintese.txt"
    private const val SUFIXO_APK_DE_TESTE = ".dev"
    const val TAMANHO_MAX = 400_000
    private const val DECIMOS = 10
    private const val MB = 1024L * 1024L
    private const val KB_POR_MB = 1024L
    private const val SEGUNDOS_PREVISAO_TERMICA = 10

    @Volatile private var ativoCache: Boolean? = null
    private val lock = Any()

    fun ativo(context: Context): Boolean =
        ativoCache ?: context.packageName.endsWith(SUFIXO_APK_DE_TESTE).also { ativoCache = it }

    /** Registra uma linha. [campos] vira `chave=valor` separados por espaço. Nunca lança. */
    fun registrar(context: Context, assunto: String, vararg campos: Pair<String, Any?>) {
        if (!ativo(context)) return
        runCatching {
            val linha = montarLinha(
                hora = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()),
                processo = Process.myPid(),
                assunto = assunto,
                campos = campos.toList(),
                aparelho = estadoDoAparelho(context)
            )
            val arquivo = File(CrashLogWriter.getLogsDir(context), NOME_ARQUIVO)
            synchronized(lock) {
                if (arquivo.length() > TAMANHO_MAX) {
                    arquivo.writeText(aparar(arquivo.readText(), TAMANHO_MAX / 2))
                }
                FileOutputStream(arquivo, true).use { it.write(linha.toByteArray(Charsets.UTF_8)) }
            }
        }.onFailure { Log.w(TAG, "Falha ao registrar diagnóstico", it) }
    }

    internal fun montarLinha(
        hora: String,
        processo: Int,
        assunto: String,
        campos: List<Pair<String, Any?>>,
        aparelho: String
    ): String {
        val corpo = campos.joinToString(" ") { (k, v) -> "$k=${formatar(v)}" }
        return "$hora p$processo $assunto $corpo | $aparelho\n"
    }

    private fun formatar(v: Any?): String = when (v) {
        null -> "-"
        is Float -> "%.2f".format(Locale.US, v)
        is Double -> "%.2f".format(Locale.US, v)
        is String -> if (v.any { it.isWhitespace() }) "\"${v.replace('"', '\'').replace('\n', ' ')}\"" else v
        else -> v.toString()
    }

    /** Fica só com o final do [conteudo] (até [manter] caracteres), começando numa linha inteira. */
    internal fun aparar(conteudo: String, manter: Int): String {
        if (conteudo.length <= manter) return conteudo
        val corte = conteudo.length - manter
        val inicio = conteudo.indexOf('\n', corte)
        return if (inicio < 0) "" else conteudo.substring(inicio + 1)
    }

    private fun estadoDoAparelho(context: Context): String {
        val partes = mutableListOf<String>()
        runCatching {
            val bateria = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = bateria?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (temp >= 0) partes += "bat=${temp / DECIMOS}.${temp % DECIMOS}C"
            val nivel = bateria?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            if (nivel >= 0) partes += "carga=$nivel%"
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                partes += "termico=${pm.currentThermalStatus}"
            }
        }
        runCatching {
            // Folga térmica prevista (1,0 = no limite do aquecimento; o estado térmico só muda depois), CPU gasta
            // pelo processo desde o início e memória dele: separam aquecimento, disputa de CPU e inchaço do processo.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val folga = pm.getThermalHeadroom(SEGUNDOS_PREVISAO_TERMICA)
                if (!folga.isNaN()) partes += "folgaTermica=%.2f".format(Locale.US, folga)
            }
        }
        partes += "cpuProc=${Process.getElapsedCpuTime()}ms"
        partes += "pss=${Debug.getPss() / KB_POR_MB}MB"
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            partes += "ram=${info.availMem / MB}MB" + if (info.lowMemory) "(baixa)" else ""
        }
        return partes.joinToString(" ")
    }
}
