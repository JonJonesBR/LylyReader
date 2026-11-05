package com.jonjonesbr.audiobookgen.ui.apoio

import android.graphics.PointF
import android.graphics.Rect
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
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

/** Margem esquerda da lista, em dp, onde o dedo cai para arrastar: fora do texto e dos menus (rb-46 U4). */
private const val MARGEM_DO_ARRASTE_DP = 16f

/** Menor segmento do arraste, em frações da área útil: não encurta abaixo de um oitavo dela (rb-46 U4). */
private const val FRACAO_MINIMA_DO_SEGMENTO = 8

/** Fração da área útil que o arraste de [garantirPalavraVisivel] anda: um terço (rb-46 U4). */
private const val FRACAO_PADRAO_DO_ARRASTE = 1f / 3f

/** Em quantos pontos a faixa livre do arraste é procurada na área útil (rb-46 U4). */
private const val PASSOS_DA_BUSCA_DE_FAIXA = 40

/** Janela que o dedo pode bater no arraste: o retângulo na tela e a descrição para medidas.txt (rb-46 U4). */
private class ObstaculoDoArraste(val retangulo: Rect, val descricao: String)

/** Segmento vertical do arraste, em coordenadas de tela, e o motivo da escolha para medidas.txt (rb-46 U4). */
private data class SegmentoDoArraste(val yInicio: Int, val yFim: Int, val motivo: String)

/** Até quanto esperar o Snackbar sair da tela (ele some sozinho, LENGTH_SHORT). */
private const val TEMPO_PARA_SNACKBAR_SUMIR_MS = 4_000L

/**
 * Área da lista na tela, em pixels, para os arrastes: o retângulo visível da lista, a faixa entre o topo e o fundo
 * úteis (fora das barras da seleção e da guiada) e a densidade do aparelho, para converter dp em pixels.
 */
private class AreaDaLista(val listaRect: Rect, val topoUtil: Int, val fundoUtil: Int, val densidade: Float)

/**
 * Medida, na thread principal, de onde a palavra está e do que a cobre. Com [problemas] vazio, o toque é seguro.
 * [precisaSubir]: a palavra está embaixo da área útil (o dedo sobe); senão está em cima (o dedo desce).
 * [obstaculoTemporario]: um Snackbar cobre o ponto, e ele some sozinho (não se arrasta a lista por isso).
 * [area]: a área da lista no momento da medida.
 * [listaParada]: a lista está com o RecyclerView em repouso (SCROLL_STATE_IDLE).
 */
