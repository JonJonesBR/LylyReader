package com.jonjonesbr.audiobookgen.domain

/** Situação de um pacote na Central de downloads. */
enum class EstadoDownload { NAO_BAIXADO, NA_FILA, BAIXANDO, PAUSADO, PRONTO, ERRO }

const val DOWNLOADS_SIMULTANEOS_MIN = 1
const val DOWNLOADS_SIMULTANEOS_MAX = 3

/**
 * Quais itens da [fila] (na ordem de chegada) começam agora, sem passar de [limite] downloads
 * simultâneos. Só entram os que estão em [EstadoDownload.NA_FILA]; os que já estão baixando ocupam vaga.
 */
fun proximosDaFila(fila: List<String>, estados: Map<String, EstadoDownload>, limite: Int): List<String> {
    val ativos = estados.values.count { it == EstadoDownload.BAIXANDO }
    val vagas = (limite.coerceIn(DOWNLOADS_SIMULTANEOS_MIN, DOWNLOADS_SIMULTANEOS_MAX) - ativos).coerceAtLeast(0)
    return fila.filter { estados[it] == EstadoDownload.NA_FILA }.take(vagas)
}

/**
 * Fração (0..1) do conjunto de downloads ativos, somando bytes e totais ([bytes] to [total]).
 * Null enquanto nenhum total é conhecido (barra indeterminada).
 */
fun fracaoAgregada(parciais: List<Pair<Long, Long>>): Float? {
    val conhecidos = parciais.filter { it.second > 0L }
    if (conhecidos.isEmpty()) return null
    val total = conhecidos.sumOf { it.second }
    return (conhecidos.sumOf { it.first.coerceAtMost(it.second) }.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * O baixar-tudo precisa do arquivo compactado e do conteúdo extraído ao mesmo tempo, então reserva
 * [FATOR_ESPACO_DOWNLOAD] vezes o tamanho anunciado.
 */
const val FATOR_ESPACO_DOWNLOAD = 2L

fun espacoSuficiente(livreBytes: Long, aBaixarBytes: Long): Boolean =
    livreBytes >= aBaixarBytes * FATOR_ESPACO_DOWNLOAD
