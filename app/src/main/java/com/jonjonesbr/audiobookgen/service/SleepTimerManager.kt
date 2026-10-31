package com.jonjonesbr.audiobookgen.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Timer de soneca (sleep timer) central e único, independente da Activity.
 *
 * Mantém o tempo restante como [remainingMs] (null = inativo) e emite [finished] ao terminar.
 * A contagem roda numa coroutine própria → sobrevive ao ciclo de vida da Activity (o usuário
 * pode bloquear a tela / sair do app). Os serviços de player ([AudioPlayerService],
 * [GuidedReadingService]) observam [remainingMs] para mostrar a contagem na notificação e
 * [finished] para pausar a reprodução; a UI observa para exibir o tempo restante.
 *
 * Há também a soneca "fim do capítulo" ([armarFimDoCapitulo]): sem contagem, ela dispara quando o
 * último parágrafo do capítulo acaba (ver GuidedPlayerManager). Só existe na leitura guiada.
 */
object SleepTimerManager {
    /** Duração (ms) do fade-out de volume nos últimos instantes da contagem. */
    const val FADE_DURATION_MS = 30_000L
    private const val MS_POR_MINUTO = 60_000L

    private const val MS_POR_SEGUNDO = 1_000L
    private const val SEGUNDOS_POR_HORA = 3600
    private const val SEGUNDOS_POR_MINUTO = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs.asStateFlow()

    // Duração TOTAL (minutos) da soneca vigente, ao contrário de [remainingMs] que decresce —
    // usado pelo botão de soneca da notificação pra saber qual preset ciclar a partir de qual.
    private val _armedMinutes = MutableStateFlow<Int?>(null)
    val armedMinutes: StateFlow<Int?> = _armedMinutes.asStateFlow()

    // Fator de volume (0f–1f) a aplicar no player durante o fade-out; 1f fora da janela de
    // fade ou quando a soneca está inativa. Os players (AudioPlayerService/GuidedPlayerManager)
    // observam para reduzir o volume gradualmente em vez de cortar o áudio de uma vez.
    private val _volumeFactor = MutableStateFlow(1f)
    val volumeFactor: StateFlow<Float> = _volumeFactor.asStateFlow()

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finished: SharedFlow<Unit> = _finished

    // Soneca "fim do capítulo" armada (sem contagem). Vira false ao disparar, ao começar uma contagem e ao cancelar.
    private val _fimDoCapitulo = MutableStateFlow(false)
    val fimDoCapitulo: StateFlow<Boolean> = _fimDoCapitulo.asStateFlow()

    val isActive: Boolean get() = _remainingMs.value != null || _fimDoCapitulo.value

    // Token da sessão de leitura guiada (GuidedPlayerManager.sessionToken) vigente no momento
    // em que a soneca foi ligada — null quando a soneca não está atrelada a uma sessão de
    // leitura guiada (ex.: ligada a partir do player de áudio convertido). Vale tanto para a
    // contagem quanto para a soneca "fim do capítulo". Mantido até o próximo start()/cancel()
    // (não é limpo ao expirar nem ao disparar "fim do capítulo") para que o coletor de [finished]
    // ainda consiga comparar contra a sessão vigente naquele instante.
    @Volatile var armedToken: Long? = null
        private set

    /** Inicia (ou reinicia) a contagem. [durationMs] <= 0 apenas cancela.
     *  [sessionToken], quando informado, trava a soneca à sessão de leitura guiada vigente
     *  no momento do start() — ver [armedToken]. Uma contagem substitui a soneca "fim do capítulo". */
    fun start(durationMs: Long, sessionToken: Long? = null) {
        job?.cancel()
        _fimDoCapitulo.value = false
        armedToken = sessionToken
        if (durationMs <= 0) {
            _remainingMs.value = null
            _armedMinutes.value = null
            return
        }
        _armedMinutes.value = (durationMs / MS_POR_MINUTO).toInt()
        job = scope.launch {
            var remaining = durationMs
            _remainingMs.value = remaining
            _volumeFactor.value = calcularFatorVolumeFade(remaining, durationMs)
            while (remaining > 0) {
                delay(MS_POR_SEGUNDO)
                remaining -= MS_POR_SEGUNDO
                _remainingMs.value = remaining.coerceAtLeast(0)
                _volumeFactor.value = calcularFatorVolumeFade(remaining, durationMs)
            }
            _remainingMs.value = null
            _armedMinutes.value = null
            // Restaura o volume cheio para a próxima sessão de playback.
            _volumeFactor.value = 1f
            _finished.emit(Unit)
        }
    }

