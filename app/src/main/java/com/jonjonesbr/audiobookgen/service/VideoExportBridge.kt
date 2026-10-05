package com.jonjonesbr.audiobookgen.service

import android.net.Uri
import com.jonjonesbr.audiobookgen.domain.OpcoesVideo
import kotlinx.coroutines.flow.MutableStateFlow

/** O que o [VideoExportService] precisa saber do audiobook a exportar. */
data class PedidoVideo(
    val uri: Uri,
    val caminho: String?,
    val nome: String,
    val duracaoMs: Long,
    val opcoes: OpcoesVideo
)

/** Andamento da exportação de vídeo; o serviço escreve, a tela e a notificação observam. */
data class EstadoVideoExport(
    val titulo: String,
    val parte: Int = 1,
    val pct: Int = 0,
    /** Preenchido quando terminou com sucesso: quantos arquivos foram exportados. */
    val partesExportadas: Int? = null,
    val erro: String? = null,
    val cancelado: Boolean = false
) {
    val terminou: Boolean get() = partesExportadas != null || erro != null || cancelado
}

object VideoExportBridge {
    val state = MutableStateFlow<EstadoVideoExport?>(null)

    @Volatile
    var pedido: PedidoVideo? = null

    /** Pede o cancelamento da exportação em andamento (definido pelo serviço). */
    @Volatile
    var cancelar: (() -> Unit)? = null
}
