package com.jonjonesbr.audiobookgen.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.StatsStore
import com.jonjonesbr.audiobookgen.domain.ResumoEstatisticas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Estatísticas de leitura/escuta — 100% locais, ver [StatsStore]. Sem gráfico de biblioteca:
 * o histórico dos últimos 7 dias é desenhado com barras simples ([View] com altura calculada).
 * Compartilhar: como texto (Intent de texto) ou como imagem (cartão desenhado num Bitmap).
 */
class StatisticsActivity : AppCompatActivity() {

    private lateinit var llGraficoDias: LinearLayout
    private val dialogos = DialogosAbertos(this)

    /** Frases do texto compartilhado. Os plurais vêm dos recursos, então o resumo sai certo em cada idioma. */
    private val frasesCompartilhar = object : ResumoEstatisticas.Frases {
        override fun cabecalho() = getString(R.string.stats_compartilhar_cabecalho)
        override fun minutosOuvidos(quantidade: Int) =
            pluralSemZeroNoSingular(R.plurals.stats_compartilhar_minutos_ouvidos, quantidade)
        override fun minutosLidos(quantidade: Int) =
            pluralSemZeroNoSingular(R.plurals.stats_compartilhar_minutos_lidos, quantidade)
        override fun livrosConcluidos(quantidade: Int) =
            pluralSemZeroNoSingular(R.plurals.stats_compartilhar_livros, quantidade)
        override fun diasSeguidos(quantidade: Int) =
            pluralSemZeroNoSingular(R.plurals.stats_compartilhar_dias, quantidade)
        override fun ultimos7Dias(minutosPorDia: String) =
            getString(R.string.stats_compartilhar_ultimos_7_dias, minutosPorDia)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_statistics)