    /**
     * Arma a soneca "fim do capítulo" para a sessão [sessionToken]: sem contagem, sem fade (o volume fica cheio).
     * Substitui uma contagem em curso.
     */
    fun armarFimDoCapitulo(sessionToken: Long) {
        job?.cancel()
        job = null
        armedToken = sessionToken
        _remainingMs.value = null
        _armedMinutes.value = null
        _volumeFactor.value = 1f
        _fimDoCapitulo.value = true
    }

    /**
     * Dispara a soneca "fim do capítulo" para a sessão [sessionToken]: emite [finished] e desarma o modo. Mantém
     * [armedToken], como na expiração por tempo, para os coletores de [finished] comparem com ele. Devolve false
     * (sem emitir nada) se a soneca não estiver armada para essa sessão.
     */
    fun dispararFimDoCapitulo(sessionToken: Long): Boolean {
        if (!_fimDoCapitulo.value || armedToken != sessionToken) return false
        _fimDoCapitulo.value = false
        _finished.tryEmit(Unit)
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = null
        _armedMinutes.value = null
        _volumeFactor.value = 1f
        _fimDoCapitulo.value = false
        armedToken = null
    }

    /** Formata o restante como "mm:ss" (ou "h:mm:ss"). */
    fun formatRemaining(ms: Long): String {
        val totalSec = (ms / MS_POR_SEGUNDO).coerceAtLeast(0)
        val h = totalSec / SEGUNDOS_POR_HORA
        val m = (totalSec % SEGUNDOS_POR_HORA) / SEGUNDOS_POR_MINUTO
        val s = totalSec % SEGUNDOS_POR_MINUTO
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/**
 * Fator de volume (0f–1f) do fade-out, dado o tempo restante ([remainingMs]) e a duração
 * total da soneca ([totalMs]). A janela de fade é limitada pela duração total — uma soneca
 * mais curta que [fadeDurationMs] esmaece por inteiro em vez de já começar baixa.
 */
fun calcularFatorVolumeFade(
    remainingMs: Long,
    totalMs: Long,
    fadeDurationMs: Long = SleepTimerManager.FADE_DURATION_MS
): Float {
    val janela = minOf(fadeDurationMs, totalMs)
    if (janela <= 0) return 1f
    return (remainingMs.toFloat() / janela).coerceIn(0f, 1f)
}

/** Modo da soneca em vigor: contagem regressiva, fim do capítulo, ou desligada. */
enum class ModoSoneca { DESLIGADA, CONTAGEM, FIM_DO_CAPITULO }

/**
 * Modo da soneca dado o tempo restante da contagem ([restanteMs], null = sem contagem) e se a soneca
 * "fim do capítulo" está armada. Os dois nunca valem juntos; o fim do capítulo tem precedência.
 */
fun modoDaSoneca(restanteMs: Long?, fimDoCapitulo: Boolean): ModoSoneca = when {
    fimDoCapitulo -> ModoSoneca.FIM_DO_CAPITULO
    restanteMs != null -> ModoSoneca.CONTAGEM
    else -> ModoSoneca.DESLIGADA
}

/**
 * A soneca "fim do capítulo" deve pausar agora? Vale quando ela está armada para a sessão [tokenDaSessao] e o
 * parágrafo que acabou de tocar é o último do capítulo ([capituloAtual] diferente de [capituloSeguinte]), ou o
 * último do livro ([capituloSeguinte] null). Sem capítulo atual, não dispara.
 */
fun sonecaDeCapituloDispara(
    armadaNoFimDoCapitulo: Boolean,
    tokenArmado: Long?,
    tokenDaSessao: Long,
    capituloAtual: Int?,
    capituloSeguinte: Int?
): Boolean = armadaNoFimDoCapitulo &&
    tokenArmado == tokenDaSessao &&
    capituloAtual != null &&
    (capituloSeguinte == null || capituloSeguinte != capituloAtual)
