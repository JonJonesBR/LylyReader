package com.jonjonesbr.audiobookgen.util

import android.app.ActivityManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
    private const val MAX_AUDIOS = 80
    private const val PASTA_AUDIOS = "Download/LylyDiag"

    /**
     * Desligado: a cópia de cada trecho (uns 36 MB em Download/LylyDiag) só se liga, na compilação, quando uma
     * investigação precisa ouvir o áudio fora do app. O registro de texto (`lyly_error_diag_sintese.txt`) segue ativo.
     */
    private const val EXPORTAR_AUDIOS = false
    private const val DECIMOS = 10
    private const val MB = 1024L * 1024L
    private const val KB_POR_MB = 1024L
    private const val SEGUNDOS_PREVISAO_TERMICA = 10

    @Volatile private var ativoCache: Boolean? = null
    private val lock = Any()
    private val executor by lazy { Executors.newSingleThreadExecutor() }

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

    /**
     * Copia o [arquivo] de áudio que vai tocar para Download/LylyDiag (só Android 10+, só no APK de teste), em
     * segundo plano, e registra o nome e o [texto] no log. Guarda só os [MAX_AUDIOS] mais recentes. Serve para ouvir
     * o trecho fora do app e para analisar o sinal: separa defeito do áudio gerado de defeito do player.
     */
    fun exportarAudio(context: Context, arquivo: File, rotulo: String, texto: String?) {
        if (!EXPORTAR_AUDIOS || !ativo(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val app = context.applicationContext
        executor.execute {
            runCatching {
                val hora = SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date())
                // O áudio mantém a extensão de origem: vozes do Python (Edge etc.) entregam MP3, não WAV.
                val nome = "lyly_diag_${hora}_$rotulo.${arquivo.extension.ifBlank { "wav" }}"
                if (copiarParaDownloads(app, arquivo, nome)) {
                    registrar(app, "audio.export", "nome" to nome, "texto" to texto)
                    apararAudios(app)
                }
            }.onFailure { Log.w(TAG, "Falha ao exportar o áudio de diagnóstico", it) }
        }
    }

    /** Cria o [nome] em [PASTA_AUDIOS] pelo MediaStore (sem permissão no Android 10+) e copia o [arquivo]. */
    private fun copiarParaDownloads(context: Context, arquivo: File, nome: String): Boolean {
        val valores = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeDoAudio(arquivo))
            put(MediaStore.MediaColumns.RELATIVE_PATH, PASTA_AUDIOS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, valores)
        val saida = uri?.let { resolver.openOutputStream(it) }
        saida?.use { out -> arquivo.inputStream().use { it.copyTo(out) } }
        return saida != null
    }

    private fun mimeDoAudio(arquivo: File): String =
        if (arquivo.extension.equals("mp3", ignoreCase = true)) "audio/mpeg" else "audio/x-wav"

    /** Apaga os áudios de diagnóstico além dos [MAX_AUDIOS] mais recentes (só os que o próprio app criou). */
    private fun apararAudios(context: Context) {
        val resolver = context.contentResolver
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(
            base, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("lyly_diag_%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            var posicao = 0
            while (cursor.moveToNext()) {
                posicao++
                if (posicao > MAX_AUDIOS) {
                    resolver.delete(ContentUris.withAppendedId(base, cursor.getLong(0)), null, null)
                }
            }
        }
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
            // pelo processo desde o início e memória dele: separam aquecimento,
            // disputa de CPU e inchaço do processo.
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
