package com.jonjonesbr.audiobookgen.tts

import com.jonjonesbr.audiobookgen.tts.ISupertonicSynthService
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

/**
 * Cliente que delega a síntese de vozes ONNX nativas (Supertonic) ao processo
 * isolado [SupertonicSynthService].
 *
 * Mantém uma única conexão viva e a reutiliza entre chamadas (o nativo permanece
 * inicializado no processo filho). Se o processo filho abortar (crash nativo do ONNX
 * Runtime), a chamada IPC lança DeadObjectException — tratada como falha limpa, sem
 * derrubar o app principal. A próxima chamada reconecta a um processo novo.
 */
object SupertonicProcessClient {
    private const val TAG = "SupertonicProcClient"
    private const val BIND_TIMEOUT_MS = 15_000L

    private val bindMutex = Mutex()

    // Duas instâncias FIXAS (nunca mutadas/recriadas — um Semaphore não suporta trocar o
    // número de permits em uso sem risco de vazar permit; mesma técnica do
    // SherpaProcessClient) — a escolhida por chamada depende de
    // AppPrefs.paralelismoSupertonicoAumentado (Ajustes › Buffering da leitura guiada).
    // Padrão CONTINUA 1 (sequencial, comportamento histórico): ao contrário do sherpa-onnx/
    // kokoro (2 concorrentes validado em device, ~1,66x de speedup medido), a thread-safety
    // do backend nativo do Supertonic para chamadas concorrentes NUNCA foi validada — 2 aqui
    // é EXPERIMENTAL, oferecido só porque o usuário pediu explicitamente pra poder testar.
    private val synthSemaphoreUnico = Semaphore(1)
    private val synthSemaphoreDuplo = Semaphore(2)

    @Volatile private var service: ISupertonicSynthService? = null
    private var connection: ServiceConnection? = null

    /** Sintetiza no processo isolado. Retorna `null` em sucesso, ou o motivo da falha. */
    suspend fun synthesize(
        context: Context,
        text: String,
        voiceId: String,
        outputFile: File
    ): String? {
        val appContext = context.applicationContext
        val svc = ensureBound(appContext) ?: run {
            Log.e(TAG, "Could not bind to isolated Supertonic process")
            return "não foi possível conectar ao processo isolado (:supertonic)"
        }
        val aumentado = com.jonjonesbr.audiobookgen.data.AppPrefs(appContext).paralelismoSupertonicoAumentado
        val semaphore = if (aumentado) synthSemaphoreDuplo else synthSemaphoreUnico
        return try {
            semaphore.withPermit {
                withContext(Dispatchers.IO) {
                    svc.synthesize(text, voiceId, outputFile.absolutePath).ifBlank { null }
                }
            }
        } catch (e: DeadObjectException) {
            Log.e(TAG, "Isolated Supertonic process died during synthesis (contained — app safe)", e)
            service = null
            "o processo isolado morreu durante a síntese (crash nativo — provavelmente falta de memória)"
        } catch (e: RemoteException) {
            Log.e(TAG, "Supertonic IPC error: ${e.message}", e)
            "erro de IPC com o processo isolado: ${e.message}"
        }
    }

    // Serializado à parte da síntese em si (mesmo motivo do SherpaProcessClient): com até 2
    // chamadas concorrentes, duas poderiam ver `service == null` ao mesmo tempo e disparar
    // bindService() em duplicidade sem essa exclusão.
    private suspend fun ensureBound(appContext: Context): ISupertonicSynthService? {
        service?.let { return it }
        return bindMutex.withLock {
            service?.let { return@withLock it }
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = ISupertonicSynthService.Stub.asInterface(binder)
                            service = s
                            if (cont.isActive) cont.resume(s)
                        }
                        override fun onServiceDisconnected(name: ComponentName?) {
                            // Processo filho morreu — invalida para forçar rebind na próxima chamada.
                            service = null
                        }
                    }
                    connection = conn
                    val intent = Intent(appContext, SupertonicSynthService::class.java)
                    // BIND_ABOVE_CLIENT: mesmo tratamento do SherpaProcessClient (sinal padrão
                    // do Android pra tratar a prioridade do processo auxiliar como pelo menos
                    // igual à do cliente) — sozinho NÃO resolveu o congelamento do MIUI no
                    // Sherpa (o foreground service em SupertonicSynthService é o que resolve
                    // de verdade), mas mantido pelo mesmo motivo: sinal correto pro sistema,
                    // sem custo adicional.
                    val bound = appContext.bindService(
                        intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_ABOVE_CLIENT
                    )
                    if (!bound) {
                        connection = null
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }
    }
}
