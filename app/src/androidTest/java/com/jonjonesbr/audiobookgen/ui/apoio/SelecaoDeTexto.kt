package com.jonjonesbr.audiobookgen.ui.apoio

import android.graphics.PointF
import android.graphics.Rect
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.ui.ReaderActivity
import org.junit.Assert.fail
import kotlin.math.abs

/** Início, fim e texto de uma seleção de um parágrafo. */
data class Selecao(val inicio: Int, val fim: Int, val texto: String)

/**
 * Topo da linha do início da seleção e fundo da barra, em coordenadas da tela (rb-48). O retângulo da barra é só
 * para as medidas.
 */
data class LinhaEBarra(val topoDaLinha: Int, val fundoDaBarra: Int, val rectBarra: String)

/** Até quanto esperar a seleção mudar ou a barra descer depois de um gesto (rb-48). */
const val TEMPO_PARA_SELECAO_MUDAR_MS = 2_000L

/** Intervalo entre duas leituras de seleção ou de medidas nas esperas de seleção. */
const val PAUSA_ENTRE_LEITURAS_MS = 100L

/** Até quanto esperar a lista parar e a palavra ficar no mesmo ponto antes de medir para o toque (rb-46 U4). */
const val TEMPO_PARA_LISTA_PARAR_MS = 3_000L

/** Intervalo entre as duas leituras que, iguais, dão a lista por parada (rb-46 U4). */
private const val INTERVALO_DE_ESTABILIDADE_MS = 150L

/** Quanto o ponto da palavra pode andar entre duas leituras e ainda contar como parado, em pixels. */
private const val TOLERANCIA_DE_PONTO_PX = 2f

/** Toques longos numa palavra antes de desistir: o primeiro e mais dois, cada um depois de limpar a seleção. */
private const val TENTATIVAS_DE_SELECAO = 3

/**
 * Garante que o parágrafo esteja na tela: se não estiver, rola a lista até ele ficar no topo (rolagem programática,
 * sem toque). Espera a View aparecer. Use com a leitura guiada tocando: o teste precisa do parágrafo para tocar nele.
 */
