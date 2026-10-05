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

/**
 * Cliente que delega a síntese do motor Kokoro (Sherpa-ONNX) ao processo isolado
 * [SherpaSynthService] (`:sherpa`). Espelho do [SupertonicProcessClient] (RN-2 do
 * PLANO_MELHORIAS_V6).
 *
 * Mantém uma única conexão viva e a reutiliza entre chamadas (a sessão ORT do nativo
 * permanece inicializada no processo filho). Se o processo filho abortar (crash nativo do
 * ONNX Runtime), a chamada IPC lança DeadObjectException — tratada como falha limpa, sem
 * derrubar o app principal. A próxima chamada reconecta a um processo novo.
 */
object SherpaProcessClient {
    private const val TAG = "SherpaProcClient"
    private const val BIND_TIMEOUT_MS = 15_000L

    // Até 2 sínteses concorrentes no :sherpa: Session.Run() do ONNX Runtime é documentado
    // thread-safe para chamadas concorrentes na MESMA sessão (pesos compartilhados, sem
    // duplicar memória) — validado empiricamente em device real (investigação de fluidez da
    // guiada kokoro, 2026-09-04): 2 chamadas concorrentes vs sequenciais, mesmo texto, sem
    // crash, WAV correto (duração ~idêntica), ~1,66x de speedup de parede reproduzido em 2
    // rodadas independentes (ver HISTORICO_MELHORIAS.md, ciclo V6). O bind/reconexão em si
    // segue serializado por [bindMutex] — só a chamada AIDL de síntese em si roda concorrente.
    //
    // Duas instâncias FIXAS (nunca mutadas/recriadas — um Semaphore não suporta trocar o
    // número de permits em uso sem risco de vazar permit) — a escolhida por chamada depende
    // de AppPrefs.paralelismoKokoroReduzido (Ajustes › Buffering da leitura guiada), lido a
    // cada synthesize(). 1 é o único outro valor oferecido ao usuário porque é o único, além
    // do padrão 2, sem risco de concorrência (execução puramente sequencial); nada acima de
    // 2 é exposto por nunca ter sido validado em device.
    private val synthSemaphoreDuplo = Semaphore(2)
    private val synthSemaphoreUnico = Semaphore(1)
    private val bindMutex = Mutex()

    @Volatile private var service: ISherpaSynthService? = null
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
            Log.e(TAG, "Could not bind to isolated Sherpa process")
            return "não foi possível conectar ao processo isolado (:sherpa)"
        }
        val reduzido = com.jonjonesbr.audiobookgen.data.AppPrefs(appContext).paralelismoKokoroReduzido
        val semaphore = if (reduzido) synthSemaphoreUnico else synthSemaphoreDuplo
        return try {
            semaphore.withPermit {
                withContext(Dispatchers.IO) {
                    svc.synthesize(text, voiceId, outputFile.absolutePath).ifBlank { null }
                }
            }
        } catch (e: DeadObjectException) {
            Log.e(TAG, "Isolated Sherpa process died during synthesis (contained — app safe)", e)
            service = null
            "o processo isolado morreu durante a síntese (crash nativo — provavelmente falta de memória)"
        } catch (e: RemoteException) {
            Log.e(TAG, "Sherpa IPC error: ${e.message}", e)
            "erro de IPC com o processo isolado: ${e.message}"
        }
    }

    // Serializado à parte da síntese em si: com até 2 chamadas concorrentes, duas poderiam
    // ver `service == null` ao mesmo tempo (ex.: as 2 primeiras orações do primeiro parágrafo
    // guiado) e disparar bindService() em duplicidade sem essa exclusão. Dupla checagem:
    // reconfirma `service` depois de obter o lock, caso outra chamada já tenha conectado
    // enquanto esta esperava.
    private suspend fun ensureBound(appContext: Context): ISherpaSynthService? {
        service?.let { return it }
        return bindMutex.withLock {
            service?.let { return@withLock it }
            withTimeoutOrNull(BIND_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            val s = ISherpaSynthService.Stub.asInterface(binder)
                            service = s
                            if (cont.isActive) cont.resume(s)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            // Processo filho morreu — invalida para forçar rebind na próxima chamada.
                            service = null
                        }
                    }
                    connection = conn
                    val intent = Intent(appContext, SherpaSynthService::class.java)
                    // BIND_ABOVE_CLIENT: pede ao sistema pra tratar a importância/oom_adj do
                    // processo :sherpa como pelo menos igual à do processo chamador (que
                    // normalmente está com foreground service ativo tocando áudio). Achado real
                    // em device (2026-09-16, via dumpsys/logcat): o gerenciador de processos em
                    // segundo plano do MIUI ("SmartPower", separado da otimização de bateria
                    // padrão do Android — confirmado com o app JÁ isento da whitelist padrão)
                    // classifica :sherpa como "invisible" assim que ele nasce (sem UI própria) e
                    // congela/mata o processo NO MEIO de uma síntese em andamento — não é falta
                    // de memória (ApplicationExitInfo mostrava reason=EXIT_SELF status=255, sem
                    // trace nativo nenhum; o binder falhava com "sent binder code 1 ... to frozen
                    // apps" ANTES da morte). BIND_ABOVE_CLIENT é o sinal padrão do Android pra
                    // esse exato cenário (processo auxiliar sem UI que precisa da prioridade do
                    // cliente que o está usando).
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
