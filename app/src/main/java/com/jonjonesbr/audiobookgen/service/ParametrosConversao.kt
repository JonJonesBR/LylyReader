package com.jonjonesbr.audiobookgen.service

import androidx.work.Data
import androidx.work.workDataOf

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
    val filaItemId: String? = null
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
        ConversionWorker.KEY_SPEAKER_MAP_PATH to speakerMapPath
        )
        val builder = Data.Builder().putAll(base)
        tomMeiosTons?.let { builder.putInt(ConversionWorker.KEY_TOM_MEIOS_TONS, it) }
        agudosDb?.let { builder.putInt(ConversionWorker.KEY_AGUDOS_DB, it) }
        filaItemId?.let { builder.putString(ConversionWorker.KEY_FILA_ITEM_ID, it) }
        return builder.build()
    }
}