fun garantirParagrafoNaTela(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int) {
    scenario.onActivity { a ->
        val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
        if (lista.findViewHolderForAdapterPosition(indiceParagrafo) == null) {
            (lista.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(indiceParagrafo, 0)
        }
    }
    esperarAte(scenario, "parágrafo $indiceParagrafo na tela") { a ->
        a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
            .findViewHolderForAdapterPosition(indiceParagrafo) != null
    }
}

/** Folga, em dp, entre o ponto do toque e a borda de uma área ocupada (barra da guiada, Snackbar, botão). */
private const val FOLGA_DA_AREA_OCUPADA_DP = 8f

/** Quantos arrastes da lista [garantirPalavraVisivel] faz antes de desistir. */
private const val ARRASTES_DA_LISTA_MAXIMOS = 3

/** Quantas medidas [garantirPalavraVisivel] faz no total (um Snackbar que some também conta). */
private const val MEDIDAS_DE_VISIBILIDADE_MAXIMAS = 6

/** Espera, depois de um arraste, antes de medir de novo (a barra da guiada e a seleção se acomodam). */
private const val PAUSA_APOS_ARRASTE_MS = 300L

/** Até quanto esperar o Snackbar sair da tela (ele some sozinho, LENGTH_SHORT). */
private const val TEMPO_PARA_SNACKBAR_SUMIR_MS = 4_000L

/**
 * Medida, na thread principal, de onde a palavra está e do que a cobre. Com [problemas] vazio, o toque é seguro.
 * [precisaSubir]: a palavra está embaixo da área útil (o dedo sobe); senão está em cima (o dedo desce).
 * [obstaculoTemporario]: um Snackbar cobre o ponto, e ele some sozinho (não se arrasta a lista por isso).
 * [listaParada]: a lista está com o RecyclerView em repouso (SCROLL_STATE_IDLE).
 */
private class MedidaDeVisibilidade(
    val ponto: PointF?,
    val problemas: List<String>,
    val precisaSubir: Boolean,
    val obstaculoTemporario: Boolean,
    val listaRect: Rect,
    val topoUtil: Int,
    val fundoUtil: Int,
    val listaParada: Boolean,
    val relato: String,
)

/**
 * Garante que a palavra [palavra] do parágrafo [indiceParagrafo] esteja num lugar onde um toque pega o texto, e
 * devolve o ponto já validado. Mede na thread principal: o ponto tem de ficar abaixo do topo útil da lista (ou do
 * fundo da barra de seleção, se ela estiver visível), acima da barra da guiada com uma folga, fora do botão
 * "Voltar à narração" e fora de um Snackbar. Se não estiver, arrasta a lista com um dedo de verdade (até três
 * arrastes): o arraste passa por DRAGGING, então a guiada para de puxar a lista de volta ao parágrafo atual.
 *
 * Com o ponto em lugar seguro, espera a lista parar ([esperarMedidaEstavel]) antes de devolvê-lo: a guiada rola a
 * lista para acompanhar o trecho, e um ponto medido no meio dessa rolagem cai uma linha abaixo (rb-46 U4).
 *
 * Cada medida vai para medidas.txt com o nome [cenario]. Se o parágrafo nem tem View (fora da lista), o arraste vai
 * para o lado dele. Falha com a medida da última tentativa se a palavra não couber em nenhum lugar seguro.
 */
fun garantirPalavraVisivel(
    scenario: ActivityScenario<ReaderActivity>,
    indiceParagrafo: Int,
    palavra: String,
    cenario: String,
): PointF {
    var arrastes = 0
    var medidas = 0
    var ultima: MedidaDeVisibilidade? = null
    while (medidas < MEDIDAS_DE_VISIBILIDADE_MAXIMAS) {
        medidas++
        val medida = medirVisibilidade(scenario, indiceParagrafo, palavra)
        val pronta = if (medida.ponto != null && medida.problemas.isEmpty()) {
            esperarMedidaEstavel(scenario, indiceParagrafo, palavra, cenario)
        } else {
            medida
        }
        ultima = pronta
        val ponto = pronta.ponto
        if (ponto != null && pronta.problemas.isEmpty()) return ponto
        Capturas.anotar(cenario, "visibilidade de '$palavra' (medida $medidas): ${pronta.relato}")
        if (pronta.obstaculoTemporario) {
            esperarSnackbarSumir(scenario)
            continue
        }
        if (arrastes == ARRASTES_DA_LISTA_MAXIMOS) break
        arrastarLista(pronta)
        arrastes++
        esperarListaParada(scenario)
    }
    throw AssertionError(
        "A palavra '$palavra' do parágrafo $indiceParagrafo não ficou em lugar seguro para o toque depois de " +
            "$arrastes arrastes da lista. Última medida: ${ultima?.relato}"
    )
}

/** Mede [palavra] e o que a cobre, numa só passagem pela thread principal. */
private fun medirVisibilidade(
    scenario: ActivityScenario<ReaderActivity>,
    indiceParagrafo: Int,
    palavra: String,
): MedidaDeVisibilidade {
    var medida: MedidaDeVisibilidade? = null
    scenario.onActivity { a -> medida = medirNaTela(a, indiceParagrafo, palavra) }
    return checkNotNull(medida) { "Sem medida de visibilidade para '$palavra' no parágrafo $indiceParagrafo" }
}

/** Retângulo visível (em coordenadas de tela) de [view], ou nulo se ela não estiver visível. */
private fun retanguloVisivel(view: View?): Rect? {
    if (view == null || view.visibility != View.VISIBLE) return null
    val rect = Rect()
    return if (view.getGlobalVisibleRect(rect)) rect else null
}

/** A conta da medida, na thread principal. Ver [garantirPalavraVisivel] para as regras. */
private fun medirNaTela(a: ReaderActivity, indiceParagrafo: Int, palavra: String): MedidaDeVisibilidade {
    val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
    val listaRect = retanguloVisivel(lista) ?: Rect()
    val guia = retanguloVisivel(a.findViewById<View>(R.id.layoutGuidedPlayer))
    val barraDeTexto = retanguloVisivel(a.findViewById<View>(R.id.barraSelecaoTexto))
    val barraDeVarios = retanguloVisivel(a.findViewById<View>(R.id.layoutBarraSelecao))
    val botaoVoltar = retanguloVisivel(a.findViewById<View>(R.id.fabVoltarNarracao))
    val textoDoSnackbar: View? = a.findViewById(com.google.android.material.R.id.snackbar_text)
    val snackbar = textoDoSnackbar?.let { retanguloVisivel(it.parent as? View ?: it) }

    val topoUtil = maxOf(listaRect.top, barraDeTexto?.bottom ?: 0, barraDeVarios?.bottom ?: 0)
    val fundoUtil = minOf(listaRect.bottom, guia?.top ?: listaRect.bottom)
    val densidade = lista.resources.displayMetrics.density
    val folga = FOLGA_DA_AREA_OCUPADA_DP * densidade

    val problemas = mutableListOf<String>()
    var precisaSubir = false
    var obstaculoTemporario = false
    val tv = lista.findViewHolderForAdapterPosition(indiceParagrafo)?.itemView as? TextView
    val ponto = tv?.let { Gestos.pontoDaPalavraNoTexto(it, indiceParagrafo, palavra) }
    if (ponto == null) {
        // Sem View do parágrafo: a palavra está fora da lista. Arrasta para o lado dele.
        val lm = lista.layoutManager as LinearLayoutManager
        val primeiro = lm.findFirstVisibleItemPosition()
        precisaSubir = primeiro == RecyclerView.NO_POSITION || indiceParagrafo > primeiro
        problemas += "sem View do parágrafo (fora da lista)"
    } else {
        if (listaRect.isEmpty) {
            problemas += "lista fora da tela"
        } else if (ponto.y < topoUtil + folga) {
            problemas += "acima da área útil (topo $topoUtil, folga ${folga.toInt()}px)"
        } else if (ponto.y > fundoUtil - folga) {
            problemas += "embaixo da barra da guiada (fundo útil $fundoUtil)"
            precisaSubir = true
        }
        if (botaoVoltar?.contains(ponto.x.toInt(), ponto.y.toInt()) == true) {
            problemas += "coberta pelo botão Voltar à narração"
            precisaSubir = ponto.y > listaRect.centerY()
        }
        if (snackbar?.contains(ponto.x.toInt(), ponto.y.toInt()) == true) {
            problemas += "coberta pelo Snackbar"
            obstaculoTemporario = true
        }
    }
    val relato = "ponto=${ponto?.let { "(${it.x.toInt()},${it.y.toInt()})" } ?: "nenhum"} " +
        "lista=${listaRect.toShortString()} topoUtil=$topoUtil fundoUtil=$fundoUtil " +
        "guia=${guia?.toShortString()} voltar=${botaoVoltar?.toShortString()} " +
        "snackbar=${snackbar?.toShortString()} problemas=$problemas"
    return MedidaDeVisibilidade(
        ponto = ponto,
        problemas = problemas,
        precisaSubir = precisaSubir,
        obstaculoTemporario = obstaculoTemporario,
        listaRect = listaRect,
        topoUtil = topoUtil,
        fundoUtil = fundoUtil,
        listaParada = lista.scrollState == RecyclerView.SCROLL_STATE_IDLE,
        relato = relato,
    )
}

/**
 * Lê a palavra a cada [INTERVALO_DE_ESTABILIDADE_MS] até duas leituras seguidas darem a lista parada e o ponto no
 * mesmo lugar ([pontoParado]), ou até [TEMPO_PARA_LISTA_PARAR_MS]. Devolve a última leitura. No tempo-limite, anota
 * e devolve a última mesmo assim: quem chama decide com o que sobrou.
 */
private fun esperarMedidaEstavel(
    scenario: ActivityScenario<ReaderActivity>,
    indiceParagrafo: Int,
    palavra: String,
    cenario: String,
): MedidaDeVisibilidade {
    val inicio = System.currentTimeMillis()
    val fim = inicio + TEMPO_PARA_LISTA_PARAR_MS
    var anterior = medirVisibilidade(scenario, indiceParagrafo, palavra)
    var leituras = 1
    while (System.currentTimeMillis() < fim) {
        Thread.sleep(INTERVALO_DE_ESTABILIDADE_MS)
        val atual = medirVisibilidade(scenario, indiceParagrafo, palavra)
        leituras++
        if (pontoParado(anterior, atual)) {
            if (leituras > 2) {
                val esperado = System.currentTimeMillis() - inicio
                Capturas.anotar(cenario, "lista parou depois de $esperado ms: ${atual.relato}")
            }
            return atual
        }
        anterior = atual
    }
    Capturas.anotar(cenario, "lista não parou em $TEMPO_PARA_LISTA_PARAR_MS ms: ${anterior.relato}")
    return anterior
}

/**
 * Duas leituras contam como paradas se a lista está em repouso nas duas e a palavra não andou além da tolerância.
 */
private fun pontoParado(antes: MedidaDeVisibilidade, depois: MedidaDeVisibilidade): Boolean {
    val a = antes.ponto ?: return false
    val b = depois.ponto ?: return false
    return antes.listaParada && depois.listaParada &&
        abs(a.x - b.x) <= TOLERANCIA_DE_PONTO_PX && abs(a.y - b.y) <= TOLERANCIA_DE_PONTO_PX
}

/**
 * Arrasta a lista com um dedo, no meio da área útil, andando um terço dela no sentido que leva a palavra para dentro
 * da área: o dedo sobe quando [MedidaDeVisibilidade.precisaSubir], senão desce. Falha se não houver área útil.
 */
private fun arrastarLista(medida: MedidaDeVisibilidade) {
    val altura = medida.fundoUtil - medida.topoUtil
    check(altura > 0) { "Sem área útil para arrastar a lista: ${medida.relato}" }
    val centro = (medida.topoUtil + medida.fundoUtil) / 2
    val meioTerco = altura / 6
    val x = medida.listaRect.centerX().toFloat()
    val (y0, y1) = if (medida.precisaSubir) {
        (centro + meioTerco) to (centro - meioTerco)
    } else {
        (centro - meioTerco) to (centro + meioTerco)
    }
    Gestos.arrastar(x, y0.toFloat(), x, y1.toFloat())
}

/** Espera a lista voltar ao repouso depois de um arraste, e uma pausa para a barra se acomodar. */
private fun esperarListaParada(scenario: ActivityScenario<ReaderActivity>) {
    esperarAte(scenario, "lista parada depois do arraste") { a ->
        a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos).scrollState == RecyclerView.SCROLL_STATE_IDLE
    }
    Thread.sleep(PAUSA_APOS_ARRASTE_MS)
}

