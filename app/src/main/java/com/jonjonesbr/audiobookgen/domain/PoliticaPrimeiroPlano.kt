package com.jonjonesbr.audiobookgen.domain

/** Tipo de serviço em primeiro plano que a conversão pede ao sistema (ver [PoliticaPrimeiroPlano.tipoDeServico]). */
enum class TipoServicoConversao { MEDIA_PROCESSING, DATA_SYNC, NENHUM }

/**
 * Regras puras de quando e com qual tipo a conversão vira serviço de primeiro plano.
 * Sem Android: recebe o SDK e os estados como parâmetros.
 */
object PoliticaPrimeiroPlano {

    private const val SDK_MEDIA_PROCESSING = 35
    private const val SDK_DATA_SYNC = 29
    private const val SDK_RESTRICAO_INICIO_EM_SEGUNDO_PLANO = 31

    /** Pausa automática antes do limite de ~6 h do tipo mediaProcessing (5h40 de folga). */
    const val LIMITE_AUTOPAUSA_MS = (5 * 60 + 40) * 60_000L

    /** Tipo de serviço por SDK: mediaProcessing (35+), dataSync (29–34) e nenhum abaixo de 29. */
    fun tipoDeServico(sdk: Int): TipoServicoConversao = when {
        sdk >= SDK_MEDIA_PROCESSING -> TipoServicoConversao.MEDIA_PROCESSING
        sdk >= SDK_DATA_SYNC -> TipoServicoConversao.DATA_SYNC
        else -> TipoServicoConversao.NENHUM
    }

    /**
     * Pode iniciar o primeiro plano: abaixo do Android 12 (SDK 31) sempre; a partir dele, só com o app
     * visível ou com o app fora da otimização de bateria (o sistema bloqueia o início em segundo plano).
     */
    fun podeIniciar(sdk: Int, appVisivel: Boolean, semOtimizacaoBateria: Boolean): Boolean =
        sdk < SDK_RESTRICAO_INICIO_EM_SEGUNDO_PLANO || appVisivel || semOtimizacaoBateria

    /**
     * Deve pausar sozinha antes que o sistema mate o primeiro plano: só no SDK 35+, em primeiro plano, com o app
     * fora da tela e ao menos [LIMITE_AUTOPAUSA_MS] desde [inicioOrcamentoMs]. Com o app visível o orçamento do
     * sistema reinicia, então nunca pausa nesse caso.
     */
    fun deveAutoPausar(
        sdk: Int, emPrimeiroPlano: Boolean, appVisivel: Boolean, inicioOrcamentoMs: Long, agoraMs: Long
    ): Boolean =
        sdk >= SDK_MEDIA_PROCESSING && emPrimeiroPlano && !appVisivel &&
            agoraMs - inicioOrcamentoMs >= LIMITE_AUTOPAUSA_MS
}
