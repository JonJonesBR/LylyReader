package com.jonjonesbr.audiobookgen.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.BloqueiosDoTempo
import com.jonjonesbr.audiobookgen.domain.EstadoDoCabecalho
import com.jonjonesbr.audiobookgen.domain.GestoDasBarras
import com.jonjonesbr.audiobookgen.domain.ModoDaBarra
import com.jonjonesbr.audiobookgen.domain.RegraDasBarrasDoLeitor
import com.jonjonesbr.audiobookgen.domain.SituacaoDoLeitor

/** Chave do estado destas barras no SavedStateRegistry da Activity (giro e morte do processo). */
private const val CHAVE_DAS_BARRAS = "rb66.barras"
private const val CHAVE_ABERTO_CIMA = "abertoCima"

/**
 * Coordenador das barras do Leitor (rb-66, passo 3). Guarda se a barra de cima está aberta, e a cada mudança calcula
 * o que aparece com [RegraDasBarrasDoLeitor]: modo guardado em AppPrefs, o que a pessoa ou o tempo pediu e a situação
 * do Leitor. Só a região de cima por enquanto: o painel de baixo entra no passo 5.
 *
 * Quem chama: o [ReaderTelaController] (ligar, aplicar), os gestos da página (pela [TelaCheiaDoLeitor]), o tempo (pelo
 * [TemporizadorDasBarras]) e o foco da janela. PROVISÓRIO até o passo 4: fora do modo páginas, o cabeçalho segue
 * forçado, como antes.
 */
