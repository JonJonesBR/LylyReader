package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.LivroDeExemplo
import com.jonjonesbr.audiobookgen.domain.Licao
import com.jonjonesbr.audiobookgen.domain.LicoesRegras
import kotlinx.coroutines.launch

/**
 * Central "Aprender o app" (Fase J): cinco lições curtas; cada uma leva à tela real, com o livro de exemplo.
 * O progresso vem de eventos reais (ver [com.jonjonesbr.audiobookgen.domain.EventoAprendizado]).
 */
class AprenderActivity : AppCompatActivity() {

    private data class LicaoUi(val licao: Licao, val titulo: Int, val resumo: Int, val passos: Int)

    private val licoes = listOf(
        LicaoUi(Licao.ADICIONAR, R.string.licao_adicionar_titulo, R.string.licao_adicionar_resumo, R.string.licao_adicionar_passos),
        LicaoUi(Licao.GUIADA, R.string.licao_guiada_titulo, R.string.licao_guiada_resumo, R.string.licao_guiada_passos),
        LicaoUi(Licao.GERAR, R.string.licao_gerar_titulo, R.string.licao_gerar_resumo, R.string.licao_gerar_passos),
        LicaoUi(Licao.VOZ, R.string.licao_voz_titulo, R.string.licao_voz_resumo, R.string.licao_voz_passos),
        LicaoUi(Licao.DOWNLOADS, R.string.licao_downloads_titulo, R.string.licao_downloads_resumo, R.string.licao_downloads_passos)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aprender)
        findViewById<ImageButton>(R.id.btnVoltarAprender).setOnClickListener { finish() }

        // Só em build de depuração: dispara "Fazer agora" da lição N sem tocar (o MIUI bloqueia toque por adb).
        val depuravel = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val forcar = intent.getIntExtra(EXTRA_DEBUG_FAZER, -1)
        if (depuravel && forcar in licoes.indices) {
            intent.removeExtra(EXTRA_DEBUG_FAZER)
            fazerAgora(licoes[forcar].licao)
        }
    }

    override fun onResume() {
        super.onResume()
        montar()
    }

    private fun montar() {
        val eventos = AppPrefs(this).eventosAprendizado
        val estado = LicoesRegras.estado(eventos).associate { it.licao to it.concluida }
        val feitas = estado.values.count { it }
        findViewById<TextView>(R.id.tvAprenderProgresso).text = getString(R.string.aprender_progresso, feitas, licoes.size)
        findViewById<ProgressBar>(R.id.pbAprender).apply {
            max = licoes.size
            progress = feitas
        }
        val lista = findViewById<LinearLayout>(R.id.listaLicoes)
        lista.removeAllViews()
        val primaria = MaterialColors.getColor(lista, androidx.appcompat.R.attr.colorPrimary)
        val apagada = androidx.core.content.ContextCompat.getColor(this, R.color.color_on_surface_faint)
        licoes.forEachIndexed { i, ui ->
            val concluida = estado[ui.licao] == true
            val item = LayoutInflater.from(this).inflate(R.layout.item_licao, lista, false)
            item.findViewById<TextView>(R.id.tvLicaoTitulo).text = "${i + 1}. ${getString(ui.titulo)}"
            item.findViewById<TextView>(R.id.tvLicaoResumo).setText(ui.resumo)
            item.findViewById<TextView>(R.id.tvLicaoPassos).text = getString(ui.passos).split("\n")
                .joinToString("\n") { "•  $it" }
            item.findViewById<ImageView>(R.id.ivLicaoEstado).apply {
                imageTintList = ColorStateList.valueOf(if (concluida) primaria else apagada)
                contentDescription = getString(if (concluida) R.string.licao_concluida else R.string.licao_pendente)
            }
            item.findViewById<Button>(R.id.btnLicaoFazer).apply {
                setText(if (concluida) R.string.licao_refazer else R.string.licao_fazer)
                setOnClickListener { fazerAgora(ui.licao) }
            }
            lista.addView(item)
        }
    }

    /** Leva à tela real da lição, com o livro de exemplo quando a lição precisa de um livro. */
    private fun fazerAgora(licao: Licao) {
        when (licao) {
            Licao.ADICIONAR -> startActivity(
                Intent(this, MeusLivrosActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_ABRIR_ADICIONAR, true)
            )
            Licao.DOWNLOADS -> startActivity(Intent(this, DownloadCentralActivity::class.java))
            Licao.GUIADA, Licao.GERAR, Licao.VOZ -> lifecycleScope.launch {
                val livro = runCatching { LivroDeExemplo.garantir(applicationContext) }.getOrNull()
                if (livro == null) {
                    Toast.makeText(this@AprenderActivity, R.string.bib_erro_abrir, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                when (licao) {
                    Licao.GUIADA -> AcoesDoLivro.abrirNoLeitor(this@AprenderActivity, livro, continuar = true)
                    Licao.GERAR -> startActivity(
                        Intent(this@AprenderActivity, LivroActivity::class.java)
                            .putExtra(LivroActivity.EXTRA_ID, livro.id)
                            .putExtra(LivroActivity.EXTRA_ABRIR_PAINEL_GERAR, true)
                    )
                    else -> startActivity(
                        Intent(this@AprenderActivity, LivroActivity::class.java).putExtra(LivroActivity.EXTRA_ID, livro.id)
                    )
                }
            }
        }
    }

    companion object {
        /** Índice (0–4) da lição a disparar sozinha; só vale em build de depuração. */
        const val EXTRA_DEBUG_FAZER = "extra_debug_fazer_licao"
    }
}