private class MedidaDeVisibilidade(
    val ponto: PointF?,
    val problemas: List<String>,
    val precisaSubir: Boolean,
    val obstaculoTemporario: Boolean,
    val area: AreaDaLista,
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
        val antes = primeiraViewDaLista(scenario)
        arrastarLista(pronta, cenario)
        arrastes++
        esperarListaParada(scenario)
        val depois = primeiraViewDaLista(scenario)
        val rolou = if (antes == depois) "não rolou" else "rolou"
        Capturas.anotar(cenario, "arraste $arrastes da lista: antes [$antes], depois [$depois], $rolou")
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

/**
 * Área da lista para os arrastes, na thread principal: o retângulo visível da lista, e o topo e o fundo úteis
 * (abaixo das barras da seleção e acima da barra da guiada). Usada pela medida de visibilidade e pela rolagem
 * à mão.
 */
private fun areaDaLista(a: ReaderActivity): AreaDaLista {
    val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
    val listaRect = retanguloVisivel(lista) ?: Rect()
    val guia = retanguloVisivel(a.findViewById<View>(R.id.layoutGuidedPlayer))
    val barraDeTexto = retanguloVisivel(a.findViewById<View>(R.id.barraSelecaoTexto))
    val barraDeVarios = retanguloVisivel(a.findViewById<View>(R.id.layoutBarraSelecao))
    val topoUtil = maxOf(listaRect.top, barraDeTexto?.bottom ?: 0, barraDeVarios?.bottom ?: 0)
    val fundoUtil = minOf(listaRect.bottom, guia?.top ?: listaRect.bottom)
    return AreaDaLista(listaRect, topoUtil, fundoUtil, lista.resources.displayMetrics.density)
}

/** A conta da medida, na thread principal. Ver [garantirPalavraVisivel] para as regras. */
private fun medirNaTela(a: ReaderActivity, indiceParagrafo: Int, palavra: String): MedidaDeVisibilidade {
    val lista = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos)
    val area = areaDaLista(a)
    val guia = retanguloVisivel(a.findViewById<View>(R.id.layoutGuidedPlayer))
    val botaoVoltar = retanguloVisivel(a.findViewById<View>(R.id.fabVoltarNarracao))
    val textoDoSnackbar: View? = a.findViewById(com.google.android.material.R.id.snackbar_text)
    val snackbar = textoDoSnackbar?.let { retanguloVisivel(it.parent as? View ?: it) }

    val folga = FOLGA_DA_AREA_OCUPADA_DP * area.densidade

    val problemas = mutableListOf<String>()
    var precisaSubir = false
    var obstaculoTemporario = false
    val tv = Gestos.textoDoParagrafoOuNulo(a, indiceParagrafo)
    val ponto = tv?.let { Gestos.pontoDaPalavraNoTexto(it, indiceParagrafo, palavra) }
    if (ponto == null) {
        // Sem View do parágrafo: a palavra está fora da lista. Arrasta para o lado dele.
        val lm = lista.layoutManager as LinearLayoutManager
        val primeiro = lm.findFirstVisibleItemPosition()
        precisaSubir = primeiro == RecyclerView.NO_POSITION || indiceParagrafo > primeiro
        problemas += "sem View do parágrafo (fora da lista)"
    } else {
        if (area.listaRect.isEmpty) {
            problemas += "lista fora da tela"
        } else if (ponto.y < area.topoUtil + folga) {
            problemas += "acima da área útil (topo ${area.topoUtil}, folga ${folga.toInt()}px)"
        } else if (ponto.y > area.fundoUtil - folga) {
            problemas += "embaixo da barra da guiada (fundo útil ${area.fundoUtil})"
            precisaSubir = true
        }
        if (botaoVoltar?.contains(ponto.x.toInt(), ponto.y.toInt()) == true) {
            problemas += "coberta pelo botão Voltar à narração"
            precisaSubir = ponto.y > area.listaRect.centerY()
        }
        if (snackbar?.contains(ponto.x.toInt(), ponto.y.toInt()) == true) {
            problemas += "coberta pelo Snackbar"
            obstaculoTemporario = true
        }
    }
    val relato = "ponto=${ponto?.let { "(${it.x.toInt()},${it.y.toInt()})" } ?: "nenhum"} " +
        "lista=${area.listaRect.toShortString()} topoUtil=${area.topoUtil} fundoUtil=${area.fundoUtil} " +
        "guia=${guia?.toShortString()} voltar=${botaoVoltar?.toShortString()} " +
        "snackbar=${snackbar?.toShortString()} problemas=$problemas"
    return MedidaDeVisibilidade(
        ponto = ponto,
        problemas = problemas,
        precisaSubir = precisaSubir,
        obstaculoTemporario = obstaculoTemporario,
        area = area,
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
 * Arrasta a lista com um dedo, andando um terço da área útil no sentido que leva a palavra para dentro da área: o
 * dedo sobe quando [MedidaDeVisibilidade.precisaSubir], senão desce. Usa o mesmo núcleo de [rolarListaComODedo].
 */
private fun arrastarLista(medida: MedidaDeVisibilidade, cenario: String) {
    arrastarAreaComODedo(medida.area, medida.precisaSubir, FRACAO_PADRAO_DO_ARRASTE, cenario)
}

/**
 * Rola a lista com um dedo de verdade, sem procurar palavra (rb-54, rb-55): arrasta [fracaoDaAreaUtil] da área útil
 * (de 0 a 1), centrada nela. Com [paraBaixoNoTexto], o dedo sobe e o texto anda para baixo. O dedo cai na margem
 * esquerda e desvia das janelas flutuantes, como o arraste de [garantirPalavraVisivel]. Espera a lista parar e
 * anota em medidas.txt se ela rolou ou não. Falha se a fração não for válida ou a lista não tiver área útil.
 */
fun rolarListaComODedo(
    scenario: ActivityScenario<ReaderActivity>,
    fracaoDaAreaUtil: Float,
    paraBaixoNoTexto: Boolean,
    cenario: String,
) {
    require(fracaoDaAreaUtil > 0f && fracaoDaAreaUtil <= 1f) { "Fração da rolagem fora de (0, 1]: $fracaoDaAreaUtil" }
    var area: AreaDaLista? = null
    scenario.onActivity { a -> area = areaDaLista(a) }
    val antes = primeiraViewDaLista(scenario)
    val areaDaRolagem = checkNotNull(area) { "Sem área da lista para rolar" }
    arrastarAreaComODedo(areaDaRolagem, paraBaixoNoTexto, fracaoDaAreaUtil, cenario)
    esperarListaParada(scenario)
    val depois = primeiraViewDaLista(scenario)
    val rolou = if (antes == depois) "não rolou" else "rolou"
    val sentido = if (paraBaixoNoTexto) "para baixo no texto" else "para cima no texto"
    val percentual = (fracaoDaAreaUtil * 100).toInt()
    Capturas.anotar(
        cenario,
        "rolagem à mão $sentido de $percentual% da área útil: antes [$antes], depois [$depois], $rolou"
    )
}

/**
 * Núcleo dos arrastes da lista: um segmento vertical de [fracao] da área útil, centrado nela, em x fixo na margem
 * esquerda da lista (rb-46 U4). O dedo sobe quando [dedoSobe] (o texto anda para baixo), senão desce. Se o segmento
 * cruzar uma janela flutuante, ele é deslocado ([escolherSegmento]). Anota em medidas.txt as janelas e o segmento
 * escolhido. Falha se não houver área útil.
 */
private fun arrastarAreaComODedo(area: AreaDaLista, dedoSobe: Boolean, fracao: Float, cenario: String) {
    val altura = area.fundoUtil - area.topoUtil
    check(altura > 0) { "Sem área útil para arrastar a lista: topo ${area.topoUtil}, fundo ${area.fundoUtil}" }
    val x = area.listaRect.left + MARGEM_DO_ARRASTE_DP * area.densidade
    val folga = FOLGA_DA_AREA_OCUPADA_DP * area.densidade
    val obstaculos = obstaculosDoArraste(area.listaRect, cenario)
    val segmento = escolherSegmento(area, fracao, x, folga, obstaculos)
    Capturas.anotar(
        cenario,
        "segmento do arraste: x=${x.toInt()} de y=${segmento.yInicio} a y=${segmento.yFim} (${segmento.motivo})"
    )
    val (y0, y1) = if (dedoSobe) {
        segmento.yFim to segmento.yInicio
    } else {
        segmento.yInicio to segmento.yFim
    }
    Gestos.arrastar(x, y0.toFloat(), x, y1.toFloat())
}

/**
 * Janelas da tela que podem cobrir o dedo, lidas pelo UiAutomation. Não contam: as do sistema (barra de status e de
 * navegação) e a janela do próprio Leitor, que cobre a lista inteira. Anota em medidas.txt todas as janelas, com o
 * que foi considerado obstáculo.
 */
private fun obstaculosDoArraste(listaRect: Rect, cenario: String): List<ObstaculoDoArraste> {
    val obstaculos = mutableListOf<ObstaculoDoArraste>()
    JanelasDaTela.ler { Capturas.anotar(cenario, "janelas: $it") }.forEach { janela ->
        val retangulo = Rect()
        janela.getBoundsInScreen(retangulo)
        val ignorada = when {
            janela.type == AccessibilityWindowInfo.TYPE_SYSTEM -> "sistema"
            retangulo.isEmpty -> "vazia"
            retangulo.contains(listaRect) -> "cobre a lista"
            cobreAListaComFolga(retangulo, listaRect) -> "cobre a lista, com 1 px de diferença"
            else -> null
        }
        val descricao = "tipo=${janela.type} titulo=${janela.title} bounds=${retangulo.toShortString()}"
        if (ignorada == null) obstaculos += ObstaculoDoArraste(retangulo, descricao)
        Capturas.anotar(cenario, "janela ${ignorada?.let { "ignorada ($it)" } ?: "obstáculo"}: $descricao")
    }
    return obstaculos
}

/**
 * Se a janela é a do próprio Leitor mesmo com os bounds um pouco diferentes da lista (insets e recortes podem dar
 * 1 px): quase do tamanho da lista e cobrindo o centro dela. Um menu flutuante não cobre a lista desse jeito.
 */
private fun cobreAListaComFolga(janela: Rect, lista: Rect): Boolean {
    return janela.width() >= lista.width() * 9 / 10 && janela.height() >= lista.height() * 9 / 10 &&
        janela.contains(lista.centerX(), lista.centerY())
}

/**
 * Escolhe o segmento vertical do arraste, em [x] fixo. Começa no padrão: [fracao] da área útil, centrado nela. Se
 * ele cruzar um obstáculo, procura a faixa livre mais perto do centro, com o mesmo tamanho; se não houver, encurta
 * o segmento (até um oitavo da área útil) e procura de novo. Sem faixa livre nenhuma, fica com o padrão e diz isso
 * no motivo: o arraste segue mesmo assim (rb-46 U4).
 */
private fun escolherSegmento(
    area: AreaDaLista,
    fracao: Float,
    x: Float,
    folga: Float,
    obstaculos: List<ObstaculoDoArraste>,
): SegmentoDoArraste {
    val altura = area.fundoUtil - area.topoUtil
    val centro = (area.topoUtil + area.fundoUtil) / 2
    val comprimento = (altura * fracao).toInt()
    val minimo = minOf(altura / FRACAO_MINIMA_DO_SEGMENTO, comprimento)
    val tamanhos = listOf(comprimento, comprimento * 3 / 4, comprimento / 2, minimo).distinct()
    val passo = (altura / PASSOS_DA_BUSCA_DE_FAIXA).coerceAtLeast(1)
    val inicioPadrao = centro - comprimento / 2
    if (obstaculos.none { cruza(inicioPadrao, inicioPadrao + comprimento, x, folga, it) }) {
        return SegmentoDoArraste(inicioPadrao, inicioPadrao + comprimento, "padrão, sem janela por cima")
    }
    for (tamanho in tamanhos) {
        val inicios = (area.topoUtil..(area.fundoUtil - tamanho) step passo)
            .sortedBy { abs(it + tamanho / 2 - centro) }
        val livre = inicios.firstOrNull { inicio ->
            obstaculos.none { cruza(inicio, inicio + tamanho, x, folga, it) }
        }
        if (livre != null) {
            return SegmentoDoArraste(livre, livre + tamanho, "desviado de janela, comprimento $tamanho")
        }
    }
    return SegmentoDoArraste(inicioPadrao, inicioPadrao + comprimento, "sem faixa livre; padrão mesmo assim")
}

/** Se o segmento vertical de [yInicio] a [yFim], em [x], encosta em [obstaculo] (com a [folga] em volta). */
private fun cruza(yInicio: Int, yFim: Int, x: Float, folga: Float, obstaculo: ObstaculoDoArraste): Boolean {
    val r = obstaculo.retangulo
    return x >= r.left - folga && x <= r.right + folga &&
        minOf(yInicio, yFim) <= r.bottom + folga && maxOf(yInicio, yFim) >= r.top - folga
}

/**
 * Posição e topo da 1ª View visível da lista, lidos na thread principal, em texto para medidas.txt. Com a lista
 * rolada, a posição ou o topo mudam (rb-46 U4).
 */
private fun primeiraViewDaLista(scenario: ActivityScenario<ReaderActivity>): String {
    var texto = ""
    scenario.onActivity { a ->
        val lm = a.findViewById<RecyclerView>(R.id.recyclerViewParagrafos).layoutManager as LinearLayoutManager
        val posicao = lm.findFirstVisibleItemPosition()
        texto = "posição=$posicao topo=${lm.findViewByPosition(posicao)?.top}"
    }
    return texto
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
        val tv = Gestos.textoDoParagrafoOuNulo(a, indiceParagrafo)
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
