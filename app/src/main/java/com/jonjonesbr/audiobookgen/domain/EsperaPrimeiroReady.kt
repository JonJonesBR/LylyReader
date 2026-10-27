package com.jonjonesbr.audiobookgen.domain

/**
 * Espera do primeiro STATE_READY de cada item tocado pelo AudioPlayerService. Só nesse READY roda o
 * bookkeeping de início (duração, capítulos, foreground, tick).
 *
 * Um erro antes do READY não pode deixar a espera travada em true, senão o próximo item nunca
 * inicializa. A nova tentativa (retomada após erro) re-arma a espera só quando o erro veio antes do READY.
 * Sem ExoPlayer aqui dentro, para ser testada na JVM.
 */
class EsperaPrimeiroReady {
    /** Verdadeiro entre começar um item e o primeiro READY dele. */
    var aguardando: Boolean = false
        private set

    /** Verdadeiro quando o último erro aconteceu antes do primeiro READY. */
    var falhaAntesDoPrimeiroReady: Boolean = false
        private set

    /** Item novo ou livro concluído tocado de novo: espera o primeiro READY. */
    fun comecar() {
        aguardando = true
    }

    /** Erro do player: deixa de esperar e lembra se a falha veio antes do primeiro READY. */
    fun aoErro() {
        falhaAntesDoPrimeiroReady = aguardando
        aguardando = false
    }

    /**
     * STATE_READY: devolve true só no primeiro READY da espera (quem chama roda o bookkeeping de início).
     * READYs seguintes devolvem false.
     */
    fun aoFicarReady(): Boolean {
        if (!aguardando) return false
        aguardando = false
        return true
    }

    /** Nova tentativa após erro: se o erro veio antes do primeiro READY, a espera volta. */
    fun aoRetomar() {
        if (falhaAntesDoPrimeiroReady) aguardando = true
    }
}
