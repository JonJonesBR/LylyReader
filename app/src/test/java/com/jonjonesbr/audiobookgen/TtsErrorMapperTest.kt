package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.TtsErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsErrorMapperTest {
    @Test
    fun mapsNetworkFailuresToConnectionMessage() {
        val message = TtsErrorMapper.toUserMessageRes("ClientConnectorError: cannot connect to host")

        assertEquals(R.string.erro_tts_sem_conexao, message)
    }

    @Test
    fun mapsGeminiKeyFailuresToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessageRes("Gemini API key is missing")

        assertEquals(R.string.erro_tts_gemini_invalida, message)
    }

    @Test
    fun mapsEncryptedPdfFailuresToDrmMessage() {
        val message = TtsErrorMapper.toUserMessageRes("Permission denied while reading encrypted PDF")

        assertEquals(R.string.erro_tts_protegido, message)
    }

    @Test
    fun mapsPasswordProtectedDocFailuresToDrmMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "Erro ao ler arquivo: senha: documento protegido/criptografado"
        )

        assertEquals(R.string.erro_tts_protegido, message)
    }

    @Test
    fun mapsQuotaFailuresToRetryLaterMessage() {
        val message = TtsErrorMapper.toUserMessageRes("HTTP 429 rate limit exceeded")

        assertEquals(R.string.erro_tts_limite_requisicoes, message)
    }

    // Motivos propagados pelo processo isolado :supertonic (SupertonicProcessClient)

    @Test
    fun mapsIsolatedProcessDeathToOutOfMemoryMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "Falha na síntese de voz offline: o processo isolado morreu durante a síntese " +
                "(crash nativo — provavelmente falta de memória)"
        )

        assertEquals(R.string.erro_tts_offline_sem_memoria, message)
    }

    @Test
    fun mapsIsolatedProcessBindFailureToEngineUnavailableMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "não foi possível conectar ao processo isolado (:supertonic)"
        )

        assertEquals(R.string.erro_tts_offline_nao_respondeu, message)
    }

    @Test
    fun mapsIsolatedProcessIpcErrorToEngineUnavailableMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "erro de IPC com o processo isolado: Parcel data corrupted"
        )

        assertEquals(R.string.erro_tts_offline_nao_respondeu, message)
    }

    @Test
    fun mapsModelNotDownloadedToDownloadVoicesMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "Modelo 'supertonic-f1' não baixado. Acesse Configurações → Vozes Offline."
        )

        assertEquals(R.string.erro_tts_offline_nao_baixada, message)
    }

    @Test
    fun stillMapsEnglishOfflineNetworkErrorsToConnectionMessage() {
        // A keyword "offline" segue valendo p/ erros de rede em ingles, mesmo apos a
        // correcao que impede "voz offline" (Supertonic) de cair no caso de rede.
        val message = TtsErrorMapper.toUserMessageRes("Device is offline")

        assertEquals(R.string.erro_tts_sem_conexao, message)
    }

    @Test
    fun mapsMissingIsolatedProcessReasonToGenericMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "Falha na síntese de voz offline: motivo desconhecido"
        )

        assertEquals(R.string.erro_tts_generico, message)
    }

    @Test
    fun hidesUnknownTechnicalDetailsFromUserMessage() {
        val message = TtsErrorMapper.toUserMessageRes("unexpected parser state at C:/Users/name/private/book.epub")

        assertEquals(R.string.erro_tts_generico, message)
    }

    @Test
    fun mapsElevenLabsRejectedKeyFailuresToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessageRes("ElevenLabs API error: 401 Unauthorized")

        assertEquals(R.string.erro_tts_elevenlabs_recusada, message)
    }

    @Test
    fun mapsElevenLabsMissingKeyFailuresToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessageRes("ElevenLabs API key is missing")

        assertEquals(R.string.erro_tts_elevenlabs_invalida, message)
    }

    // Mensagens de exceção de rede do Java (HttpURLConnection) que chegam pelo motivo real
    // propagado da ElevenLabsSynthBridge — falha de rede não pode ser rotulada como chave.
    @Test
    fun mapsJavaUnknownHostFailureToConnectionMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "ElevenLabs: Unable to resolve host \"api.elevenlabs.io\": No address associated with hostname"
        )

        assertEquals(R.string.erro_tts_sem_conexao, message)
    }

    @Test
    fun mapsJavaConnectFailureToConnectionMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "ElevenLabs: failed to connect to api.elevenlabs.io/104.18.0.1 (port 443) " +
                "from /10.0.0.1 (port 41234): connect failed"
        )

        assertEquals(R.string.erro_tts_sem_conexao, message)
    }

    @Test
    fun mapsWrappedElevenLabsNetworkFailureFromConversionToConnectionMessage() {
        // Mensagem final montada pelo core_processor_android.py com o motivo real propagado
        // pela ponte — falha de rede não pode ser rotulada como chave inválida.
        val message = TtsErrorMapper.toUserMessageRes(
            "5 de 120 blocos não puderam ser convertidos após 3 tentativas. " +
                "ElevenLabs: failed to connect to api.elevenlabs.io/104.18.0.1 (port 443): connect failed"
        )

        assertEquals(R.string.erro_tts_sem_conexao, message)
    }

    @Test
    fun mapsWrappedElevenLabsRejectedKeyFromConversionToCredentialMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "5 de 120 blocos não puderam ser convertidos após 3 tentativas. " +
                "ElevenLabs: elevenlabs http 401: Unauthenticated"
        )

        assertEquals(R.string.erro_tts_elevenlabs_recusada, message)
    }

    // Mensagens finais do core_processor_android.py quando trechos do livro falham (motor Edge)

    @Test
    fun mapsEdgeBlocksFailureToPartialConversionMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "19 de 201 blocos não puderam ser convertidos após 3 tentativas. " +
                "Verifique a conexão com a internet."
        )

        assertEquals(R.string.erro_tts_blocos_falharam, message)
    }

    @Test
    fun mapsEdgeBlocksFailureWithoutAccentsToPartialConversionMessage() {
        val message = TtsErrorMapper.toUserMessageRes(
            "19 de 201 blocos nao puderam ser convertidos apos 3 tentativas."
        )

        assertEquals(R.string.erro_tts_blocos_falharam, message)
    }

    @Test
    fun mapsAudioJoinFailureToJoinMessage() {
        val message = TtsErrorMapper.toUserMessageRes("Falha na concatenação do áudio.")

        assertEquals(R.string.erro_tts_juncao_falhou, message)
    }

    @Test
    fun mapsAudioJoinFailureWithoutAccentsToJoinMessage() {
        val message = TtsErrorMapper.toUserMessageRes("Falha na concatenacao do audio.")

        assertEquals(R.string.erro_tts_juncao_falhou, message)
    }
}
