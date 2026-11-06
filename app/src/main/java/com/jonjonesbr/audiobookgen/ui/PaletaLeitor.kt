package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.WindowInsetsControllerCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.util.dpToPx

/** Cores do leitor para um tema de leitura: fundo, superfície (barra de busca), texto e texto secundário. */
internal data class PaletaLeitor(
    val fundo: Int,
    val superficie: Int,
    val texto: Int,
    val suave: Int,
    val clara: Boolean
)

internal fun paletaDoLeitor(tema: TemaLeitura): PaletaLeitor = when (tema) {
    TemaLeitura.CLARO -> PaletaLeitor(cor("#F8F8F8"), cor("#ECECEC"), cor("#222222"), cor("#5F5F5F"), clara = true)
    TemaLeitura.ESCURO -> PaletaLeitor(cor("#1A1A1A"), cor("#262626"), cor("#E6E6E6"), cor("#A0A0A0"), clara = false)
    TemaLeitura.SEPIA -> PaletaLeitor(cor("#F5ECD7"), cor("#EADFC4"), cor("#3B2F1E"), cor("#7A6A50"), clara = true)
    TemaLeitura.OLED -> PaletaLeitor(cor("#000000"), cor("#141414"), cor("#E0E0E0"), cor("#9A9A9A"), clara = false)
}

private fun cor(hex: String) = Color.parseColor(hex)

/** Alfa da bolinha da setinha da barra de cima (rb-66): 0,85 de 255. */
private const val ALFA_DA_SETINHA = 217

/** Folga da bolinha dentro dos 48 dp de toque da setinha: sobra um círculo de 36 dp visíveis (rb-66). */
private const val FOLGA_DA_SETINHA_DP = 6

/**
 * Pinta o topo do leitor (título e botões), a barra do capítulo, a busca, a barra da leitura guiada e os avisos
 * com as cores do tema de leitura. Antes só o fundo mudava: no tema Claro ou Sépia, a barra do capítulo e o topo
 * continuavam com as cores do tema escuro do app e o texto sumia no fundo branco.
 */
internal fun Activity.aplicarPaletaNasBarrasDoLeitor(p: PaletaLeitor) {
    findViewById<android.view.View>(R.id.toolbarReader)?.setBackgroundColor(p.fundo)
    // Cabeçalho sobreposto ao texto (rb-66): o fundo do contêiner é o do tema, para o texto não aparecer por baixo.
    findViewById<android.view.View>(R.id.cabecalhoDoLeitor)?.setBackgroundColor(p.fundo)
    // Setinha da barra de cima no vertical (rb-66, passo 4): bolinha da superfície quase opaca, ícone suave. O toque
    // segue nos 48 dp do botão; a folga do InsetDrawable deixa o círculo visível com 36 dp.
    findViewById<ImageView>(R.id.btnMostrarBarras)?.apply {
        val folga = dpToPx(FOLGA_DA_SETINHA_DP)
        val bolinha = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(p.superficie)
            setAlpha(ALFA_DA_SETINHA)
        }
        background = InsetDrawable(bolinha, folga)
    }
    findViewById<android.view.View>(R.id.layoutBuscaReader)?.setBackgroundColor(p.superficie)
    val principal = ColorStateList.valueOf(p.texto)
    val secundaria = ColorStateList.valueOf(p.suave)
    listOf(
        R.id.btnReaderVoltar, R.id.btnBuscarReader, R.id.btnBookmarkReader, R.id.btnReaderOpcoes, R.id.btnBuscaFechar
    ).forEach { findViewById<ImageView>(it)?.imageTintList = principal }
    listOf(
        R.id.btnCapituloAnterior, R.id.btnCapituloProximo, R.id.ivAbrirCapitulos,
        R.id.btnBuscaAnterior, R.id.btnBuscaProximo
    ).forEach { findViewById<ImageView>(it)?.imageTintList = secundaria }
    listOf(R.id.tvReaderTitulo, R.id.tvCapituloAtual, R.id.tvReaderErro)
        .forEach { findViewById<TextView>(it)?.setTextColor(p.texto) }
    listOf(R.id.tvProgressoLeitura, R.id.tvCarregandoReader, R.id.tvBuscaContador)
        .forEach { findViewById<TextView>(it)?.setTextColor(p.suave) }
    findViewById<EditText>(R.id.etBuscaReader)?.apply {
        setTextColor(p.texto)
        setHintTextColor(p.suave)
    }
    // Barra da leitura guiada (activity_reader.xml): tudo junto; no Sépia e no OLED ela ficava com a cor do app.
    // O botão de play/pause fica com o destaque próprio (fundo arredondado), por isso não entra aqui.
    findViewById<android.view.View>(R.id.layoutGuidedPlayer)?.setBackgroundColor(p.superficie)
    findViewById<android.view.View>(R.id.divisorGuidedPlayer)?.setBackgroundColor(p.suave)
    listOf(R.id.tvGuidedStatus, R.id.tvGuidedSoneca, R.id.tvGuidedPassos, R.id.btnGuidedVelocidade)
        .forEach { findViewById<TextView>(it)?.setTextColor(p.texto) }
    listOf(R.id.btnGuidedVoz, R.id.btnGuidedPrev, R.id.btnGuidedNext)
        .forEach { findViewById<ImageView>(it)?.imageTintList = principal }
    findViewById<ImageView>(R.id.btnMostrarBarras)?.imageTintList = secundaria
    findViewById<ImageView>(R.id.btnGuidedClose)?.imageTintList = secundaria
    // Ícones da barra de status escuros no fundo claro (e claros no escuro).
    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = p.clara
}
