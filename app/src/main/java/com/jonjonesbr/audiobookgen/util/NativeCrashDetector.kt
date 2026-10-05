package com.jonjonesbr.audiobookgen.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log

object NativeCrashDetector {
    private const val TAG = "NativeCrashDetector"
    private const val PREF_NAME = "native_crash_prefs"
    private const val KEY_LAST_CHECKED = "last_checked_timestamp"
    private const val TRACE_MAX_CHARS = 2000

    // Motivos de saída "normais" (usuário fechou o app, parou o serviço, atualização) — não
    // interessam para diagnóstico. Tudo que não está nessa lista (crash nativo/Java, ANR,
    // low memory, etc.) é logado — inclui as saídas do processo isolado :supertonic, já que
    // getHistoricalProcessExitReasons retorna o histórico de TODOS os processos do pacote,
    // não só o principal.
    private val benignReasons = setOf(
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED
    )

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "REASON_CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "REASON_CRASH"
        ApplicationExitInfo.REASON_ANR -> "REASON_ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "REASON_LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "REASON_SIGNALED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "REASON_EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "REASON_INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "REASON_DEPENDENCY_DIED"
        else -> "REASON_OTHER($reason)"
    }

    fun checkForNativeCrashes(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastChecked = prefs.getLong(KEY_LAST_CHECKED, 0L)

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            var latestTimestamp = lastChecked

            val newReasons = reasons.filter { it.timestamp > lastChecked }
            newReasons.forEach { if (it.timestamp > latestTimestamp) latestTimestamp = it.timestamp }

            for (reason in newReasons.filterNot { it.reason in benignReasons }) {
                val description = reason.description ?: "unknown"
                val status = String.format(java.util.Locale.US, "0x%08X", reason.status)
                val reasonStr = reasonName(reason.reason)

                // Tenta extrair o trace de falha (nativo ou ANR) via InputStream.
                val trace = try {
                    reason.traceInputStream?.bufferedReader()?.use { it.readText() } ?: "(no trace)"
                } catch (_: Exception) { "(error reading trace)" }

                val extraInfo = mapOf(
                    "reason" to reasonStr,
                    "status" to status,
                    "pid" to reason.pid.toString(),
                    "processName" to (reason.processName ?: "?"),
                    "description" to description,
                    "trace_begin" to trace.take(TRACE_MAX_CHARS)
                )

                CrashLogWriter.log(
                    throwable = null,
                    contextTag = "ABNORMAL_EXIT_DETECTED",
                    message = "$reasonStr detectado via ApplicationExitInfo: $description",
                    extraInfo = extraInfo
                )

                Log.w(TAG, "Saída anormal detectada: $reasonStr, pid=${reason.pid}, desc=$description")
            }

            prefs.edit().putLong(KEY_LAST_CHECKED, latestTimestamp).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar native crashes", e)
        }
    }
}
