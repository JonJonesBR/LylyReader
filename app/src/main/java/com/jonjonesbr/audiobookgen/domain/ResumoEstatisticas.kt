package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.data.StatsStore

/**
 * Texto de "Compartilhar como texto" nas Estatísticas de leitura. Função pura: as frases vêm de [Frases]
 * (a tela as monta com os recursos, plurais incluídos; o teste usa um fake).
 */
object ResumoEstatisticas {

    /** Frases já traduzidas. Cada método recebe a contagem e devolve o trecho com o plural certo. */
    interface Frases {
        fun cabecalho(): String
        fun minutosOuvidos(quantidade: Int): String
        fun minutosLidos(quantidade: Int): String
        fun livrosConcluidos(quantidade: Int): String
        fun diasSeguidos(quantidade: Int): String

        /** Recebe os minutos de cada dia (mais antigo primeiro), já separados por vírgula. */
        fun ultimos7Dias(minutosPorDia: String): String
    }

    /**
     * Ex.: "Minhas leituras no LylyReader: 30 minutos ouvidos, 36 minutos lidos, 2 livros concluídos, 3 dias seguidos."
     * A segunda linha, com os minutos dos últimos 7 dias, só aparece se houve algum minuto nesse período.
     */
    fun montarTexto(totais: StatsStore.Totais, dias: List<StatsStore.DiaStats>, frases: Frases): String {
        val resumo = listOf(
            frases.minutosOuvidos(totais.totalMinutosOuvidos),
            frases.minutosLidos(totais.totalMinutosLidos),
            frases.livrosConcluidos(totais.livrosConcluidos),
            frases.diasSeguidos(totais.streakDias)
        ).joinToString(", ")
        // Mesmo total do gráfico: ouvido somado ao lido em cada dia.
        val minutosPorDia = dias.map { it.minutosOuvidos + it.minutosLidos }
        val linhaSeteDias = if (minutosPorDia.any { it > 0 }) {
            "\n" + frases.ultimos7Dias(minutosPorDia.joinToString(", "))
        } else {
            ""
        }
        return "${frases.cabecalho()} $resumo.$linhaSeteDias"
    }
}
