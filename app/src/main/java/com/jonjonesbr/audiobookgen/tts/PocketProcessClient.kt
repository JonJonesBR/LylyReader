package com.jonjonesbr.audiobookgen.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** Serial IPC client for the memory-heavy native engine in process :pocket. */
object PocketProcessClient {
    private const val TAG = "PocketProcessClient"
    private const val BIND_TIMEOUT_MS = 15_000L
    private const val LIMITE_LENTO_MS = 30_000L
    private val bindMutex = Mutex()
    private val synthSemaphore = Semaphore(1)

    @Volatile private var service: IPocketSynthService? = null
    private var connection: ServiceConnection? = null

    suspend fun synthesize(context: Context, text: String, voiceId: String, output: File): String? {
        val appContext = context.applicationContext
        val passos = com.jonjonesbr.audiobookgen.data.AppPrefs(appContext).pocketPassos
        val inicio = System.currentTimeMillis()
        val svc = ensureBound(appContext) ?: return "não foi possível conectar ao processo Pocket"
        return try {
            synthSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    svc.synthesize(text, voiceId, output.absolutePath, passos).ifBlank { null }
                }
            }.also {
                // Registro para diagnosticar travas intermitentes: síntese normal leva poucos segundos.
                val duracao = System.currentTimeMillis() - inicio
                if (duracao >= LIMITE_LENTO_MS) {
                    Log.w(TAG, "Síntese Pocket lenta: ${duracao} ms (${text.length} chars, voz=$voiceId)")
                    com.jonjonesbr.audiobookgen.util.CrashLogWriter.log(
                        null, "pocket_lento", "duração=${duracao} ms, chars=${text.length}, voz=$voiceId, passos=$passos"
                    )
                }
            }
        } catch (error: DeadObjectException) {
            Log.e(TAG, "Pocket process died during synthesis; the app process remains alive", error)
            service = null
            "o processo isolado do Pocket TTS foi encerrado durante a síntese"
        } catch (error: RemoteException) {
            Log.e(TAG, "Pocket IPC failed: ${error.message}", error)
            "erro de IPC com o serviço Pocket: ${error.message}"
        }
    }

    private suspend fun ensureBound(context: Context): IPocketSynthService? {
        service?.let { return it }
        return bindMutex.withLock {
            service?.let { return@withLock it }
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val connected = IPocketSynthService.Stub.asInterface(binder)
                            service = connected
                            if (continuation.isActive) continuation.resume(connected)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    }
                    connection = conn
                    val bound = context.bindService(
                        Intent(context, PocketSynthService::class.java),
                        conn,
                        Context.BIND_AUTO_CREATE or Context.BIND_ABOVE_CLIENT
                    )
                    if (!bound) {
                        connection = null
                        if (continuation.isActive) continuation.resume(null)
                    }
                    continuation.invokeOnCancellation {
                        runCatching { context.unbindService(conn) }
                        if (connection === conn) connection = null
                    }
                }
            }
        }
    }
}
