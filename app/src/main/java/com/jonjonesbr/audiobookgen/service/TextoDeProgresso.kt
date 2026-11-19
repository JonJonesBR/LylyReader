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

    /**
     * Ex.: "18% · faltam cerca de 2 min"; com falhas na 1ª passada, "· 1 trecho será refeito automaticamente".
     * Nas rodadas de retry, "18% · Refazendo 1 trecho (tentativa 1 de 3), aguarde…" (sem tempo nem falhas).
     * Conexão lenta: uma 2ª linha com o aviso.
     */
    fun frase(context: Context, progresso: ProgressoEstruturado): String {
        val linha = if (progresso.refazendo > 0) {
            fraseDeRefazer(context, progresso)
        } else {
            fraseDeAndamento(context, progresso)
        }
        return if (progresso.lenta) {
            "$linha\n" + context.getString(R.string.conv_cartao_conexao_lenta)
        } else {
            linha
        }
    }

    private fun fraseDeRefazer(context: Context, progresso: ProgressoEstruturado): String {
        val pct = context.getString(R.string.conv_cartao_pct_simples, progresso.pct)
        val refazendo = context.resources.getQuantityString(
            R.plurals.conv_cartao_refazendo,
            progresso.refazendo,
            progresso.refazendo,
            progresso.rodada,
            progresso.rodadas,
        )
        return context.getString(R.string.conv_cartao_com_falhas, pct, refazendo)
    }

    private fun fraseDeAndamento(context: Context, progresso: ProgressoEstruturado): String {
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
