package com.jonjonesbr.audiobookgen.service

import androidx.work.Data
import androidx.work.workDataOf
import org.json.JSONObject

data class ParametrosConversao(
    val arquivoEntrada: String,
    val voz: String,
    val velocidade: String,
    val estilo: String,
    val caminhoSaidaInterna: String,
    val motor: String,
    val runToken: String? = null,
    /**
     * Caminho do livro-texto de ORIGEM (o mesmo usado como chave de posição de leitura),
     * quando a conversão cobre o livro INTEIRO — usado só para vincular o MP3 gerado ao
     * livro no [com.jonjonesbr.audiobookgen.data.LinkedBookStore]. Null quando a conversão
     * é parcial (capítulo/trecho selecionado) ou iniciada fora do leitor, para não vincular
     * um áudio incompleto ao livro inteiro.
     */
    val livroOrigemCaminho: String? = null,
    /** Mapa local de falantes gerado no leitor; ausente mantém a conversão normal. */
    val speakerMapPath: String? = null,
    /** Só quando o livro tem perfil de áudio próprio; null = usa os ajustes globais. */
    val tomMeiosTons: Int? = null,
    val agudosDb: Int? = null,
    /** Item da fila de processamento que esta execução atende; o worker o fecha e inicia o próximo (ver [FilaDeConversao]). */
    val filaItemId: String? = null,
    /**
     * Livro dono do texto (inteiro, capítulo ou trecho) cujo dicionário de pronúncia próprio entra na conversão, somado ao global.
     * É o caminho real do livro, nunca o .txt temporário. Null = só o global.
     */
    val livroDicionarioCaminho: String? = null
) {
    fun toInputData(pausaMs: Int): Data {
        val base = workDataOf(
        ConversionWorker.KEY_ARQUIVO       to arquivoEntrada,
        ConversionWorker.KEY_VOZ           to voz,
        ConversionWorker.KEY_VELOCIDADE    to velocidade,
        ConversionWorker.KEY_ESTILO        to estilo,
        ConversionWorker.KEY_SAIDA         to caminhoSaidaInterna,
        ConversionWorker.KEY_MOTOR         to motor,
        ConversionWorker.KEY_PAUSA_MS      to pausaMs,
        ConversionWorker.KEY_RUN_TOKEN     to runToken,
        ConversionWorker.KEY_RUN_TOKEN_ENTRADA to runToken,
        ConversionWorker.KEY_LIVRO_ORIGEM  to livroOrigemCaminho,
        ConversionWorker.KEY_SPEAKER_MAP_PATH to speakerMapPath,
        ConversionWorker.KEY_LIVRO_DICIONARIO to livroDicionarioCaminho
        )
        val builder = Data.Builder().putAll(base)
        tomMeiosTons?.let { builder.putInt(ConversionWorker.KEY_TOM_MEIOS_TONS, it) }
        agudosDb?.let { builder.putInt(ConversionWorker.KEY_AGUDOS_DB, it) }
        filaItemId?.let { builder.putString(ConversionWorker.KEY_FILA_ITEM_ID, it) }
        return builder.build()
    }

    fun toJson(): String {
        val obj = JSONObject()
            .put("arquivoEntrada", arquivoEntrada)
            .put("voz", voz)
            .put("velocidade", velocidade)
            .put("estilo", estilo)
            .put("caminhoSaidaInterna", caminhoSaidaInterna)
            .put("motor", motor)
        runToken?.let { obj.put("runToken", it) }
        livroOrigemCaminho?.let { obj.put("livroOrigemCaminho", it) }
        speakerMapPath?.let { obj.put("speakerMapPath", it) }
        tomMeiosTons?.let { obj.put("tomMeiosTons", it) }
        agudosDb?.let { obj.put("agudosDb", it) }
        filaItemId?.let { obj.put("filaItemId", it) }
        livroDicionarioCaminho?.let { obj.put("livroDicionarioCaminho", it) }
        return obj.toString()
    }

    companion object {
        fun fromJson(raw: String?): ParametrosConversao? =
            raw?.takeIf { it.isNotBlank() }?.let { texto ->
                runCatching {
                    val obj = JSONObject(texto)
                    val arquivo = obj.optString("arquivoEntrada", "")
                    val saida = obj.optString("caminhoSaidaInterna", "")
                    if (arquivo.isBlank() || saida.isBlank()) {
                        null
                    } else {
                        ParametrosConversao(
                            arquivoEntrada = arquivo,
                            voz = obj.optString("voz", ""),
                            velocidade = obj.optString("velocidade", "+0%"),
                            estilo = obj.optString("estilo", "padrao"),
                            caminhoSaidaInterna = saida,
                            motor = obj.optString("motor", ""),
                            runToken = obj.optString("runToken").ifBlank { null },
                            livroOrigemCaminho = obj.optString("livroOrigemCaminho").ifBlank { null },
                            speakerMapPath = obj.optString("speakerMapPath").ifBlank { null },
                            tomMeiosTons = if (obj.has("tomMeiosTons")) obj.getInt("tomMeiosTons") else null,
                            agudosDb = if (obj.has("agudosDb")) obj.getInt("agudosDb") else null,
                            filaItemId = obj.optString("filaItemId").ifBlank { null },
                            livroDicionarioCaminho = obj.optString("livroDicionarioCaminho").ifBlank { null },
                        )
                    }
                }.getOrNull()
            }
    }
}
