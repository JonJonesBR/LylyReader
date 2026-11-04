package com.jonjonesbr.audiobookgen.ui

import android.view.ViewConfiguration
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView

/**
 * Barra da seleção de texto quando não há o modo do sistema (rb-54). O menu da seleção pode fechar sem a seleção
 * sair (janela sem foco, a seleção reaberta pelo Editor no duplo toque): o parágrafo continua com foco e seleção,
 * e antes a barra sumia e a seleção ficava pintada, sem ações. Aqui a barra segue a seleção viva: aparece quando
 * há seleção com foco e some quando ela acaba. Se o Editor reabre o modo sozinho dentro da janela do duplo toque,
 * a barra fica com o menu, e não aparece nada extra.
 *
 * Quem dispara a conferência é a lista ([RecyclerViewParagrafos.liberarDepois]): o fim do toque, a rolagem parada e
 * o fechar do modo. A decisão está em [passoDaBarraDaSelecao]. Tudo roda na main thread, por post.
 *
 * Com o parágrafo selecionado fora da tela, o trecho fica guardado (rb-58, ver [SelecaoGuardada]): a barra fica e
 * age sobre ele, e quem o limpa é Voltar, um toque curto, uma ação da barra, uma seleção nova ou uma lista nova.
 *
 * O Voltar também fica aqui: com a seleção viva e sem o modo, ele desfaz a seleção e não sai do Leitor. O callback é
 * criado e registrado só quando é preciso, para ficar acima do callback da Activity (o último registrado tem
 * prioridade).
 */
