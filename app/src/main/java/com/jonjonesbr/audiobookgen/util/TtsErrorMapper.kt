package com.jonjonesbr.audiobookgen.util

import androidx.annotation.StringRes
import com.jonjonesbr.audiobookgen.R

object TtsErrorMapper {
    /**
     * Converte uma mensagem técnica de erro num texto para o usuário. Devolve o ID do recurso
     * (string), para quem chama resolver com o idioma do app.
     */
    @StringRes
    fun toUserMessageRes(raw: String): Int {
        val r = raw.lowercase()
        return when {
            "cancelado pelo usuario" in r || "cancelado pelo usuário" in r || "cancelled" in r ->
                R.string.erro_tts_cancelado
            // Motivos do processo isolado :supertonic — ANTES do caso de rede: as mensagens
            // desse motor contêm "voz offline", que colidiria com a keyword "offline" abaixo.
            "processo isolado" in r && ("morreu" in r || "crash" in r) ->
                R.string.erro_tts_offline_sem_memoria
            "processo isolado" in r ->
                R.string.erro_tts_offline_nao_respondeu
            ("nao baixado" in r || "não baixado" in r) && "modelo" in r ->
                R.string.erro_tts_offline_nao_baixada
            "network is unreachable" in r || "cannot connect" in r || "connection refused" in r ||
                "clientconnectorerror" in r || "connectionerror" in r || "gaierror" in r ||
                "nodename nor servname" in r || "failed to establish" in r ||
                // Mensagens de exceção de rede do Java (HttpURLConnection) — ex.: ElevenLabs
                "unable to resolve host" in r || "connect failed" in r || "failed to connect" in r ||
                // "offline" pega erros de rede em ingles, mas nao mensagens PT sobre "voz offline"
                ("offline" in r && "voz" !in r) ->
                R.string.erro_tts_sem_conexao
            "timeout" in r || "timed out" in r ->
                R.string.erro_tts_tempo_esgotado
            "too many requests" in r || "rate limit" in r || "quota" in r || "429" in r ->
                R.string.erro_tts_limite_requisicoes
            ("401" in r || "403" in r || "unauthorized" in r || "forbidden" in r) && "gemini" in r ->
                R.string.erro_tts_gemini_recusada
            "gemini" in r && ("key" in r || "api" in r || "chave" in r) ->
                R.string.erro_tts_gemini_invalida
            ("401" in r || "403" in r || "unauthorized" in r || "forbidden" in r) && "elevenlabs" in r ->
                R.string.erro_tts_elevenlabs_recusada
            "elevenlabs" in r && ("key" in r || "api" in r || "chave" in r) ->
                R.string.erro_tts_elevenlabs_invalida
            "voz" in r && ("indisponivel" in r || "indisponível" in r) ->
                R.string.erro_tts_voz_temporariamente_indisponivel
            "invalid voice" in r || "voice not found" in r ->
                R.string.erro_tts_voz_nao_disponivel
            "no audio was received" in r || "no audio data" in r ->
                R.string.erro_tts_sem_audio
            "badzipfile" in r || "bad zip" in r || "not a zip" in r ->
                R.string.erro_tts_epub_invalido
            "pdfreader" in r || "pdfexception" in r || "invalid pdf" in r || "startxref" in r ->
                R.string.erro_tts_pdf_ilegivel
            "drm" in r || "encrypted" in r || "senha" in r || ("permission" in r && "pdf" in r) ->
                R.string.erro_tts_protegido
            "formato" in r && ("nao suportado" in r || "não suportado" in r) ->
                R.string.erro_tts_formato_nao_suportado
            "texto vazio" in r || "nao contem texto" in r || "não contém texto" in r || "no text" in r ->
                R.string.erro_tts_sem_texto
            "nenhum chunk" in r ->
                R.string.erro_tts_texto_nao_separavel
            // Mensagens finais do core_processor_android.py. Ficam depois das causas específicas
            // (rede, chave, timeout) e antes do genérico.
            "blocos nao puderam" in r || "blocos não puderam" in r ->
                R.string.erro_tts_blocos_falharam
            "falha na concatenacao" in r || "falha na concatenação" in r ->
                R.string.erro_tts_juncao_falhou
            else ->
                R.string.erro_tts_generico
        }
    }
}