        findViewById<ImageButton>(R.id.btnVoltarStats).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnCompartilharStats).setOnClickListener { escolherFormatoCompartilhar() }
        llGraficoDias = findViewById(R.id.llGraficoDias)

        carregarEstatisticas()
    }

    private fun carregarEstatisticas() {
        preencherNumeros(window.decorView, StatsStore.totais(this))
        preencherGrafico(llGraficoDias, StatsStore.ultimosSeteDias(this))
    }

    /** Preenche os quatro números. [raiz] é a tela ou o cartão de compartilhar (os dois usam o mesmo bloco). */
    private fun preencherNumeros(raiz: View, totais: StatsStore.Totais) {
        raiz.findViewById<TextView>(R.id.tvStatsMinutosOuvidos).text = totais.totalMinutosOuvidos.toString()
        raiz.findViewById<TextView>(R.id.tvStatsMinutosLidos).text = totais.totalMinutosLidos.toString()
        raiz.findViewById<TextView>(R.id.tvStatsLivrosConcluidos).text = totais.livrosConcluidos.toString()
        raiz.findViewById<TextView>(R.id.tvStatsStreak).text = totais.streakDias.toString()
    }

    private fun preencherGrafico(llGrafico: LinearLayout, dias: List<StatsStore.DiaStats>) {
        llGrafico.removeAllViews()
        val maxMinutos = dias.maxOf { it.minutosOuvidos + it.minutosLidos }.coerceAtLeast(1)
        val alturaMaximaPx = (BAR_MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()

        dias.forEach { dia ->
            val item = LayoutInflater.from(this).inflate(R.layout.item_stats_dia, llGrafico, false)
            val totalDia = dia.minutosOuvidos + dia.minutosLidos

            item.findViewById<TextView>(R.id.tvDiaMinutos).text = totalDia.toString()
            item.findViewById<TextView>(R.id.tvDiaLabel).text = diaDoMes(dia.data)

            val barra = item.findViewById<View>(R.id.barraDia)
            val alturaPx = (alturaMaximaPx * totalDia / maxMinutos).coerceAtLeast(if (totalDia > 0) 2 else 0)
            barra.layoutParams = barra.layoutParams.apply { height = alturaPx }

            llGrafico.addView(item)
        }
    }

    /**
     * Plural do Android com a contagem real. Em português o 0 cai na forma "one" ("0 minuto ouvido"),
     * então o 0 pede a forma "other" (2 cai nela nos três idiomas). O número formatado continua sendo o 0.
     */
    private fun pluralSemZeroNoSingular(id: Int, quantidade: Int): String =
        resources.getQuantityString(id, if (quantidade == 0) 2 else quantidade, quantidade)

    /** Dia do mês (ex.: "24") extraído de uma data "yyyy-MM-dd" — rótulo simples, sem depender de locale. */
    private fun diaDoMes(dataIso: String): String =
        dataIso.split("-").getOrNull(2)?.toIntOrNull()?.toString() ?: "?"

    private fun escolherFormatoCompartilhar() {
        dialogos.mostrar(
            AlertDialog.Builder(this)
                .setTitle(R.string.stats_compartilhar_cd)
                .setItems(
                    arrayOf(
                        getString(R.string.stats_compartilhar_como_imagem),
                        getString(R.string.stats_compartilhar_como_texto)
                    )
                ) { _, qual ->
                    if (qual == 0) compartilharComoImagem() else compartilharComoTexto()
                }
                .setNegativeButton(android.R.string.cancel, null)
        )
    }

    private fun compartilharComoTexto() {
        val texto = ResumoEstatisticas.montarTexto(
            StatsStore.totais(this),
            StatsStore.ultimosSeteDias(this),
            frasesCompartilhar
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.stats_compartilhar_cd)))
    }

    /** Desenho do cartão na thread principal (é uma View); a gravação do PNG vai para IO. */
    private fun compartilharComoImagem() {
        val totais = StatsStore.totais(this)
        val dias = StatsStore.ultimosSeteDias(this)
        lifecycleScope.launch {
            val bitmap = desenharCartao(totais, dias)
            val uri = try {
                val arquivo = withContext(Dispatchers.IO) { gravarPng(bitmap) }
                FileProvider.getUriForFile(this@StatisticsActivity, "$packageName.fileprovider", arquivo)
            } catch (e: IOException) {
                Log.w(TAG, "Falha ao gravar o PNG das estatísticas", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "FileProvider recusou o PNG das estatísticas", e)
                null
            }
            bitmap.recycle()
            if (uri == null) {
                Toast.makeText(this@StatisticsActivity, R.string.stats_compartilhar_erro, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.stats_compartilhar_cd)))
        }
    }

    /** Infla o cartão, preenche e desenha num Bitmap de largura fixa. As cores são as do tema desta Activity. */
    private fun desenharCartao(totais: StatsStore.Totais, dias: List<StatsStore.DiaStats>): Bitmap {
        val cartao = LayoutInflater.from(this).inflate(R.layout.cartao_compartilhar_estatisticas, null, false)
        preencherNumeros(cartao, totais)
        preencherGrafico(cartao.findViewById<LinearLayout>(R.id.llGraficoDias), dias)

        val largura = (CARTAO_LARGURA_DP * resources.displayMetrics.density).toInt()
        cartao.measure(
            View.MeasureSpec.makeMeasureSpec(largura, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        cartao.layout(0, 0, cartao.measuredWidth, cartao.measuredHeight)

        val bitmap = Bitmap.createBitmap(cartao.measuredWidth, cartao.measuredHeight, Bitmap.Config.ARGB_8888)
        cartao.draw(Canvas(bitmap))
        return bitmap
    }

    /** Grava o PNG em cacheDir/compartilhar (o caminho está em res/xml/file_paths.xml). Fora da thread principal. */
    private fun gravarPng(bitmap: Bitmap): File {
        val pasta = File(cacheDir, "compartilhar").apply { mkdirs() }
        val arquivo = File(pasta, "estatisticas.png")
        FileOutputStream(arquivo).use { saida -> bitmap.compress(Bitmap.CompressFormat.PNG, QUALIDADE_PNG, saida) }
        return arquivo
    }

    companion object {
        private const val TAG = "StatisticsActivity"
        private const val BAR_MAX_HEIGHT_DP = 90
        private const val CARTAO_LARGURA_DP = 360
        private const val QUALIDADE_PNG = 100
    }
}
