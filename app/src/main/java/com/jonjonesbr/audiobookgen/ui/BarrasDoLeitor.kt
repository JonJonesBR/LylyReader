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
import com.jonjonesbr.audiobookgen.domain.Dica
import com.jonjonesbr.audiobookgen.domain.EstadoDoCabecalho
import com.jonjonesbr.audiobookgen.domain.GestoDasBarras
import com.jonjonesbr.audiobookgen.domain.ModoDaBarra
import com.jonjonesbr.audiobookgen.domain.RegraDasBarrasDoLeitor
import com.jonjonesbr.audiobookgen.domain.SituacaoDoLeitor

/** Chave do estado destas barras no SavedStateRegistry da Activity (giro e morte do processo). */
private const val CHAVE_DAS_BARRAS = "rb66.barras"
private const val CHAVE_ABERTO_CIMA = "abertoCima"
private const val CHAVE_ABERTO_BAIXO = "abertoBaixo"

/**
 * Coordenador das barras do Leitor (rb-66, passos 3 a 5). Guarda se a barra de cima e o painel de baixo estão abertos
 * (`aberto`: o que a pessoa ou o tempo pediram) e, a cada mudança, calcula o que aparece com [RegraDasBarrasDoLeitor]:
 * modo guardado em AppPrefs, o que foi pedido e a situação do Leitor.
 *
 * Região de cima: o cabeçalho (toolbar, busca, capítulo), pela [TelaCheiaDoLeitor]. Região de baixo: o painel da guiada
 * (só existe com a guiada), pela [PainelRecolhivelDaGuiada]: recolhe numa tira com play e progresso. A visibilidade
 * do painel inteiro é da GuidedReadingBarController; aqui só se mexe nos filhos quando ele está à vista.
 *
 * Quem chama: o [ReaderTelaController] (ligar, aplicar), os gestos da página e do vertical (pela [TelaCheiaDoLeitor]),
 * a setinha e o play da tira (pelo painel), o tempo (pelo [TemporizadorDasBarras]) e o foco da janela.
 */
