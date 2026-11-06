package com.jonjonesbr.audiobookgen.util

import com.jonjonesbr.audiobookgen.domain.semExtensaoDeAudio

/**
 * O que o player recebeu para tocar um áudio. [chave] é o que quem abriu mandou: a uri do cartão (Biblioteca),
 * o caminho do arquivo interno (tela do livro, leitor, mini-player) ou uma uri "file:".
 *
 * A conversão grava o arquivo em filesDir, e o app copia esse arquivo para Downloads ou para a pasta escolhida.
 * A Biblioteca lista a cópia, não o interno. [copiaExportada] é a uri dessa cópia, quando o app registrou
 * (CopiaExportadaStore). [pastaInterna] é o filesDir: só para conversões antigas, sem registro, o interno
 * casa com o cartão pelo nome.
 */
data class ChaveDoPlayer(
    val chave: String?,
    val copiaExportada: String? = null,
    val pastaInterna: String? = null
)

/** O que o cartão da Biblioteca sabe do próprio áudio. [caminhoNoDisco] vem do MediaStore (DATA), quando informa. */
data class IdentidadeDoAudiobook(
    val uri: String,
    val caminho: String?,
    val caminhoNoDisco: String?,
    val nomeArquivo: String?
)

/** Como o player casa com o cartão: [DIRETO] (uri, caminho ou cópia), [POR_NOME] (conversão antiga) ou [NENHUM]. */
enum class Casamento { DIRETO, POR_NOME, NENHUM }

/**
 * Diz se o player está tocando o áudio do cartão (qualquer casamento, exceto [Casamento.NENHUM]).
 */
fun mesmoAudiobook(player: ChaveDoPlayer, cartao: IdentidadeDoAudiobook): Boolean =
    casamentoDoAudiobook(player, cartao) != Casamento.NENHUM

/**
 * Como o player casa com o cartão.
 *
 * Primeiro casa a chave direta (uri, caminho, uri "file:"). Depois, se o app copiou o arquivo para Downloads,
 * casa pelo _id do MediaStore da cópia: a uri do cartão e a da cópia são a mesma linha da tabela files
 * (`content://media/external/audio/media/N` e `content://media/external/downloads/N`). Sem registro da cópia
 * (conversão antiga), casa pelo nome do arquivo interno sem extensão: é o [Casamento.POR_NOME], que só vale
 * quando é o único cartão com esse nome (ver [indicesDoAudiobookNoPlayer]).
 */
fun casamentoDoAudiobook(player: ChaveDoPlayer, cartao: IdentidadeDoAudiobook): Casamento {
    val chave = player.chave?.takeIf { it.isNotEmpty() } ?: return Casamento.NENHUM
    val copia = player.copiaExportada
    return when {
        casaDireto(chave, cartao) -> Casamento.DIRETO
        copia != null -> if (casaCopia(copia, cartao)) Casamento.DIRETO else Casamento.NENHUM
        nomeIgualAoInterno(chave, player.pastaInterna, cartao.nomeArquivo) -> Casamento.POR_NOME
        else -> Casamento.NENHUM
    }
}

/**
 * Quais cartões da lista tocam no player, pela posição. O casamento direto vale sempre; o casamento pelo nome só
 * vale quando um único cartão tem esse nome: com dois ou mais (dois livros "capitulo.mp3", por exemplo), nenhum é
 * marcado, para não apontar o livro errado. O mini-player continua dizendo o que toca.
 */
fun indicesDoAudiobookNoPlayer(player: ChaveDoPlayer, cartoes: List<IdentidadeDoAudiobook>): Set<Int> {
    val casos = cartoes.map { casamentoDoAudiobook(player, it) }
    val diretos = casos.indices.filter { casos[it] == Casamento.DIRETO }
    val porNome = casos.indices.filter { casos[it] == Casamento.POR_NOME }
    return (diretos + porNome.takeIf { it.size == 1 }.orEmpty()).toSet()
}

private fun casaCopia(copia: String, cartao: IdentidadeDoAudiobook): Boolean {
    val idDaCopia = idDoMediaStore(copia) ?: return casaDireto(copia, cartao)
    return idDaCopia == idDoMediaStore(cartao.uri)
}

private fun casaDireto(chave: String, cartao: IdentidadeDoAudiobook): Boolean {
    if (chave == cartao.uri || chave == cartao.caminho || chave == cartao.caminhoNoDisco) return true
    val alvo = caminhoDeArquivo(chave) ?: return false
    return alvo == cartao.caminho || alvo == cartao.caminhoNoDisco
}

/** Conversão antiga: o arquivo interno (sob [pastaInterna]) tem o mesmo nome do cartão, sem a extensão. */
private fun nomeIgualAoInterno(chave: String, pastaInterna: String?, nomeDoCartao: String?): Boolean {
    val pasta = pastaInterna?.takeIf { it.isNotEmpty() } ?: return false
    val nome = nomeDoCartao?.takeIf { it.isNotEmpty() } ?: return false
    if (!chave.startsWith("$pasta/")) return false
    return semExtensaoDeAudio(chave.substringAfterLast('/')) == semExtensaoDeAudio(nome)
}

private val colecoesDeArquivo = setOf("audio/media", "downloads", "file")

/**
 * _id do MediaStore de uma uri `content://media/external/<coleção>/<id>` das coleções de arquivo
 * (áudio, downloads, files). Null para as outras uris, como as de álbuns.
 */
internal fun idDoMediaStore(uri: String): Long? {
    val prefixo = "content://media/external/"
    if (!uri.startsWith(prefixo)) return null
    val partes = uri.removePrefix(prefixo).split('/')
    val colecao = partes.dropLast(1).joinToString("/")
    return if (colecao in colecoesDeArquivo) partes.last().toLongOrNull() else null
}

/** Caminho do arquivo de uma chave "/caminho" ou "file:///caminho" (com %20 decodificado); null para content://. */
internal fun caminhoDeArquivo(chave: String): String? = when {
    chave.startsWith("/") -> chave
    chave.startsWith("file:") -> runCatching { java.net.URI(chave).path }.getOrNull()
    else -> null
}
