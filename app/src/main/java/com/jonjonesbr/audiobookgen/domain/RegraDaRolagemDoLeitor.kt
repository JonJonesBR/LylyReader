package com.jonjonesbr.audiobookgen.domain

import android.view.KeyEvent

/**
 * Regra pura da rolagem do leitor: quando a leitura guiada ou o audiobook conduzem a lista, qual posição salvar e
 * qual restaurar, e quais teclas rolam o texto. Sem Android (só as constantes de [KeyEvent], que o compilador embute).
 * Usada por ReaderRolagemController, que faz a parte com a lista.
 */
object RegraDaRolagemDoLeitor {

    /** Depois de o usuário rolar com o dedo, a rolagem automática do audiobook fica suspensa por este tempo. */
    const val PAUSA_ROLAGEM_MANUAL_MS = 8_000L

    /**
     * A leitura guiada conduz a rolagem quando está ativa ([indiceGuiada] >= 0) e o usuário não a pausou com a
     * rolagem manual. [scrollManual] é lambda para não ler o estado da barra quando não há leitura ativa.
     */
    fun guiadaConduzRolagem(indiceGuiada: Int, scrollManual: () -> Boolean): Boolean =
        indiceGuiada >= 0 && !scrollManual()

    /**
     * O acompanhamento do audiobook rola só quando a suspensão acabou, a sincronia está ativa, há parágrafo em
     * sincronia e ele mudou desde o último salto.
     */
    fun podeRolarComAudiobook(
        agora: Long,
        suspensaAte: Long,
        syncAtivo: Boolean,
        paragrafoSync: Int,
        ultimoSync: Int,
    ): Boolean =
        agora >= suspensaAte && syncAtivo && paragrafoSync >= 0 && paragrafoSync != ultimoSync

    /**
     * Parágrafo de referência para progresso e marcadores: o da sincronia quando há, senão o primeiro visível na
     * tela. Sem texto, não há referência.
     */
    fun paragrafoDeReferencia(temTexto: Boolean, paragrafoSync: Int, primeiroVisivel: Int?): Int? = when {
        !temTexto -> null
        paragrafoSync >= 0 -> paragrafoSync
        else -> primeiroVisivel?.takeIf { it >= 0 }
    }

    /**
     * Parágrafo a gravar ao sair da tela. A guiada encerrada vale só sem sincronia do audiobook; sem ela, o fim do
     * livro na tela grava o último parágrafo; senão, a referência visível.
     */
    fun paragrafoParaSalvar(
        guiadaAoEncerrar: Int,
        paragrafoSync: Int,
        fimNaTela: Boolean,
        totalItens: Int,
        visivel: Int?,
    ): Int? {
        val semSync = paragrafoSync < 0
        // Guiada encerrada sem rolagem depois (-1 se o usuário rolou): é a posição a guardar.
        val guiadaEncerrada = guiadaAoEncerrar.takeIf { it >= 0 && semSync }
        // Fim do livro na tela: grava o último parágrafo, para a biblioteca mostrar o livro como lido (100%).
        val fim = if (semSync && fimNaTela) totalItens - 1 else null
        return guiadaEncerrada ?: fim ?: visivel
    }

    /**
     * Posição a rolar ao abrir o livro. Na reconexão com a guiada ainda ativa, o parágrafo dela; senão a posição
     * salva (exceto quando a guiada vai começar sozinha, que faz o próprio scroll). Null = não rola.
     */
    fun posicaoARestaurar(
        reconectando: Boolean,
        indiceGuiada: Int,
        iniciaSozinha: Boolean,
        posicaoSalva: Int,
        total: Int,
    ): Int? = when {
        reconectando && indiceGuiada in 0 until total -> indiceGuiada
        !iniciaSozinha && posicaoSalva in 1 until total -> posicaoSalva
        else -> null
    }

    /** Sentido da rolagem de página: -1 para cima (PAGE_UP), 1 para baixo (PAGE_DOWN), null para as outras teclas. */
    fun sentidoDaTecla(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_UP -> -1
        KeyEvent.KEYCODE_PAGE_DOWN -> 1
        else -> null
    }

    /** Setas para cima e para baixo: o foco pode levar a lista a rolar. */
    fun ehSetaVertical(keyCode: Int): Boolean = sentidoDaSeta(keyCode) != null

    /** Sentido de uma seta vertical: 1 para baixo, -1 para cima. Nulo para as outras teclas. */
    fun sentidoDaSeta(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> -1
        KeyEvent.KEYCODE_DPAD_DOWN -> 1
        else -> null
    }

    /**
     * Seta vertical rola a página parada (rb-26c) só sem Shift e sem seleção aberta, com o TextView da página
     * focado e com texto para rolar. Fora disso, a seta segue o caminho normal (o TextView estende a seleção,
     * ou a lista muda o foco).
     */
    fun setaRolaAPagina(shift: Boolean, temSelecao: Boolean, textoFocado: Boolean, podeRolar: Boolean): Boolean {
        if (shift || temSelecao) return false
        return textoFocado && podeRolar
    }

    /** Teclas que podem rolar a lista (páginas e setas verticais). As outras nunca chegam à lista. */
    fun teclaDeRolagem(keyCode: Int): Boolean = sentidoDaTecla(keyCode) != null || ehSetaVertical(keyCode)
}

/**
 * Quanto a tecla de página anda na lista vertical (rb-67/13): a altura útil, sem os paddings (o RecyclerView desenha
 * as linhas por baixo do painel da guiada e do mini player), menos uma linha. Assim a última linha vista volta ao topo
 * e o fio não se perde. [alturaDaLinha] é 0 sem linhas na tela; então anda a altura útil inteira.
 */
fun passoDaTeclaDePagina(altura: Int, paddingTop: Int, paddingBottom: Int, alturaDaLinha: Int): Int =
    (altura - paddingTop - paddingBottom - alturaDaLinha).coerceAtLeast(alturaDaLinha)
