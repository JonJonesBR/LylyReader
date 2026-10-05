package com.jonjonesbr.audiobookgen.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.StatsStore

/**
 * Estatísticas de leitura/escuta — 100% locais, ver [StatsStore]. Sem gráfico de biblioteca:
 * o histórico dos últimos 7 dias é desenhado com barras simples ([View] com altura calculada).
 */
class StatisticsActivity : AppCompatActivity() {

    private lateinit var llGraficoDias: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        findViewById<ImageButton>(R.id.btnVoltarStats).setOnClickListener { finish() }
        llGraficoDias = findViewById(R.id.llGraficoDias)

        carregarEstatisticas()
    }

    private fun carregarEstatisticas() {
        val totais = StatsStore.totais(this)
        findViewById<TextView>(R.id.tvStatsMinutosOuvidos).text = totais.totalMinutosOuvidos.toString()
        findViewById<TextView>(R.id.tvStatsMinutosLidos).text = totais.totalMinutosLidos.toString()
        findViewById<TextView>(R.id.tvStatsLivrosConcluidos).text = totais.livrosConcluidos.toString()
        findViewById<TextView>(R.id.tvStatsStreak).text = totais.streakDias.toString()

        desenharGraficoDias()
    }

    private fun desenharGraficoDias() {
        llGraficoDias.removeAllViews()
        val dias = StatsStore.ultimosSeteDias(this)
        val maxMinutos = dias.maxOf { it.minutosOuvidos + it.minutosLidos }.coerceAtLeast(1)

        dias.forEach { dia ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_stats_dia, llGraficoDias, false)
            val totalDia = dia.minutosOuvidos + dia.minutosLidos

            item.findViewById<TextView>(R.id.tvDiaMinutos).text = totalDia.toString()
            item.findViewById<TextView>(R.id.tvDiaLabel).text = diaDoMes(dia.data)

            val container = item.findViewById<FrameLayout>(R.id.containerBarraDia)
            val barra = item.findViewById<android.view.View>(R.id.barraDia)
            val alturaMaximaPx = (BAR_MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()
            container.post {
                val alturaPx = (alturaMaximaPx * totalDia / maxMinutos).coerceAtLeast(if (totalDia > 0) 2 else 0)
                barra.layoutParams = barra.layoutParams.apply { height = alturaPx }
                barra.requestLayout()
            }

            llGraficoDias.addView(item)
        }
    }

    /** Dia do mês (ex.: "24") extraído de uma data "yyyy-MM-dd" — rótulo simples, sem depender de locale. */
    private fun diaDoMes(dataIso: String): String =
        dataIso.split("-").getOrNull(2)?.toIntOrNull()?.toString() ?: "?"

    companion object {
        private const val BAR_MAX_HEIGHT_DP = 90
    }
}