internal class BarrasDoLeitor(
    private val activity: AppCompatActivity,
    private val lista: RecyclerViewParagrafos,
) {
    private val cabecalho: BarraDoLeitor = activity.findViewById(R.id.cabecalhoDoLeitor)
    private val setinha: View = activity.findViewById(R.id.btnMostrarBarras)
    private val busca: View = activity.findViewById(R.id.layoutBuscaReader)
    private val barraDoModo: View = activity.findViewById(R.id.layoutBarraModo)
    private val barraDaSelecao: View = activity.findViewById(R.id.layoutBarraSelecao)
    private val barraDeTexto: View = activity.findViewById(R.id.barraSelecaoTexto)
    private val painelRaiz: View = activity.findViewById(R.id.layoutGuidedPlayer)
    private val painelBarra: BarraDoLeitor? = painelRaiz as? BarraDoLeitor
    private val acessibilidade: AccessibilityManager? = activity.getSystemService(AccessibilityManager::class.java)
    // As dicas das barras recolhidas (rb-66, passo 7): o coordenador avisa o que aconteceu, a classe decide a dica.
    private val dicas = DicasDasBarras(activity, cabecalho)

    private val telaCheia: TelaCheiaDoLeitor =
        TelaCheiaDoLeitor(activity, lista, cabecalho) { gesto -> aoGestoDeCima(gesto) }
    private val painel: PainelRecolhivelDaGuiada =
        PainelRecolhivelDaGuiada(painelRaiz) { gesto -> aoGestoDeBaixo(gesto) }
    private val temporizador: TemporizadorDasBarras = TemporizadorDasBarras(
        activity = activity,
        podeRecolher = { podeRecolherAgora() },
        // O tempo recolhe as duas regiões: aoGestoDeCima também aplica o TEMPO_ESGOTADO ao painel de baixo.
        aoEsgotar = { aoGestoDeCima(GestoDasBarras.TEMPO_ESGOTADO) },
        aoVoltar = { relerEAplicar() },
    )
    // Na Activity destruída: solta o foco da janela e o observador do tempo.
    private val fimDaActivity: DefaultLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            liberar()
        }
    }
    // Perder o foco da janela (diálogo, menu, folha, cortina) segura o tempo; recuperar rearma (ver relerEAplicar).
    private val focoDaJanela = ViewTreeObserver.OnWindowFocusChangeListener { temFoco ->
        janelaComFoco = temFoco
        if (temFoco) relerEAplicar()
    }
    // Rede de segurança do vertical: a barra da seleção de texto abre e fecha sem passar pelo aplicar do controller.
    private val layoutDaJanela = ViewTreeObserver.OnGlobalLayoutListener {
        // Só no vertical: no modo páginas a reserva mudaria dentro do layout (ali vale o aplicar do controller).
        if (!paginas && ultimo != null) {
            aplicar(leitura, paginas)
        } else if (ultimo != null && painelMostrado != (painelRaiz.visibility != View.GONE)) {
            // Modo páginas: quando a guiada põe o painel à vista, só a tira dele é aplicada (sem tocar na reserva de
            // cima). Sem isso, o painel ficaria no estado de antes até a próxima atualização da tela.
            aplicarPainel(leitorDeTela())
        }
    }
    // TalkBack ligado ou desligado com o Leitor aberto (atalho do volume): a lista troca de vertical para páginas ou
    // volta (rb-67, item 2). Ao sair do TalkBack, a página já se alinha; e as barras reaplicam com o modo novo.
    private val ouvinteDoLeitorDeTela = AccessibilityManager.TouchExplorationStateChangeListener { ligado ->
        lista.requestLayout()
        lista.invalidate()
        val paginacao = lista.paginacao
        if (!ligado && paginacao != null && paginacao.ligada) lista.post { paginacao.alinharTopo() }
        aplicar(leitura, lista.paginacao?.ligada == true && leitura)
    }
    // Teclado: o Tab sem dono mostra o que estiver recolhido (cabeçalho e painel) e segue para a navegação de foco
    // normal (não consome o evento).
    private val tecla = ViewCompat.OnUnhandledKeyEventListenerCompat { _, ev ->
        if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_TAB) {
            if (ultimo?.visivel != true) aoGestoDeCima(GestoDasBarras.MOSTRAR)
            if (painelRecolhidoNaBase) aoGestoDeBaixo(GestoDasBarras.MOSTRAR)
        }
        false
    }

    private var modoCima = ModoDaBarra.TEMPO
    // O que a pessoa ou o tempo pediu para a barra de cima (não o que aparece: isso sai da regra).
    private var abertoCima = false
    private var modoBaixo = ModoDaBarra.TEMPO
    // O mesmo para o painel de baixo. Com o painel recolhido por padrão a cada guiada, o que vale é o modo TEMPO.
    private var abertoBaixo = false
    // Verdadeiro enquanto o painel está à vista (não GONE). Ao aparecer de novo, a tira começa recolhida no TEMPO.
    private var painelMostrado = false
    private var leitura = false
    private var paginas = false
    private var ultimo: EstadoDoCabecalho? = null
    // Sem foco na janela (diálogo, menu, folha, cortina de notificações) o tempo não recolhe.
    private var janelaComFoco = true

    /**
     * Liga tudo, uma vez por Activity (no configurar do ReaderTelaController, no onCreate, depois do super): os modos
     * lidos das preferências, o estado de giro, a tela cheia, o painel, o toque nas barras, o tempo e o foco da janela.
     * Não chamar de novo: o registro do estado é único por chave.
     */
    fun ligar() {
        val guardado = activity.savedStateRegistry.consumeRestoredStateForKey(CHAVE_DAS_BARRAS)
        modoCima = ModoDaBarra.de(AppPrefs(activity).readerBarraDeCima)
        modoBaixo = ModoDaBarra.de(AppPrefs(activity).readerBarraDeBaixo)
        // Giro: a barra MANUAL volta como estava; a de tempo sempre começa recolhida.
        abertoCima = modoCima == ModoDaBarra.MANUAL && guardado?.getBoolean(CHAVE_ABERTO_CIMA) == true
        abertoBaixo = modoBaixo == ModoDaBarra.MANUAL && guardado?.getBoolean(CHAVE_ABERTO_BAIXO) == true
        activity.savedStateRegistry.registerSavedStateProvider(CHAVE_DAS_BARRAS) {
            Bundle().apply {
                putBoolean(CHAVE_ABERTO_CIMA, abertoCima)
                putBoolean(CHAVE_ABERTO_BAIXO, abertoBaixo)
            }
        }
        telaCheia.ligar()
        painel.ligar()
        cabecalho.aoTocar = { temporizador.tocou() }
        painelBarra?.aoTocar = { temporizador.tocou() }
        activity.lifecycle.addObserver(temporizador)
        activity.lifecycle.addObserver(fimDaActivity)
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focoDaJanela)
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(layoutDaJanela)
        ViewCompat.addOnUnhandledKeyEventListener(activity.window.decorView, tecla)
        acessibilidade?.addTouchExplorationStateChangeListener(ouvinteDoLeitorDeTela)
    }

    /**
     * Aplica as barras com a situação atual. [leitura] é falso em erro, carregamento, edição ou comparação (aí o
     * cabeçalho é obrigatório); [paginas] é o modo páginas com texto. Roda a cada atualização da tela: só repassa à
     * tela e ao painel quando o resultado muda. Rearma o tempo quando o estado do cabeçalho muda ou ao entrar/sair
     * das páginas (os gestos rearmam em [aoGestoDeCima] e [aoGestoDeBaixo]).
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
        // No vertical, com a barra do fluxo (seleção de parágrafos ou modo) à mostra, o cabeçalho fica reservado e à
        // mostra, como no modo SEMPRE: a barra fica no topo e a setinha cobriria o botão "Gerar áudio". Rolar e o
        // tempo não mexem nessa reserva enquanto a barra estiver aberta. A seleção de texto no topo ainda esconde.
        val forcarPelaBarra = !paginas && situacao.barraDoFluxoVisivel && !situacao.selecaoNoTopo
        val modo = if (forcarPelaBarra) ModoDaBarra.SEMPRE else modoCima
        val estado = RegraDasBarrasDoLeitor.cabecalho(modo, abertoCima, paginas, situacao)
        val mudou = estado != ultimo
        if (mudou) {
            ultimo = estado
            telaCheia.aplicar(estado, continuaNasPaginas)
        }
        // Cabeçalho recolhido no vertical: a dica da tela cheia aponta para a setinha (uma vez por abertura).
        if (!paginas && !estado.visivel) dicas.recolhidoNoVertical()
        aplicarPainel(situacao.leitorDeTela)
        // Entrar ou sair das páginas com a barra aberta por tempo arma ou para a contagem. Mudar o cabeçalho (ex.: a
        // seleção fechou e ele voltou) recomeça o tempo: senão o período que já corria podia recolhê-lo logo depois.
        if (mudouDePaginas || mudou) armarSeCabe()
    }

    /**
     * O painel de baixo, quando está à vista. Ao aparecer (a guiada começou), a tira volta recolhida no modo TEMPO: o
     * `aberto` de uma guiada anterior não vale. O painel GONE também recebe o estado: se o modo muda com ele
     * escondido (Aparência, TalkBack), ele já está certo quando voltar, em vez de reaparecer no estado antigo.
     */
    private fun aplicarPainel(leitorDeTela: Boolean) {
        val mostrado = painelRaiz.visibility != View.GONE
        val apareceu = mostrado && !painelMostrado
        if (apareceu && modoBaixo == ModoDaBarra.TEMPO) abertoBaixo = false
        painelMostrado = mostrado
        val aberto = RegraDasBarrasDoLeitor.painelAberto(modoBaixo, abertoBaixo, leitorDeTela)
        painel.aplicar(aberto = aberto, seta = RegraDasBarrasDoLeitor.setaDoPainel(modoBaixo, leitorDeTela))
        // rb-66 (passo 7): o painel que aparece recolhido pela 1ª vez ganha a dica da tira.
        if (apareceu && !aberto) dicas.tiraApareceu()
    }

    /**
     * Um gesto da região de cima (deslize, virada, tempo). Muda o que foi pedido pela regra e rearma o tempo. A leitura
     * andando e o tempo também recolhem o painel de baixo (pela mesma regra, com o modo dele).
     */
    private fun aoGestoDeCima(gesto: GestoDasBarras) {
        val estavaVisivel = ultimo?.visivel == true
        abertoCima = RegraDasBarrasDoLeitor.depoisDoGesto(modoCima, abertoCima, gesto)
        if (gesto == GestoDasBarras.LEITURA_ANDOU || gesto == GestoDasBarras.TEMPO_ESGOTADO) {
            abertoBaixo = RegraDasBarrasDoLeitor.depoisDoGesto(modoBaixo, abertoBaixo, gesto)
        }
        aplicar(leitura, paginas)
        armarSeCabe()
        dicas.aoMudarCabecalho(gesto, estavaVisivel, ultimo?.visivel == true)
    }

    /** Um gesto do painel de baixo (a seta da tira, o Tab). Muda o que foi pedido pela regra e rearma o tempo. */
    private fun aoGestoDeBaixo(gesto: GestoDasBarras) {
        abertoBaixo = RegraDasBarrasDoLeitor.depoisDoGesto(modoBaixo, abertoBaixo, gesto)
        aplicar(leitura, paginas)
        armarSeCabe()
        // A dica do botão de voz (rb-66) espera a tira abrir: o botão fica escondido com o painel recolhido, e a
        // GuidedReadingBarController só tenta quando ele já está à vista.
        if (gesto == GestoDasBarras.MOSTRAR) {
            painelRaiz.post {
                activity.findViewById<View?>(R.id.btnGuidedVoz)?.let {
                    CoachMark.tentar(activity, Dica.LEITOR_VOZ_E_MOTOR, it)
                }
            }
        }
    }

    /**
     * Relê os modos (se a Aparência mudou) e reaplica; rearma o tempo. Chamado no onStart e quando a janela recupera o
     * foco, que é o que acontece ao fechar a folha da Aparência.
     */
    private fun relerEAplicar() {
        val novo = ModoDaBarra.de(AppPrefs(activity).readerBarraDeCima)
        if (novo != modoCima) {
            abertoCima = RegraDasBarrasDoLeitor.abertoAoTrocarDeModo(modoCima, novo, ultimo?.visivel == true)
            modoCima = novo
        }
        val novoBaixo = ModoDaBarra.de(AppPrefs(activity).readerBarraDeBaixo)
        if (novoBaixo != modoBaixo) {
            val aberto = RegraDasBarrasDoLeitor.painelAberto(modoBaixo, abertoBaixo, leitorDeTela())
            abertoBaixo = RegraDasBarrasDoLeitor.abertoAoTrocarDeModo(modoBaixo, novoBaixo, aberto)
            modoBaixo = novoBaixo
        }
        aplicar(leitura, paginas)
        armarSeCabe()
    }

    /**
     * O tempo corre quando alguma região em TEMPO está aberta: a barra de cima (no modo páginas e no vertical) ou o
     * painel de baixo (com a guiada). Fora disso, para.
     */
    private fun armarSeCabe() {
        val tempoEmCima = modoCima == ModoDaBarra.TEMPO && abertoCima
        val tempoEmBaixo = modoBaixo == ModoDaBarra.TEMPO && abertoBaixo
        if (tempoEmCima || tempoEmBaixo) temporizador.armar() else temporizador.cancelar()
    }

    /** Os bloqueios do tempo, lidos agora (ver [RegraDasBarrasDoLeitor.podeRecolherPorTempo]). */
    private fun podeRecolherAgora(): Boolean = RegraDasBarrasDoLeitor.podeRecolherPorTempo(
        BloqueiosDoTempo(
            selecao = lista.textoEmUso || barraDeTexto.visibility == View.VISIBLE,
            janelaSemFoco = !janelaComFoco,
            buscaAberta = busca.visibility == View.VISIBLE,
            leitorDeTela = leitorDeTela(),
            focoOuToqueNasBarras = cabecalho.toqueEmAndamento || cabecalho.hasFocus() || setinha.isFocused ||
                painelBarra?.toqueEmAndamento == true || painelRaiz.hasFocus(),
            dicaAberta = activity.findViewById<View>(R.id.coachMarkRaiz) != null,
        ),
    )

    /**
     * A tira do painel de baixo está na base (à vista e recolhida). Com ela, a barra da seleção de texto não conta no
     * respiro da lista: a página não muda de tamanho quando a seleção abre (rb-66, passo 5).
     */
    val painelRecolhidoNaBase: Boolean
        get() = painelRaiz.visibility != View.GONE &&
            !RegraDasBarrasDoLeitor.painelAberto(modoBaixo, abertoBaixo, leitorDeTela())

    private fun leitorDeTela(): Boolean = acessibilidade?.isTouchExplorationEnabled == true

    /** Solta o foco da janela e o tempo: chamado quando a Activity é destruída. */
    private fun liberar() {
        temporizador.cancelar()
        val janela = activity.window.decorView.viewTreeObserver
        if (janela.isAlive) janela.removeOnWindowFocusChangeListener(focoDaJanela)
        if (janela.isAlive) janela.removeOnGlobalLayoutListener(layoutDaJanela)
        activity.lifecycle.removeObserver(temporizador)
        activity.lifecycle.removeObserver(fimDaActivity)
        ViewCompat.removeOnUnhandledKeyEventListener(activity.window.decorView, tecla)
        acessibilidade?.removeTouchExplorationStateChangeListener(ouvinteDoLeitorDeTela)
    }
}
