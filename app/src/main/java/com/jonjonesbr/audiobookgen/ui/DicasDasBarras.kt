package com.jonjonesbr.audiobookgen.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Dica
import com.jonjonesbr.audiobookgen.domain.GestoDasBarras
import com.jonjonesbr.audiobookgen.domain.TelaDica

/** Gestos que a pessoa faz para mostrar o cabeçalho (não o tempo, a leitura andando nem a regra). */
private val gestosDaPessoa = setOf(GestoDasBarras.MOSTRAR, GestoDasBarras.ROLOU_PARA_CIMA)

/**
 * Dicas que acompanham as barras recolhidas do Leitor (rb-66, passo 7). O [BarrasDoLeitor] avisa o que aconteceu e esta
 * classe decide qual dica tentar e onde ela aponta. Cada uma tenta uma vez por abertura do Leitor. Quando a dica não
 * pode aparecer (alvo oculto, outra dica aberta, regra de [CoachMark]), não é marcada como vista e volta na próxima
 * vez.
 *
 * Tudo corre por post depois do layout do alvo: o cabeçalho e a setinha acabam de mudar de visibilidade e ainda não
 * têm tamanho quando o gesto chega.
 */
internal class DicasDasBarras(private val activity: AppCompatActivity, private val cabecalho: View) {

    // Tentado: a dica foi mostrada nesta abertura. Pendente: já há um post agendado para ela (rb-67, item 17a).
    private var cabecalhoTentado = false
    private var cabecalhoPendente = false
    private var verticalTentado = false
    private var verticalPendente = false
    private var tiraTentada = false
    private var tiraPendente = false

    /**
     * A pessoa mostrou o cabeçalho nesta abertura (a 1ª vez): a dica do menu ⋮ ou dos capítulos, a que estiver a seguir
     * na ordem da tela LEITOR. Vale para o deslize, rolar para cima, a setinha, o Tab e a ação de acessibilidade.
     */
    fun aoMudarCabecalho(gesto: GestoDasBarras, estavaVisivel: Boolean, agoraVisivel: Boolean) {
        if (cabecalhoTentado || cabecalhoPendente) return
        val mostrou = !estavaVisivel && agoraVisivel
        if (mostrou && gesto in gestosDaPessoa) {
            cabecalhoPendente = true
            cabecalho.doOnLayout {
                cabecalho.post {
                    cabecalhoPendente = false
                    cabecalhoTentado = CoachMark.mostrarProxima(activity, TelaDica.LEITOR) { dica ->
                        alvoDaDicaDoLeitor(activity, dica)
                    }
                }
            }
        }
    }

    /**
     * No vertical, o cabeçalho ficou recolhido na abertura: a dica da tela cheia aponta para a setinha. No modo páginas
     * quem cuida da dica é a [TelaCheiaDoLeitor], com a lista como alvo.
     */
    fun recolhidoNoVertical() {
        if (verticalTentado || verticalPendente) return
        // Alvo oculto não passa por layout: sem esta checagem o listener ficaria pendente e a dica sairia depois, fora
        // de hora. Sem marcar a flag, a dica tenta de novo na próxima vez que o cabeçalho for recolhido nesta abertura.
        val setinha = activity.findViewById<View?>(R.id.btnMostrarBarras)?.takeIf { it.isShown } ?: return
        verticalPendente = true
        setinha.doOnLayout {
            setinha.post {
                verticalPendente = false
                verticalTentado = CoachMark.tentar(activity, Dica.LEITOR_TELA_CHEIA_VERTICAL, setinha)
            }
        }
    }

    /** O painel de baixo apareceu recolhido na tira: a dica aponta para a seta da tira. Uma vez por abertura. */
    fun tiraApareceu() {
        if (tiraTentada || tiraPendente) return
        // Mesma regra da setinha: alvo oculto não recebe layout, então só marca a flag com o alvo à vista.
        val seta = activity.findViewById<View?>(R.id.btnGuidedRecolher)?.takeIf { it.isShown } ?: return
        tiraPendente = true
        seta.doOnLayout {
            seta.post {
                tiraPendente = false
                tiraTentada = CoachMark.tentar(activity, Dica.LEITOR_PAINEL_TIRA, seta)
            }
        }
    }
}
