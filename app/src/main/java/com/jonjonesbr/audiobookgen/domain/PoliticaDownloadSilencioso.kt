package com.jonjonesbr.audiobookgen.domain

/**
 * Download offline silencioso do primeiro acesso ("Escolha seu livro"): quando baixar o pacote de voz
 * recomendado em segundo plano. Só inicia uma vez, fora de rede tarifada e com espaço de sobra
 * (mesma regra de [espacoSuficiente] usada na Central). Nos demais casos não faz nada e não marca a
 * tentativa, então tenta de novo na próxima abertura da tela.
 */
object PoliticaDownloadSilencioso {

    /** Pacote do catálogo baixado sem pedir: Supertonic (`VoiceCatalog`, id "supertonic-m1"). */
    const val PACOTE_RECOMENDADO = "supertonic-m1"

    /**
     * [redeTarifada] deve ser true também quando não há internet (o download não teria como começar).
     */
    fun deveIniciar(jaIniciado: Boolean, redeTarifada: Boolean, espacoLivreBytes: Long, tamanhoBytes: Long): Boolean =
        !jaIniciado && !redeTarifada && espacoSuficiente(espacoLivreBytes, tamanhoBytes)
}
