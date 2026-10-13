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
}