internal class BarrasDoLeitor(
    private val activity: AppCompatActivity,
    private val lista: RecyclerViewParagrafos,
) {
    private val cabecalho: BarraDoLeitor = activity.findViewById(R.id.cabecalhoDoLeitor)
    private val busca: View = activity.findViewById(R.id.layoutBuscaReader)
    private val barraDoModo: View = activity.findViewById(R.id.layoutBarraModo)
    private val barraDaSelecao: View = activity.findViewById(R.id.layoutBarraSelecao)
    private val barraDeTexto: View = activity.findViewById(R.id.barraSelecaoTexto)
    private val acessibilidade: AccessibilityManager? = activity.getSystemService(AccessibilityManager::class.java)

    private val telaCheia: TelaCheiaDoLeitor =
        TelaCheiaDoLeitor(activity, lista, cabecalho) { gesto -> aoGestoDeCima(gesto) }
    private val temporizador: TemporizadorDasBarras = TemporizadorDasBarras(
        activity = activity,
        podeRecolher = { podeRecolherAgora() },
        aoEsgotar = { aoGestoDeCima(GestoDasBarras.TEMPO_ESGOTADO) },
        aoVoltar = { relerEAplicar() },
    )
    // Na Activity destruída: solta o foco da janela e o observador do tempo.
    private val fimDaActivity: DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            liberar()
        }
    }
    private val focoDaJanela = ViewTreeObserver.OnWindowFocusChangeListener { temFoco -> aoMudarFocoDaJanela(temFoco) }
    // Teclado: o Tab sem dono mostra o cabeçalho e segue para a navegação de foco normal (não consome o evento).
    private val tecla = ViewCompat.OnUnhandledKeyEventListenerCompat { _, ev ->
        if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_TAB && ultimo?.visivel != true) {
            aoGestoDeCima(GestoDasBarras.MOSTRAR)
        }
        false
    }

    private var modoCima = ModoDaBarra.TEMPO
    // O que a pessoa ou o tempo pediu para a barra de cima (não o que aparece: isso sai da regra).
    private var abertoCima = false
    private var leitura = false
    private var paginas = false
    private var ultimo: EstadoDoCabecalho? = null
    // Sem foco na janela (diálogo, menu, folha, cortina de notificações) o tempo não recolhe.
    private var janelaComFoco = true

    /**
     * Liga tudo, uma vez por Activity (no configurar do ReaderTelaController, no onCreate, depois do super): o modo
     * lido das preferências, o estado de giro, a tela cheia, o toque nas barras, o tempo e o foco da janela. Não chamar
     * de novo: o registro do estado é único por chave.
     */
    fun ligar() {
        val guardado = activity.savedStateRegistry.consumeRestoredStateForKey(CHAVE_DAS_BARRAS)
        modoCima = ModoDaBarra.de(AppPrefs(activity).readerBarraDeCima)
        // Giro: a barra MANUAL volta como estava; a de tempo sempre começa recolhida.
        abertoCima = modoCima == ModoDaBarra.MANUAL && guardado?.getBoolean(CHAVE_ABERTO_CIMA) == true
        activity.savedStateRegistry.registerSavedStateProvider(CHAVE_DAS_BARRAS) {
            Bundle().apply { putBoolean(CHAVE_ABERTO_CIMA, abertoCima) }
        }
        telaCheia.ligar()
        cabecalho.aoTocar = { temporizador.tocou() }
        activity.lifecycle.addObserver(temporizador)
        activity.lifecycle.addObserver(fimDaActivity)
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focoDaJanela)
        ViewCompat.addOnUnhandledKeyEventListener(activity.window.decorView, tecla)
    }

    /**
     * Aplica a barra de cima com a situação atual. [leitura] é falso em erro, carregamento, edição ou comparação (aí o
     * cabeçalho é obrigatório); [paginas] é o modo páginas com texto. Roda a cada atualização da tela: só repassa à
     * tela quando o resultado muda. Rearma o tempo só ao entrar ou sair das páginas (os gestos rearmam em
     * [aoGestoDeCima]).
     */
    fun aplicar(leitura: Boolean, paginas: Boolean) {
        // Na troca de modo (edição, comparação, vertical para páginas) a lista é refeita: a 1ª linha só se mantém
        // quando a lista já estava nas páginas e continua nelas.
        val continuaNasPaginas = this.paginas && paginas
        val mudouDePaginas = this.paginas != paginas
        this.leitura = leitura
        this.paginas = paginas
        val situacao = SituacaoDoLeitor(
            leitura = leitura,
            leitorDeTela = leitorDeTela(),
            buscaAberta = busca.visibility == View.VISIBLE,
            barraDoFluxoVisivel = barraDoModo.visibility == View.VISIBLE || barraDaSelecao.visibility == View.VISIBLE,
            selecaoNoTopo = barraDeTexto.visibility == View.VISIBLE && !lista.baseCedidaASelecao,
        )
        // PROVISÓRIO (até o passo 4): fora do modo páginas o cabeçalho segue forçado, como hoje.
        val modo = if (paginas) modoCima else ModoDaBarra.SEMPRE
        val estado = RegraDasBarrasDoLeitor.cabecalho(modo, abertoCima, paginas, situacao)
        if (estado != ultimo) {
            ultimo = estado
            telaCheia.aplicar(estado, continuaNasPaginas)
        }
        // Entrar nas páginas com a barra aberta por tempo arma a contagem (ex.: a ação de acessibilidade no vertical).
        if (mudouDePaginas) armarSeCabe()
    }

    /** Um gesto da região de cima (deslize, virada, tempo). Muda o que foi pedido pela regra e rearma o tempo. */
    private fun aoGestoDeCima(gesto: GestoDasBarras) {
        abertoCima = RegraDasBarrasDoLeitor.depoisDoGesto(modoCima, abertoCima, gesto)
        aplicar(leitura, paginas)
        armarSeCabe()
    }

    /**
     * Relê o modo (se a Aparência mudou) e reaplica; rearma o tempo. Chamado no onStart e quando a janela recupera o
     * foco, que é o que acontece ao fechar a folha da Aparência.
     */
    private fun relerEAplicar() {
        val novo = ModoDaBarra.de(AppPrefs(activity).readerBarraDeCima)
        if (novo != modoCima) {
            abertoCima = RegraDasBarrasDoLeitor.abertoAoTrocarDeModo(modoCima, novo, ultimo?.visivel == true)
            modoCima = novo
        }
        aplicar(leitura, paginas)
        armarSeCabe()
    }

    /** O tempo só corre com a barra de cima aberta por tempo no modo páginas. Fora disso, para. */
    private fun armarSeCabe() {
        if (paginas && modoCima == ModoDaBarra.TEMPO && abertoCima) temporizador.armar() else temporizador.cancelar()
    }

    private fun aoMudarFocoDaJanela(temFoco: Boolean) {
        janelaComFoco = temFoco
        if (temFoco) relerEAplicar()
    }

    /** Os bloqueios do tempo, lidos agora (ver [RegraDasBarrasDoLeitor.podeRecolherPorTempo]). */
    private fun podeRecolherAgora(): Boolean = RegraDasBarrasDoLeitor.podeRecolherPorTempo(
        BloqueiosDoTempo(
            selecao = lista.textoEmUso || barraDeTexto.visibility == View.VISIBLE,
            janelaSemFoco = !janelaComFoco,
            buscaAberta = busca.visibility == View.VISIBLE,
            leitorDeTela = leitorDeTela(),
            focoOuToqueNasBarras = cabecalho.toqueEmAndamento || cabecalho.hasFocus(),
            dicaAberta = activity.findViewById<View>(R.id.coachMarkRaiz) != null,
        ),
    )

    private fun leitorDeTela(): Boolean = acessibilidade?.isTouchExplorationEnabled == true

    /** Solta o foco da janela e o tempo: chamado quando a Activity é destruída. */
    private fun liberar() {
        temporizador.cancelar()
        val janela = activity.window.decorView.viewTreeObserver
        if (janela.isAlive) janela.removeOnWindowFocusChangeListener(focoDaJanela)
        activity.lifecycle.removeObserver(temporizador)
        activity.lifecycle.removeObserver(fimDaActivity)
        ViewCompat.removeOnUnhandledKeyEventListener(activity.window.decorView, tecla)
    }
}