/** Espera o Snackbar sair da tela, até [TEMPO_PARA_SNACKBAR_SUMIR_MS]. Não falha: quem chama mede de novo. */
private fun esperarSnackbarSumir(scenario: ActivityScenario<ReaderActivity>) {
    val fim = System.currentTimeMillis() + TEMPO_PARA_SNACKBAR_SUMIR_MS
    while (System.currentTimeMillis() < fim && snackbarVisivel(scenario)) {
        Thread.sleep(PAUSA_ENTRE_LEITURAS_MS)
    }
}

/** O texto do Snackbar do Material está visível agora? Lido na thread principal. */
private fun snackbarVisivel(scenario: ActivityScenario<ReaderActivity>): Boolean {
    var visivel = false
    scenario.onActivity { a ->
        visivel = a.findViewById<View>(com.google.android.material.R.id.snackbar_text)?.isShown == true
    }
    return visivel
}

/** Seleção atual do parágrafo [indiceParagrafo], lida na thread principal; nula se não há seleção. */
fun selecaoAtual(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int): Selecao? {
    var selecao: Selecao? = null
    scenario.onActivity { a ->
        val tv = Gestos.textoDoParagrafo(a, indiceParagrafo)
        if (tv.hasSelection()) {
            val inicio = tv.selectionStart
            val fim = tv.selectionEnd
            selecao = Selecao(inicio, fim, tv.text.subSequence(inicio, fim).toString())
        }
    }
    return selecao
}

