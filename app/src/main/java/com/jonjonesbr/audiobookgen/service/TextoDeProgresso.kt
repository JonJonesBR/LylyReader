package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.ParteDaMensagem
import com.jonjonesbr.audiobookgen.domain.ProgressoDaConversao
import com.jonjonesbr.audiobookgen.domain.ProgressoEstruturado
import com.jonjonesbr.audiobookgen.domain.TempoRestante

/**
 * Frase de progresso no idioma do app ("18% · faltam cerca de 2 min"), montada a partir dos números que o Python manda.
 * Usada pelo cartão de Conversões e pela notificação, para as duas mostrarem o mesmo texto.
 */
object TextoDeProgresso {

    /** Troca a linha "@progresso" pela frase; as demais linhas (capítulo, etapa) passam como estão. */
    fun montar(context: Context, mensagem: String): String {
        if (!mensagem.contains(ProgressoDaConversao.PREFIXO)) return mensagem
        return ProgressoDaConversao.partes(mensagem).joinToString("\n") { parte ->
            when (parte) {
                is ParteDaMensagem.Texto -> parte.texto
                is ParteDaMensagem.Progresso -> frase(context, parte.progresso)
            }
        }
    }

    /** Ex.: "18% · faltam cerca de 2 min"; com falhas, acrescenta "· 1 trecho falhou". */
    fun frase(context: Context, progresso: ProgressoEstruturado): String {
        val pct = progresso.pct
        val principal = when (val tempo = ProgressoDaConversao.tempoRestante(progresso.etaSegundos)) {
            TempoRestante.Calculando -> context.getString(R.string.conv_cartao_pct_calculando, pct)
            TempoRestante.MenosDeUmMinuto -> context.getString(R.string.conv_cartao_pct_menos_de_um_min, pct)
            is TempoRestante.Minutos -> context.getString(R.string.conv_cartao_pct_min, pct, tempo.minutos)
            is TempoRestante.HorasEMinutos -> if (tempo.minutos == 0) {
                context.getString(R.string.conv_cartao_pct_h, pct, tempo.horas)
            } else {
                context.getString(R.string.conv_cartao_pct_h_min, pct, tempo.horas, tempo.minutos)
            }
        }
        if (progresso.falhas == 0) return principal
        val falhas = context.resources.getQuantityString(
            R.plurals.conv_cartao_falhas, progresso.falhas, progresso.falhas
        )
        return context.getString(R.string.conv_cartao_com_falhas, principal, falhas)
    }
}
