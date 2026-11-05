package com.jonjonesbr.audiobookgen.tts

/** Tipo do primeiro plano do motor: dataSync (limite de 6 h por 24 h no Android 15+) ou mediaPlayback. */
internal enum class TipoPrimeiroPlanoMotor { DADOS, REPRODUCAO }

// Android 15 (API 35): a partir dele o dataSync tem limite de 6 h por 24 h.
private const val SDK_LIMITE_DATA_SYNC = 35

/**
 * Escolhe o tipo do primeiro plano do motor (rb-37). A leitura guiada usa mediaPlayback no
 * Android 15 ou mais novo, para não gastar o limite do dataSync; o resto segue com dataSync.
 * Função pura (sem Build): quem chama passa o SDK.
 */
internal fun tipoDoPrimeiroPlanoMotor(sdk: Int, paraReproducao: Boolean): TipoPrimeiroPlanoMotor =
    if (paraReproducao && sdk >= SDK_LIMITE_DATA_SYNC) {
        TipoPrimeiroPlanoMotor.REPRODUCAO
    } else {
        TipoPrimeiroPlanoMotor.DADOS
    }

/**
 * Regra pura do primeiro plano dos motores de voz (rb-36): quando entrar e quando sair.
 *
 * Sem Android e sem sincronização própria: quem chama segura a trava de [PrimeiroPlanoDoMotor].
 * A "ficha" devolvida por [aoTerminar] identifica a espera; um começo novo invalida a espera
 * antiga, então a saída agendada não derruba uma síntese que acabou de começar.
 *
 * Troca de tipo (rb-37): um começo com outro tipo pede novo startForeground. Falha na troca não
 * muda o estado: o serviço segue no tipo anterior (que continua valendo) e a próxima síntese tenta
 * de novo. Só [confirmarPrimeiroPlano] com `ok = true` grava o tipo.
 */
internal class CicloPrimeiroPlanoMotor {

    var emVoo: Int = 0
        private set

    /** Tipo do primeiro plano agora; null = fora do primeiro plano. */
    var tipoAtual: TipoPrimeiroPlanoMotor? = null
        private set

    val emPrimeiroPlano: Boolean get() = tipoAtual != null

    // Invalida as saídas agendadas: cada começo e cada saída forçada avança a geração.
    private var geracao: Long = 0L

    /**
     * Início de síntese: emVoo++ e geração++. true = chamar startForeground com [tipo] (ainda não
     * está nele: fora do primeiro plano ou com outro tipo).
     * Sem o requisito de emVoo == 1: se o startForeground falhou com outra síntese em voo, a
     * próxima tenta de novo, em vez de seguir sem primeiro plano até o lote inteiro acabar.
     */
    fun aoComecar(tipo: TipoPrimeiroPlanoMotor = TipoPrimeiroPlanoMotor.DADOS): Boolean {
        emVoo++
        geracao++
        return tipoAtual != tipo
    }

    /**
     * Resultado do startForeground (false = falhou). Com sucesso, grava o [tipo] atual. Com falha,
     * não muda nada: fora do primeiro plano continua fora, e em outro tipo continua no anterior.
     */
    fun confirmarPrimeiroPlano(ok: Boolean, tipo: TipoPrimeiroPlanoMotor = TipoPrimeiroPlanoMotor.DADOS) {
        if (ok) tipoAtual = tipo
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
        tipoAtual = null
        return true
    }

    /** onTimeout/onDestroy: o sistema já tirou (ou vai tirar) o processo do primeiro plano. */
    fun aoSairForcado() {
        tipoAtual = null
        geracao++
    }
}