/**
 * Toque longo numa palavra do parágrafo [indiceParagrafo] e espera a barra da seleção aparecer com a seleção no
 * TextView. [regras] fecha os diálogos do sistema antes do toque. Falha com a mensagem da espera se a seleção não
 * abrir. Depois espera a barra estabilizar (a descida da lista, rb-48).
 *
 * Antes do toque, uma seleção já aberta é fechada por código ([limparSelecaoAberta]): com ela aberta, o toque
 * longo cai no menu flutuante do sistema (rb-46 U4). Depois do toque, a seleção tem de conter [palavra]; se não
 * contiver, limpa e tenta de novo, até [TENTATIVAS_DE_SELECAO] toques. Cada tentativa vai para medidas.txt com o
 * nome [cenario]. Se nenhuma der a palavra, falha com a última seleção.
 */
fun selecionarPalavra(
    scenario: ActivityScenario<ReaderActivity>,
    regras: RegrasDoLeitor,
    indiceParagrafo: Int,
    palavra: String,
    cenario: String = "selecao",
) {
    limparSelecaoAberta(scenario, indiceParagrafo)
    var ultima: Selecao? = null
    repeat(TENTATIVAS_DE_SELECAO) { tentativa ->
        if (tentativa > 0) {
            val motivo = "seleção sem '$palavra' (última: ${ultima ?: "nenhuma"})"
            Capturas.anotar(cenario, "$motivo: tentativa ${tentativa + 1} de $TENTATIVAS_DE_SELECAO")
            limparSelecaoAberta(scenario, indiceParagrafo)
        }
        val ponto = pontoEstavelDaPalavra(scenario, indiceParagrafo, palavra, cenario)
        regras.fecharDialogosDoSistema()
        Gestos.toqueLongo(ponto.x, ponto.y)
        esperarAte(scenario, "barra da seleção aberta depois do toque longo em '$palavra'") { a ->
            a.findViewById<View>(R.id.barraSelecaoTexto).visibility == View.VISIBLE &&
                Gestos.textoDoParagrafo(a, indiceParagrafo).hasSelection()
        }
        esperarLinhaAbaixoDaBarra(scenario, indiceParagrafo)
        ultima = selecaoAtual(scenario, indiceParagrafo)
        if (ultima?.texto?.contains(palavra) == true) return
    }
    fail(
        "A seleção do toque longo em '$palavra' não contém a palavra, em $TENTATIVAS_DE_SELECAO tentativas; " +
            "última seleção: ${ultima ?: "nenhuma"}. Se a lista não parou, veja as anotações " +
            "'lista não parou' em medidas.txt"
    )
}

