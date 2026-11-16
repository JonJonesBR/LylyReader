package com.jonjonesbr.audiobookgen.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.util.dpToPx

/**
 * Cores do leitor para um tema de leitura: barras/superfícies e cores dos parágrafos
 * (texto, seleção, busca e sincronia da leitura guiada).
 */
@Suppress("LongParameterList")
internal data class PaletaLeitor(
    val fundo: Int,
    val superficie: Int,
    val texto: Int,
    val suave: Int,
    val clara: Boolean,
    val textoParagrafo: Int,
    val selecao: Int,
    val buscaAtual: Int,
    val buscaMatch: Int,
    val sync: Int,
    val barraSync: Int
)

/** Cor do destaque persistente (T3.5) — amarelo translúcido, legível sobre os 4 temas. */
internal const val COR_DESTAQUE_PERSISTENTE = 0x55FFC107

/** Palavra achada pela busca: laranja forte no resultado atual, amarelo nos demais. */
@Suppress("MagicNumber")
internal val COR_TRECHO_BUSCA_ATUAL = 0xCCFF9800.toInt()
@Suppress("MagicNumber")
internal val COR_TRECHO_BUSCA = 0x88FFEB3B.toInt()

/** Cor de parágrafo marcado como removido no modo de alterações. */
@Suppress("MagicNumber")
internal val COR_PARAGRAFO_REMOVIDO = 0xFFB71C1C.toInt()

// Claro e Sépia têm sync escurecido e barra lateral com contraste >= 3:1 (a11y-4 / WCAG 1.4.11).
@Suppress("MagicNumber")
internal fun paletaDoLeitor(tema: TemaLeitura): PaletaLeitor = when (tema) {
    TemaLeitura.CLARO -> PaletaLeitor(
        fundo = 0xFFF8F8F8.toInt(),
        superficie = 0xFFECECEC.toInt(),
        texto = 0xFF222222.toInt(),
        suave = 0xFF5F5F5F.toInt(),
        clara = true,
        textoParagrafo = 0xFF333333.toInt(),
        selecao = 0xFFD0C0FF.toInt(),
        buscaAtual = 0xFFFFE082.toInt(),
        buscaMatch = 0xFFFFF8E1.toInt(),
        sync = 0xFFDAD2F5.toInt(),
        barraSync = 0xFF6A4CA0.toInt()
    )
    TemaLeitura.ESCURO -> PaletaLeitor(
        fundo = 0xFF1A1A1A.toInt(),
        superficie = 0xFF262626.toInt(),
        texto = 0xFFE6E6E6.toInt(),
        suave = 0xFFA0A0A0.toInt(),
        clara = false,
        textoParagrafo = 0xFFE0E0E0.toInt(),
        selecao = 0xFF4A3070.toInt(),
        buscaAtual = 0xFF5C4A20.toInt(),
        buscaMatch = 0xFF3A3020.toInt(),
        sync = 0xFF2A2040.toInt(),
        barraSync = 0xFFB39DDB.toInt()
    )
    TemaLeitura.SEPIA -> PaletaLeitor(
        fundo = 0xFFF5ECD7.toInt(),
        superficie = 0xFFEADFC4.toInt(),
        texto = 0xFF3B2F1E.toInt(),
        suave = 0xFF7A6A50.toInt(),
        clara = true,
        textoParagrafo = 0xFF3B2A1A.toInt(),
        selecao = 0xFFC8A870.toInt(),
        buscaAtual = 0xFFD4A040.toInt(),
        buscaMatch = 0xFFE8D0A0.toInt(),
        sync = 0xFFDFC99C.toInt(),
        barraSync = 0xFF6B4423.toInt()
    )
    TemaLeitura.OLED -> PaletaLeitor(
        fundo = 0xFF000000.toInt(),
        superficie = 0xFF141414.toInt(),
        texto = 0xFFE0E0E0.toInt(),
        suave = 0xFF9A9A9A.toInt(),
        clara = false,
        textoParagrafo = 0xFFCCCCCC.toInt(),
        selecao = 0xFF4A3070.toInt(),
        buscaAtual = 0xFF5C4A20.toInt(),
        buscaMatch = 0xFF3A3020.toInt(),
        sync = 0xFF2A2040.toInt(),
        barraSync = 0xFFB39DDB.toInt()
    )
}

/** Alfa da bolinha da setinha da barra de cima (rb-66): 0,85 de 255. */
private const val ALFA_DA_SETINHA = 217

/** Folga da bolinha dentro dos 48 dp de toque da setinha: sobra um círculo de 36 dp visíveis (rb-66). */
private const val FOLGA_DA_SETINHA_DP = 6

/** Alfa do trilho do progresso fino da tira da guiada (rb-66, passo 5): 64 de 255, discreto sobre a superfície. */
private const val ALFA_DO_TRILHO_DO_PROGRESSO = 64

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
    // A seta de recolher (rb-66, passo 5) é da barra da guiada: sai da cor do app, como voz, anterior e próximo.
    listOf(R.id.btnGuidedVoz, R.id.btnGuidedPrev, R.id.btnGuidedNext, R.id.btnGuidedRecolher)
        .forEach { findViewById<ImageView>(it)?.imageTintList = principal }
    findViewById<ImageView>(R.id.btnMostrarBarras)?.imageTintList = secundaria
    findViewById<ImageView>(R.id.btnGuidedClose)?.imageTintList = secundaria
    // Progresso fino da tira (rb-66, passo 5): a barra no texto e o trilho discreto, para não ficar com a cor do app.
    findViewById<LinearProgressIndicator>(R.id.progressoGuiadaTira)?.apply {
        setIndicatorColor(p.texto)
        trackColor = ColorUtils.setAlphaComponent(p.suave, ALFA_DO_TRILHO_DO_PROGRESSO)
    }
    // Ícones da barra de status escuros no fundo claro (e claros no escuro).
    WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = p.clara
}
