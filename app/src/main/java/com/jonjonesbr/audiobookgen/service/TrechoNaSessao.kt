package com.jonjonesbr.audiobookgen.service

/**
 * O que a sessão de mídia mostra do trecho que está no player (rb-41). velocidade 0 = parado.
 * Função pura, sem Android: a barra da notificação é desenhada pelo sistema a partir destes valores.
 */
internal data class TrechoNaSessao(
    val duracaoMs: Long,     // sempre > 0
    val posicaoMs: Long,     // 0..duracaoMs
    val velocidade: Float,   // efetiva (base × multiplicador × fala); 0f se pausado/terminou/falhou
    val evento: Long,        // contador: dois eventos com os mesmos valores não se fundem no StateFlow
    val instanteMs: Long     // elapsedRealtime em que a posição foi lida no player (a barra anda a partir dele)
)

/** Leitura crua do player num evento. */
internal data class LeituraDoPlayer(
    val duracaoMs: Int,
    val posicaoMs: Int,
    val tocando: Boolean,
    val velocidade: Float,
    val terminou: Boolean = false
)

/**
 * Converte a leitura do player no trecho da sessão. [anterior] é o último trecho publicado (null se não há).
 * [instante] é o elapsedRealtime da leitura. Devolve null quando não há duração nem trecho anterior para mostrar.
 */
internal fun trechoLido(
    leitura: LeituraDoPlayer,
    anterior: TrechoNaSessao?,
    evento: Long,
    instante: Long
): TrechoNaSessao? =
    when {
        // Motor vazio, falha ou carregando: a barra para onde estava, com velocidade 0.
        leitura.duracaoMs <= 0 ->
            anterior?.let {
                if (it.velocidade == 0f) it else it.copy(velocidade = 0f, evento = evento, instanteMs = instante)
            }
        // Já parado e sem ter terminado: um MediaPlayer terminado pode responder posição 0, e a barra
        // congelada no fim voltaria para o começo. Mantém o trecho anterior intacto.
        !leitura.tocando && !leitura.terminou && anterior?.velocidade == 0f -> anterior
        else -> {
            val duracao = leitura.duracaoMs.toLong()
            TrechoNaSessao(
                duracaoMs = duracao,
                posicaoMs = if (leitura.terminou) duracao else leitura.posicaoMs.toLong().coerceIn(0L, duracao),
                velocidade = if (leitura.tocando && !leitura.terminou) leitura.velocidade.coerceAtLeast(0f) else 0f,
                evento = evento,
                instanteMs = instante
            )
        }
    }
