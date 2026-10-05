package com.jonjonesbr.audiobookgen.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogWriter {
    private const val TAG = "CrashLogWriter"
    private const val MAX_LOG_FILES = 20
    private const val FILE_PREFIX = "lyly_error_"
    private const val DIR_NAME = "error_logs"

    private var appContext: Context? = null
    private var appVersion: String = "?"
    private var appBuild: String = "?"

    fun init(context: Context) {
        appContext = context.applicationContext
        appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }
        appBuild = try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode.toString()
            } else {
                pkgInfo.versionCode.toString()
            }
        } catch (_: Exception) { "?" }
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun log(
        throwable: Throwable?,
        contextTag: String = "",
        message: String = "",
        extraInfo: Map<String, String> = emptyMap()
    ) {
        try {
            val ctx = appContext ?: run {
                Log.e(TAG, "CrashLogWriter nao inicializado. Chame init() no Application.onCreate()")
                return
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'", Locale.US).format(Date())
            val sb = StringBuilder()

            sb.appendLine("═══════════════════════════════════════════")
            sb.appendLine("LYLY READER — LOG DE ERRO")
            sb.appendLine("═══════════════════════════════════════════")
            sb.appendLine("Data/Hora: $timestamp")
            sb.appendLine("App: LylyReader v${appVersion} (build ${appBuild})")
            sb.appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
            sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine()

            if (message.isNotBlank()) {
                sb.appendLine("MENSAGEM: $message")
                sb.appendLine()
            }

            if (contextTag.isNotBlank()) {
                sb.appendLine("CONTEXTO: $contextTag")
                sb.appendLine()
            }

            if (extraInfo.isNotEmpty()) {
                sb.appendLine("INFORMACOES ADICIONAIS:")
                extraInfo.forEach { (k, v) -> sb.appendLine("  $k: $v") }
                sb.appendLine()
            }

            if (throwable != null) {
                sb.appendLine("STACK TRACE:")
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()
                sb.append(sw.toString())
                sb.appendLine()

                var cause = throwable.cause
                var causeIdx = 1
                while (cause != null) {
                    sb.appendLine("Causa $causeIdx:")
                    val csw = StringWriter()
                    val cpw = PrintWriter(csw)
                    cause.printStackTrace(cpw)
                    cpw.flush()
                    sb.append(csw.toString())
                    sb.appendLine()
                    cause = cause.cause
                    causeIdx++
                }
            }

            sb.appendLine("Thread: ${Thread.currentThread().name}")
            sb.appendLine("═══════════════════════════════════════════")

            val fileName = "${FILE_PREFIX}${timestamp}.txt"
            writeToInternalLogs(ctx, fileName, sb.toString())
            rotateOldFiles(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar log de erro", e)
        }
    }

    fun getLogsDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getLogFiles(context: Context): List<File> {
        val dir = getLogsDir(context)
        return dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?.toList() ?: emptyList()
    }

    @Suppress("TooGenericExceptionCaught")
    fun clearAllLogs(context: Context) {
        try {
            val dir = getLogsDir(context)
            dir.listFiles()?.forEach { it.delete() }
            Log.i(TAG, "Todos os logs de erro apagados")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao limpar logs de erro", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeToInternalLogs(context: Context, fileName: String, content: String) {
        try {
            val dir = getLogsDir(context)
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            Log.i(TAG, "Log salvo internamente: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao escrever log de erro internamente", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun rotateOldFiles(context: Context) {
        try {
            val dir = getLogsDir(context)
            val files = dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".txt") }
                ?.sortedBy { it.lastModified() } ?: return
            
            val mutableFiles = files.toMutableList()
            while (mutableFiles.size > MAX_LOG_FILES) {
                mutableFiles.first().delete()
                mutableFiles.removeAt(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao rotacionar logs", e)
        }
    }
}
