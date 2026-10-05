package com.jonjonesbr.audiobookgen.util

object TtsErrorMapper {
    fun toUserMessage(raw: String): String {
        val r = raw.lowercase()
        return when {
            "cancelado pelo usuario" in r || "cancelado pelo usuário" in r || "cancelled" in r ->
                "Conversao cancelada."
            // Motivos do processo isolado :supertonic — ANTES do caso de rede: as mensagens
            // desse motor contêm "voz offline", que colidiria com a keyword "offline" abaixo.
            "processo isolado" in r && ("morreu" in r || "crash" in r) ->
                "A sintese offline falhou por falta de memoria. Feche outros apps e tente novamente."
            "processo isolado" in r ->
                "O motor de voz offline nao respondeu. Tente novamente; se persistir, reinicie o app."
            ("nao baixado" in r || "não baixado" in r) && "modelo" in r ->
                "A voz offline ainda nao foi baixada. Acesse Configuracoes e baixe as vozes offline."
            "network is unreachable" in r || "cannot connect" in r || "connection refused" in r ||
                "clientconnectorerror" in r || "connectionerror" in r || "gaierror" in r ||
                "nodename nor servname" in r || "failed to establish" in r ||
                // Mensagens de exceção de rede do Java (HttpURLConnection) — ex.: ElevenLabs
                "unable to resolve host" in r || "connect failed" in r || "failed to connect" in r ||
                // "offline" pega erros de rede em ingles, mas nao mensagens PT sobre "voz offline"
                ("offline" in r && "voz" !in r) ->
                "Sem conexao. Verifique a internet e tente novamente."
            "timeout" in r || "timed out" in r ->
                "A conexao demorou demais. Tente novamente em alguns instantes."
            "too many requests" in r || "rate limit" in r || "quota" in r || "429" in r ->
                "O servico de voz limitou as requisicoes. Aguarde um pouco e tente novamente."
            ("401" in r || "403" in r || "unauthorized" in r || "forbidden" in r) && "gemini" in r ->
                "A chave Gemini foi recusada. Revise a chave salva nas configuracoes."
            "gemini" in r && ("key" in r || "api" in r || "chave" in r) ->
                "A chave Gemini esta ausente ou invalida."
            ("401" in r || "403" in r || "unauthorized" in r || "forbidden" in r) && "elevenlabs" in r ->
                "A chave ElevenLabs foi recusada. Revise a chave salva nas configuracoes."
            "elevenlabs" in r && ("key" in r || "api" in r || "chave" in r) ->
                "A chave ElevenLabs esta ausente ou invalida."
            "voz" in r && ("indisponivel" in r || "indisponível" in r) ->
                "A voz selecionada esta temporariamente indisponivel."
            "invalid voice" in r || "voice not found" in r ->
                "A voz selecionada nao esta disponivel neste motor."
            "no audio was received" in r || "no audio data" in r ->
                "O servico de voz nao devolveu audio. Tente novamente."
            "badzipfile" in r || "bad zip" in r || "not a zip" in r ->
                "O arquivo EPUB parece invalido ou corrompido."
            "pdfreader" in r || "pdfexception" in r || "invalid pdf" in r || "startxref" in r ->
                "O PDF nao pode ser lido neste formato."
            "drm" in r || "encrypted" in r || "senha" in r || ("permission" in r && "pdf" in r) ->
                "O arquivo parece protegido por senha ou DRM."
            "formato" in r && ("nao suportado" in r || "não suportado" in r) ->
                "Formato de arquivo nao suportado."
            "texto vazio" in r || "nao contem texto" in r || "não contém texto" in r || "no text" in r ->
                "Nao foi encontrado texto legivel no documento."
            "nenhum chunk" in r ->
                "O texto nao pode ser separado para narracao."
            else ->
                "Nao foi possivel concluir. Tente novamente ou use outro arquivo."
        }
    }
}
