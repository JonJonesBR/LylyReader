package com.jonjonesbr.audiobookgen.domain

import java.io.File

/**
 * Textos puros das notificações (sem Android): nome do audiobook em geração ou pronto, percentual do título e
 * autor mostrado no player. Assim as regras ficam testáveis em JUnit.
 */
object TextosDeNotificacao {

    /**
     * Nome legível do audiobook que está sendo gerado (ou ficou pronto), pelo arquivo de saída. Usa o mesmo critério
     * do cartão da aba Audiobooks. Devolve null sem caminho, para o chamador escolher um título de reserva.
     */
    fun nomeDaConversao(caminhoSaida: String?): String? {
        if (caminhoSaida.isNullOrEmpty()) return null
        return NomeDoAudiobook.paraMostrar(null, null, File(caminhoSaida).name).takeIf { it.isNotEmpty() }
    }

    /** Percentual limitado a 0..100, para o título da notificação nunca mostrar valor fora da faixa. */
    fun pctNoTitulo(pct: Int): Int = pct.coerceIn(0, 100)

    /**
     * Autor mostrado no player: o do livro vinculado tem prioridade; senão a etiqueta do MP3, sem o artista padrão
     * do app ([NomeDoAudiobook.autorDaEtiqueta]).
     */
    fun autorNoPlayer(autorDoLivro: String?, artistaDaEtiqueta: String?): String? =
        autorDoLivro?.trim()?.takeIf { it.isNotEmpty() } ?: NomeDoAudiobook.autorDaEtiqueta(artistaDaEtiqueta)
}