/**
 * Fecha a seleção aberta do parágrafo [indiceParagrafo] por código: tira a seleção do TextView e o foco dele, o
 * que encerra o menu da seleção, e a barra some (ParagrafoAdapter, no onDestroyActionMode). Não faz nada se não há
 * seleção. É preparação do teste, não o comportamento testado. Espera a barra sumir e a lista voltar ao lugar
 * (rb-48), para o próximo toque medir a tela já parada.
 */
fun limparSelecaoAberta(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int) {
    if (selecaoAtual(scenario, indiceParagrafo) == null) return
    scenario.onActivity { a ->
        val tv = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
            .findViewHolderForAdapterPosition(indiceParagrafo)?.itemView as? TextView
        if (tv != null) {
            (tv.text as? Spannable)?.let { Selection.removeSelection(it) }
            tv.clearFocus()
        }
    }
    esperarAte(scenario, "barra da seleção fechada e lista de volta ao lugar depois de limpar a seleção") { a ->
        a.findViewById<View>(R.id.barraSelecaoTexto).visibility != View.VISIBLE &&
            a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos).translationY == 0f
    }
}

/**
 * Ponto da palavra depois que a lista para (ver [esperarMedidaEstavel]). A guiada rola a lista para acompanhar o
 * trecho, e o ponto medido durante a rolagem pode cair uma linha abaixo do alvo (rb-46 U4).
 */
