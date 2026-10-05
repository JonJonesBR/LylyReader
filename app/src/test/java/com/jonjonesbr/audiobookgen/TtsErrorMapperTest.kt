package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.TtsErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsErrorMapperTest {
    @Test
    fun mapsNetworkFailuresToConnectionMessage() {
        val message = TtsErrorMapper.toUserMessage("ClientConnectorError: cannot connect to host")

        assertEquals("Sem conexao. Verifique a internet e tente novamente.", message)
    }

    @Test
    fun mapsGeminiKeyFailuresToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessage("Gemini API key is missing")

        assertEquals("A chave Gemini esta ausente ou invalida.", message)
    }

    @Test
    fun mapsEncryptedPdfFailuresToDrmMessage() {
        val message = TtsErrorMapper.toUserMessage("Permission denied while reading encrypted PDF")

        assertEquals("O arquivo parece protegido por senha ou DRM.", message)
    }

    @Test
    fun mapsPasswordProtectedDocFailuresToDrmMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "Erro ao ler arquivo: senha: documento protegido/criptografado"
        )

        assertEquals("O arquivo parece protegido por senha ou DRM.", message)
    }

    @Test
    fun mapsQuotaFailuresToRetryLaterMessage() {
        val message = TtsErrorMapper.toUserMessage("HTTP 429 rate limit exceeded")

        assertEquals("O servico de voz limitou as requisicoes. Aguarde um pouco e tente novamente.", message)
    }

    // Motivos propagados pelo processo isolado :supertonic (SupertonicProcessClient)

    @Test
    fun mapsIsolatedProcessDeathToOutOfMemoryMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "Falha na síntese de voz offline: o processo isolado morreu durante a síntese " +
                "(crash nativo — provavelmente falta de memória)"
        )

        assertEquals(
            "A sintese offline falhou por falta de memoria. Feche outros apps e tente novamente.",
            message
        )
    }

    @Test
    fun mapsIsolatedProcessBindFailureToEngineUnavailableMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "não foi possível conectar ao processo isolado (:supertonic)"
        )

        assertEquals(
            "O motor de voz offline nao respondeu. Tente novamente; se persistir, reinicie o app.",
            message
        )
    }

    @Test
    fun mapsIsolatedProcessIpcErrorToEngineUnavailableMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "erro de IPC com o processo isolado: Parcel data corrupted"
        )

        assertEquals(
            "O motor de voz offline nao respondeu. Tente novamente; se persistir, reinicie o app.",
            message
        )
    }

    @Test
    fun mapsModelNotDownloadedToDownloadVoicesMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "Modelo 'supertonic-f1' não baixado. Acesse Configurações → Vozes Offline."
        )

        assertEquals(
            "A voz offline ainda nao foi baixada. Acesse Configuracoes e baixe as vozes offline.",
            message
        )
    }

    @Test
    fun stillMapsEnglishOfflineNetworkErrorsToConnectionMessage() {
        // A keyword "offline" segue valendo p/ erros de rede em ingles, mesmo apos a
        // correcao que impede "voz offline" (Supertonic) de cair no caso de rede.
        val message = TtsErrorMapper.toUserMessage("Device is offline")

        assertEquals("Sem conexao. Verifique a internet e tente novamente.", message)
    }

    @Test
    fun mapsMissingIsolatedProcessReasonToGenericMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "Falha na síntese de voz offline: motivo desconhecido"
        )

        assertEquals("Nao foi possivel concluir. Tente novamente ou use outro arquivo.", message)
    }

    @Test
    fun hidesUnknownTechnicalDetailsFromUserMessage() {
        val message = TtsErrorMapper.toUserMessage("unexpected parser state at C:/Users/name/private/book.epub")

        assertEquals("Nao foi possivel concluir. Tente novamente ou use outro arquivo.", message)
    }

    @Test
    fun mapsElevenLabsRejectedKeyFailuresToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessage("ElevenLabs API error: 401 Unauthorized")

        assertEquals("A chave ElevenLabs foi recusada. Revise a chave salva nas configuracoes.", message)
    }

    @Test
    fun mapsElevenLabsMissingKeyFailuresToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessage("ElevenLabs API key is missing")

        assertEquals("A chave ElevenLabs esta ausente ou invalida.", message)
    }

    // Mensagens de exceção de rede do Java (HttpURLConnection) que chegam pelo motivo real
    // propagado da ElevenLabsSynthBridge — falha de rede não pode ser rotulada como chave.
    @Test
    fun mapsJavaUnknownHostFailureToConnectionMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "ElevenLabs: Unable to resolve host \"api.elevenlabs.io\": No address associated with hostname"
        )

        assertEquals("Sem conexao. Verifique a internet e tente novamente.", message)
    }

    @Test
    fun mapsJavaConnectFailureToConnectionMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "ElevenLabs: failed to connect to api.elevenlabs.io/104.18.0.1 (port 443) " +
                "from /10.0.0.1 (port 41234): connect failed"
        )

        assertEquals("Sem conexao. Verifique a internet e tente novamente.", message)
    }

    @Test
    fun mapsWrappedElevenLabsNetworkFailureFromConversionToConnectionMessage() {
        // Mensagem final montada pelo core_processor_android.py com o motivo real propagado
        // pela ponte — falha de rede não pode ser rotulada como chave inválida.
        val message = TtsErrorMapper.toUserMessage(
            "5 de 120 blocos não puderam ser convertidos após 3 tentativas. " +
                "ElevenLabs: failed to connect to api.elevenlabs.io/104.18.0.1 (port 443): connect failed"
        )

        assertEquals("Sem conexao. Verifique a internet e tente novamente.", message)
    }

    @Test
    fun mapsWrappedElevenLabsRejectedKeyFromConversionToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessage(
            "5 de 120 blocos não puderam ser convertidos após 3 tentativas. " +
                "ElevenLabs: elevenlabs http 401: Unauthenticated"
        )

        assertEquals("A chave ElevenLabs foi recusada. Revise a chave salva nas configuracoes.", message)
    }
}
