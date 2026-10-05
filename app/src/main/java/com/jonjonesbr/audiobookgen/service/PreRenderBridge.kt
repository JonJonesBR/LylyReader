package com.jonjonesbr.audiobookgen.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Andamento do "preparar capítulo" (pré-renderização da leitura guiada). O [GuidedPlayerManager]
 * escreve; o [PreRenderService] (notificação em primeiro plano) e a tela observam.
 */
data class PreRenderState(
    val feitos: Int,
    val total: Int,
    val titulo: String,
    val concluido: Boolean = false,
    val cancelado: Boolean = false,
    val erro: String? = null,
    /** Motor mais lento que a fala: o preparo espera enquanto a leitura ao vivo está tocando. */
    val esperandoLeitura: Boolean = false
) {
    val fracao: Float get() = if (total <= 0) 0f else (feitos.toFloat() / total).coerceIn(0f, 1f)
    val terminou: Boolean get() = concluido || cancelado || erro != null
}

object PreRenderBridge {
    val state = MutableStateFlow<PreRenderState?>(null)

    /** Pede o cancelamento do preparo em andamento (definido pelo gerenciador ativo). */
    @Volatile
    var cancelar: (() -> Unit)? = null
}
