package com.jonjonesbr.audiobookgen.domain

/**
 * Regras puras para detectar conexão lenta no progresso do Python e montar o pacote técnico
 * gravado no log interno (sem expor IP, SSID, chaves ou conteúdo do livro).
 */
object DiagnosticoConversaoLenta {

    fun mensagemIndicaConexaoLenta(msg: String): Boolean =
        ProgressoDaConversao.partes(msg).any { parte ->
            parte is ParteDaMensagem.Progresso && parte.progresso.lenta
        }

    fun montarCamposExtras(
        motor: String,
        voz: String,
        pct: Int,
        ipv6Ativo: Boolean?,
        resumoPython: Map<String, String>,
    ): Map<String, String> {
        val extras = linkedMapOf(
            "motor" to motor,
            "voz" to voz,
            "progresso_pct" to pct.toString(),
            "ipv6_presente" to when (ipv6Ativo) {
                true -> "sim"
                false -> "nao"
                null -> "desconhecido"
            },
        )
        resumoPython.forEach { (chave, valor) ->
            val valorLimpo = valor.trim()
            if (chave.isNotBlank() && valorLimpo.isNotEmpty()) {
                val chaveNormalizada = if (chave.startsWith("edge_")) chave else "edge_$chave"
                extras[chaveNormalizada] = valorLimpo
            }
        }
        return extras
    }
}
