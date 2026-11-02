package com.jonjonesbr.audiobookgen.domain

import java.io.File

/** Etiquetas que o Python grava no MP3: título (TIT2), autor (TPE1, vazio = "LylyReader") e álbum (TALB). */
data class EtiquetasDoMp3(val titulo: String, val autor: String?, val album: String)

/**
 * Título e autor do livro de uma conversão, para o cartão "Gerando agora", as notificações e as etiquetas do MP3.
 * Puro (sem Android): quem lê a Biblioteca passa os dados prontos para [de].
 *
 * [titulo] é o nome legível do audiobook (o do livro, com o sufixo do trecho ou capítulo; ver [de]).
 * [tituloDoLivro] é o título do livro, sem sufixo: vira o álbum (TALB). [autor] vazio vira null.
 */
data class MetadadosDaConversao(val titulo: String?, val tituloDoLivro: String?, val autor: String?) {

    /** Etiquetas para o Python. Título nunca vazio: usa [tituloDeReserva] se faltar. */
    fun etiquetas(tituloDeReserva: String): EtiquetasDoMp3 {
        val tituloFinal = titulo ?: tituloDeReserva
        return EtiquetasDoMp3(titulo = tituloFinal, autor = autor, album = tituloDoLivro ?: tituloFinal)
    }

    companion object {
        private val ESPACOS = Regex("\\s+")

        /**
         * Monta os metadados de uma conversão. Com o livro na Biblioteca ([tituloDoLivro] não vazio) e a saída
         * começando pelo nome do arquivo do livro, o título é o do livro mais o sufixo da saída
         * ("Um Apólogo - trecho").
         * Nos outros casos (livro fora da Biblioteca, nome que não bate, título vazio), vale o nome do arquivo
         * de saída.
         */
        fun de(
            tituloDoLivro: String?,
            autorDoLivro: String?,
            caminhoDoLivro: String?,
            caminhoSaida: String?
        ): MetadadosDaConversao {
            val livroT = tituloDoLivro?.trim()?.takeIf { it.isNotEmpty() }
            val autor = autorDoLivro?.trim()?.takeIf { it.isNotEmpty() }
            val titulo = tituloComSufixo(livroT, caminhoDoLivro, caminhoSaida)
                ?: TextosDeNotificacao.nomeDaConversao(caminhoSaida)
            return MetadadosDaConversao(titulo = titulo, tituloDoLivro = livroT, autor = autor)
        }

        /**
         * Título do livro seguido do que a saída acrescenta ao nome do arquivo (" - trecho", " - Capítulo 1").
         * Null se não houver título do livro, ou se a saída não começar pelo nome do arquivo do livro numa
         * fronteira (ex.: "Um_Ap_logo2" não é "Um_Ap_logo" mais sufixo).
         */
        private fun tituloComSufixo(livroT: String?, caminhoDoLivro: String?, caminhoSaida: String?): String? {
            val livroBase = caminhoDoLivro?.let { File(it).nameWithoutExtension }.orEmpty()
            val base = caminhoSaida?.let { File(it).nameWithoutExtension }.orEmpty()
            val resto = base.removePrefix(livroBase)
            val casa = livroBase.isNotEmpty() && base.startsWith(livroBase) &&
                (resto.isEmpty() || resto.startsWith(" "))
            return livroT?.takeIf { casa }?.let { (it + resto.replace('_', ' ')).replace(ESPACOS, " ").trim() }
        }
    }
}
