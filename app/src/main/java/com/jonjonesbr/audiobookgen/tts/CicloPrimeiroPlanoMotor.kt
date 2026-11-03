package com.jonjonesbr.audiobookgen.tts

/**
 * Regra pura do primeiro plano dos motores de voz (rb-36): quando entrar e quando sair.
 *
 * Sem Android e sem sincronização própria: quem chama segura a trava de [PrimeiroPlanoDoMotor].
 * A "ficha" devolvida por [aoTerminar] identifica a espera; um começo novo invalida a espera
 * antiga, então a saída agendada não derruba uma síntese que acabou de começar.
 */
internal class CicloPrimeiroPlanoMotor {

    var emVoo: Int = 0
        private set

    var emPrimeiroPlano: Boolean = false
        private set

    // Invalida as saídas agendadas: cada começo e cada saída forçada avança a geração.
    private var geracao: Long = 0L

    /**
     * Início de síntese: emVoo++ e geração++. true = chamar startForeground (ainda não está).
     * Sem o requisito de emVoo == 1: se o startForeground falhou com outra síntese em voo, a
     * próxima tenta de novo, em vez de seguir sem primeiro plano até o lote inteiro acabar.
     */
    fun aoComecar(): Boolean {
        emVoo++
        geracao++
        return !emPrimeiroPlano
    }

    /** Resultado do startForeground (false = falhou; a síntese segue sem primeiro plano). */
    fun confirmarPrimeiroPlano(ok: Boolean) {
        emPrimeiroPlano = ok
    }

    /**
     * Fim de síntese: emVoo = max(0, emVoo - 1). Se chegou a zero e está em primeiro plano,
     * devolve a ficha para agendar a saída; senão, null.
     */
    fun aoTerminar(): Long? {
        emVoo = maxOf(0, emVoo - 1)
        return if (emVoo == 0 && emPrimeiroPlano) geracao else null
    }

    /** A espera venceu: true só se a ficha ainda é a atual, nada está em voo e há primeiro plano. */
    fun aoVencerEspera(ficha: Long): Boolean {
        if (ficha != geracao || emVoo != 0 || !emPrimeiroPlano) return false
        emPrimeiroPlano = false
        return true
    }

    /** onTimeout/onDestroy: o sistema já tirou (ou vai tirar) o processo do primeiro plano. */
    fun aoSairForcado() {
        emPrimeiroPlano = false
        geracao++
    }
}