private fun pontoEstavelDaPalavra(
    scenario: ActivityScenario<ReaderActivity>,
    indiceParagrafo: Int,
    palavra: String,
    cenario: String,
): PointF {
    val medida = esperarMedidaEstavel(scenario, indiceParagrafo, palavra, cenario)
    return checkNotNull(medida.ponto) {
        "Sem ponto para a palavra '$palavra' do parágrafo $indiceParagrafo: ${medida.relato}"
    }
}

/**
 * Mede, na thread principal, a linha do início da seleção do parágrafo e a base da barra, ambas em coordenadas da
 * tela. Nulo sem seleção ou sem layout. Mesma conta de linhaDoInicioDaSelecaoNaTela (rb-48).
 */
fun medirLinhaEBarra(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int): LinhaEBarra? {
    var medida: LinhaEBarra? = null
    scenario.onActivity { a ->
        val tv = Gestos.textoDoParagrafo(a, indiceParagrafo)
        val layout = tv.layout
        if (layout == null || !tv.hasSelection()) return@onActivity
        val inicio = minOf(tv.selectionStart, tv.selectionEnd)
        val posTv = IntArray(2)
        tv.getLocationOnScreen(posTv)
        val base = posTv[1] + tv.totalPaddingTop - tv.scrollY
        val topo = base + layout.getLineTop(layout.getLineForOffset(inicio))
        val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
        val posBarra = IntArray(2)
        barra.getLocationOnScreen(posBarra)
        val rectBarra = Rect()
        barra.getGlobalVisibleRect(rectBarra)
        medida = LinhaEBarra(topo, posBarra[1] + barra.height, rectBarra.toShortString())
    }
    return medida
}

/**
 * Lê a linha e a barra a cada [PAUSA_ENTRE_LEITURAS_MS] até a linha ficar abaixo da barra ou o tempo acabar. Não
 * falha: devolve a última medida, e quem chama decide o que fazer com ela.
 */
fun esperarLinhaAbaixoDaBarra(
    scenario: ActivityScenario<ReaderActivity>,
    indiceParagrafo: Int,
    timeoutMs: Long = TEMPO_PARA_SELECAO_MUDAR_MS,
): LinhaEBarra? {
    val fim = System.currentTimeMillis() + timeoutMs
    var medida = medirLinhaEBarra(scenario, indiceParagrafo)
    while ((medida == null || medida.topoDaLinha < medida.fundoDaBarra) && System.currentTimeMillis() < fim) {
        Thread.sleep(PAUSA_ENTRE_LEITURAS_MS)
        medida = medirLinhaEBarra(scenario, indiceParagrafo)
    }
    return medida
}

/** Espera até 2 s a seleção do parágrafo terminar depois de [fimAntes]; nulo se ela não crescer. */
fun esperarFimMaiorQue(scenario: ActivityScenario<ReaderActivity>, indiceParagrafo: Int, fimAntes: Int): Selecao? {
    val fim = System.currentTimeMillis() + TEMPO_PARA_SELECAO_MUDAR_MS
    var atual = selecaoAtual(scenario, indiceParagrafo)
    while (atual == null || atual.fim <= fimAntes) {
        if (System.currentTimeMillis() >= fim) return null
        Thread.sleep(PAUSA_ENTRE_LEITURAS_MS)
        atual = selecaoAtual(scenario, indiceParagrafo)
    }
    return atual
}

/** Espera até 2 s o início da seleção do parágrafo ficar menor que [inicioAntes]; nulo se ele não andar. */
fun esperarInicioMenorQue(
    scenario: ActivityScenario<ReaderActivity>,
    indiceParagrafo: Int,
    inicioAntes: Int,
): Selecao? {
    val fim = System.currentTimeMillis() + TEMPO_PARA_SELECAO_MUDAR_MS
    var atual = selecaoAtual(scenario, indiceParagrafo)
    while (atual == null || atual.inicio >= inicioAntes) {
        if (System.currentTimeMillis() >= fim) return null
        Thread.sleep(PAUSA_ENTRE_LEITURAS_MS)
        atual = selecaoAtual(scenario, indiceParagrafo)
    }
    return atual
}
