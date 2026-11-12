package com.jonjonesbr.audiobookgen.tts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.jonjonesbr.audiobookgen.service.GuidedReadingService
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

/** Cliente IPC serial do motor nativo Paradee, que roda no processo :paradee. */
object ParadeeProcessClient {
    /** Mensagem devolvida quando o processo :paradee morre no meio da síntese (o motor de voz tenta de novo). */
    const val MENSAGEM_PROCESSO_ENCERRADO = "o processo isolado do Paradee foi encerrado durante a síntese"

    private const val TAG = "ParadeeProcessClient"
    private const val BIND_TIMEOUT_MS = 15_000L
    private const val LIMITE_LENTO_MS = 30_000L
    private val bindMutex = Mutex()
    private val synthSemaphore = Semaphore(1)

    @Volatile private var service: IParadeeSynthService? = null
    private var connection: ServiceConnection? = null

    /** Devolve null em caso de sucesso, ou a mensagem de erro. */
    suspend fun synthesize(context: Context, text: String, voiceId: String, output: File, speed: Float): String? {
        val appContext = context.applicationContext
        val inicio = System.currentTimeMillis()
        val svc = ensureBound(appContext) ?: return "não foi possível conectar ao processo Paradee"
        return try {
            synthSemaphore.withPermit {
                withContext(Dispatchers.IO) {
                    // Lido depois do semáforo, logo antes do IPC, para não usar um valor velho da espera.
                    val paraReproducao = GuidedReadingService.ativo
                    svc.synthesize(text, voiceId, output.absolutePath, speed, paraReproducao)
                        .ifBlank { null }
                }
            }.also {
                // Registro para diagnosticar travas intermitentes: síntese normal leva poucos segundos.
                val duracao = System.currentTimeMillis() - inicio
                if (duracao >= LIMITE_LENTO_MS) {
                    Log.w(TAG, "Síntese Paradee lenta: ${duracao} ms (${text.length} chars, voz=$voiceId)")
                    com.jonjonesbr.audiobookgen.util.CrashLogWriter.log(
                        null, "paradee_lento", "duração=${duracao} ms, chars=${text.length}, voz=$voiceId"
                    )
                }
            }
        } catch (error: DeadObjectException) {
            Log.e(TAG, "Paradee process died during synthesis; the app process remains alive", error)
            service = null
            MENSAGEM_PROCESSO_ENCERRADO
        } catch (error: RemoteException) {
            Log.e(TAG, "Paradee IPC failed: ${error.message}", error)
            "erro de IPC com o serviço Paradee: ${error.message}"
        }
    }

    private suspend fun ensureBound(context: Context): IParadeeSynthService? {
        service?.let { return it }
        return bindMutex.withLock {
            service?.let { return@withLock it }
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val connected = IParadeeSynthService.Stub.asInterface(binder)
                            service = connected
                            if (continuation.isActive) continuation.resume(connected)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    }
                    connection = conn
                    val bound = context.bindService(
                        Intent(context, ParadeeSynthService::class.java),
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
