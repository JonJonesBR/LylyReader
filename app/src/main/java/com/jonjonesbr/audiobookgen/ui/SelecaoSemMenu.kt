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
 * O Voltar também fica aqui: com a seleção viva e sem o modo, ele desfaz a seleção e não sai do Leitor. O callback é
 * criado e registrado só quando é preciso, para ficar acima do callback da Activity (o último registrado tem
 * prioridade).
 */
internal class SelecaoSemMenu(
    private val activity: AppCompatActivity,
    private val lista: RecyclerViewParagrafos,
    private val barra: BarraDaSelecaoDeTexto,
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
     * Com o menu aberto, quem chama já usa o próprio menu.
     */
    fun agirPelaBarra(acao: AcaoDaBarraDeSelecao) {
        (lista.textoComSelecaoViva()?.customSelectionActionModeCallback as? MenuDaSelecaoDeTexto)
            ?.executarPelaBarra(acao)
        lista.liberarDepois()
    }

    private fun decidir(jaEsperou: Boolean) {
        val viva = lista.textoComSelecaoViva()
        val passo = passoDaBarraDaSelecao(
            menuAberto = lista.selecoesAbertas > 0,
            selecaoViva = viva != null,
            emMovimento = lista.toqueEmAndamento || lista.scrollState != RecyclerView.SCROLL_STATE_IDLE,
            jaEsperou = jaEsperou,
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
            PassoDaBarraDaSelecao.ESCONDER -> {
                barra.esconder()
                // Já estamos num post: o aviso de liberar o texto (notifyItemChanged) é seguro aqui. Não chame
                // liberarDepois daqui, senão a conferência viraria um laço de posts.
                if (!lista.textoEmUso) lista.aoLiberarTexto?.invoke()
            }
        }
        ligarVoltar(lista.selecoesAbertas == 0 && viva != null && barra.visivel)
    }

    private fun ligarVoltar(ligado: Boolean) {
        val atual = voltar
        if (atual != null) {
            atual.isEnabled = ligado
        } else if (ligado) {
            val novo = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    isEnabled = false
                    val viva = lista.textoComSelecaoViva()
                    if (viva == null) {
                        // Sem seleção viva, o Voltar segue para quem vem depois (a Activity).
                        activity.onBackPressedDispatcher.onBackPressed()
                    } else {
                        viva.desfazerSelecao()
                        lista.liberarDepois()
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