internal class SelecaoSemMenu(
    private val activity: AppCompatActivity,
    private val lista: RecyclerViewParagrafos,
    private val barra: BarraDaSelecaoDeTexto,
    // As ações da barra (as do menu). Lambda: o adapter declara as ações depois desta classe.
    private val acoes: () -> AcoesDaSelecaoDeTexto,
) {
    // Espera pelo menu: a janela do duplo toque mais uma folga, para o Editor ter tempo de reabrir o modo.
    private val espera: Long = ViewConfiguration.getDoubleTapTimeout().toLong() + FOLGA_DA_ESPERA_MS
    private val depoisDaEspera = Runnable { decidir(jaEsperou = true) }
    private var voltar: OnBackPressedCallback? = null

    // Último TextView mostrado com a barra pela seleção viva. Outro TextView é outra seleção: a descida se ajusta
    // a ele (mesma ideia de ultimoMenuAberto no ParagrafoAdapter).
    private var ultimaSelecaoSemMenu: TextView? = null

    init {
        lista.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) lista.liberarDepois()
            }
        })
    }

    /** Confere a seleção viva. Sempre chamado já dentro de um post (pelo liberarDepois). */
    fun conferir() {
        lista.removeCallbacks(depoisDaEspera)
        decidir(jaEsperou = false)
    }

    /**
     * Ação da barra com o menu fechado: executa pelo menu do parágrafo com seleção (que desfaz a seleção) e confere.
     * Com trecho guardado (rb-58), a ação roda sobre ele e o trecho sai. Com o menu aberto, quem chama já usa o
     * próprio menu.
     */
    fun agirPelaBarra(acao: AcaoDaBarraDeSelecao) {
        val guardada = lista.guardada
        val trechoGuardado = guardada.trecho()
        // Seleção viva vence o trecho guardado: a conferência limpa o guardado quando há seleção nova.
        val viva = lista.textoComSelecaoViva
        if (viva == null && trechoGuardado != null) {
            acoes().acaoDaBarra(acao, trechoGuardado, guardada.posicao)?.invoke()
            limparGuardada()
            return
        }
        // A seleção viva, ou a última que a barra mostrou: o foco pode estar num botão da barra (rb-57).
        val tv = viva ?: ultimaSelecaoSemMenu?.takeIf { it.hasSelection() }
        (tv?.customSelectionActionModeCallback as? MenuDaSelecaoDeTexto)?.executarPelaBarra(acao)
        lista.liberarDepois()
    }

    /**
     * Tira o trecho guardado (rb-58). Com o parágrafo na tela, o marcador sai da view na hora; fora dela, o item é
     * repintado (o bind refaz o texto e os spans). Depois confere a barra: sem seleção nem trecho, ela some.
     */
    fun limparGuardada() {
        val posicao = lista.guardada.posicao
        lista.guardada.limpar()
        if (posicao >= 0) {
            val holder = lista.findViewHolderForAdapterPosition(posicao) as? ParagrafoAdapter.VHLeitura
            if (holder != null) holder.tv.pintarTrechoGuardado(null) else lista.adapter?.notifyItemChanged(posicao)
        }
        lista.liberarDepois()
    }

    private fun decidir(jaEsperou: Boolean) {
        val viva = lista.textoComSelecaoViva
        limparPelaSelecaoOuPeloToque(viva)
        val passo = passoDaBarraDaSelecao(
            menuAberto = lista.selecoesAbertas > 0,
            selecaoViva = viva != null,
            emMovimento = lista.toqueEmAndamento || lista.scrollState != RecyclerView.SCROLL_STATE_IDLE,
            jaEsperou = jaEsperou,
            guardada = lista.guardada.ativa,
        )
        when (passo) {
            PassoDaBarraDaSelecao.NADA -> Unit
            PassoDaBarraDaSelecao.ESPERAR -> lista.postDelayed(depoisDaEspera, espera)
            PassoDaBarraDaSelecao.MOSTRAR_SEM_MENU -> {
                // Outro parágrafo que ganhou a seleção é sessão nova da barra (ver ultimaSelecaoSemMenu).
                val outraSelecao = viva !== ultimaSelecaoSemMenu
                ultimaSelecaoSemMenu = viva
                barra.mostrar(outraSelecao = outraSelecao) {
                    (viva?.customSelectionActionModeCallback as? MenuDaSelecaoDeTexto)?.linhaDoInicioDaSelecaoNaTela()
                }
            }
            // Sem seleção viva, o trecho guardado segue na barra, sem descer a lista (ele está fora da tela).
            PassoDaBarraDaSelecao.MOSTRAR_GUARDADA -> barra.mostrar(outraSelecao = false) { null }
            PassoDaBarraDaSelecao.ESCONDER -> esconderBarra()
        }
        val comSelecao = viva != null || ultimaSelecaoSemMenu?.hasSelection() == true
        ligarVoltar(voltarDeveLigar(comSelecao))
    }

    /**
     * Seleção nova substitui o trecho guardado, e o toque curto que o limpa (rb-58) tem a marca, consumida aqui. Sem
     * nenhuma das duas, não mexe em nada.
     */
    private fun limparPelaSelecaoOuPeloToque(viva: TextView?) {
        if (viva == null && !lista.toqueLimpaGuardada) return
        lista.toqueLimpaGuardada = false
        if (lista.guardada.ativa) limparGuardada()
    }

    private fun esconderBarra() {
        barra.esconder()
        // Seleção sem dono (foco num botão da barra, barra sumiu): desfaz, senão fica órfã (rb-57).
        ultimaSelecaoSemMenu?.takeIf { !it.isFocused }?.desfazerSelecaoSemMenu()
        // Já estamos num post: o aviso de liberar o texto (notifyItemChanged) é seguro aqui. Não chame
        // liberarDepois daqui, senão a conferência viraria um laço de posts.
        if (!lista.textoEmUso) lista.aoLiberarTexto?.invoke()
    }

    /** O Voltar fica ligado só com a barra acesa, sem menu aberto e com seleção ou trecho guardado (rb-58). */
    private fun voltarDeveLigar(comSelecao: Boolean): Boolean =
        lista.selecoesAbertas == 0 && (comSelecao || lista.guardada.ativa) && barra.visivel

    private fun ligarVoltar(ligado: Boolean) {
        val atual = voltar
        if (atual != null) {
            atual.isEnabled = ligado
        } else if (ligado) {
            val novo = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false
                    // Com o foco num botão da barra (rb-57), a seleção viva é a última que a barra mostrou.
                    val viva = lista.textoComSelecaoViva ?: ultimaSelecaoSemMenu?.takeIf { it.hasSelection() }
                    when {
                        viva != null -> {
                            viva.desfazerSelecao()
                            lista.liberarDepois()
                        }
                        // Trecho guardado (rb-58): o Voltar limpa o trecho e o Leitor fica aberto.
                        lista.guardada.ativa -> limparGuardada()
                        // Sem seleção, o Voltar segue para quem vem depois (a Activity).
                        else -> activity.onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
            voltar = novo
            activity.onBackPressedDispatcher.addCallback(activity, novo)
        }
    }

    private companion object {
        // Folga sobre a janela do duplo toque, em ms: o Editor reabre o modo logo depois dela, se for reabrir.
        const val FOLGA_DA_ESPERA_MS = 32L
    }
}
