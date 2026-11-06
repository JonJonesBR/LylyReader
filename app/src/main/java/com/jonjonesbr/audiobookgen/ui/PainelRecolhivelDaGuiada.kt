package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.GestoDasBarras

/** Tag das views que só aparecem com o painel aberto, e das que só aparecem com ele recolhido (rb-66, passo 5). */
private const val TAG_SO_ABERTO = "so_aberto"
private const val TAG_SO_RECOLHIDO = "so_recolhido"

/**
 * Painel de baixo da leitura guiada recolhível (rb-66, passo 5). Aberto, é o painel de sempre. Recolhido, vira uma
 * tira: o status, o "Voltar à narração", o play da tira, o progresso fino e a seta. Quem diz o que aparece é
 * [aplicar], com a regra das barras. As views entram pela tag do XML (so_aberto e so_recolhido), então retrato e
 * paisagem diferem só no layout.
 *
 * A visibilidade da raiz do painel (ele inteiro, com ou sem guiada) não é daqui: é da GuidedReadingBarController. Esta
 * classe mexe só nos filhos, e só troca o que muda (o painel é aplicado a cada atualização da tela).
 */
internal class PainelRecolhivelDaGuiada(
    private val raiz: View,
    private val aoGesto: (GestoDasBarras) -> Unit,
) {
    private val soAberto = mutableListOf<View>()
    private val soRecolhido = mutableListOf<View>()
    private val botaoSeta: ImageButton? = raiz.findViewById(R.id.btnGuidedRecolher)
    private var ultimoAberto: Boolean? = null
    private var ultimaSeta: Boolean? = null

    /**
     * Percorre a árvore do painel uma vez, junta as views com tag e liga a seta e o play da tira. Chamado uma vez, pelo
     * coordenador das barras.
     */
    fun ligar() {
        juntarComTag(raiz)
        botaoSeta?.setOnClickListener {
            aoGesto(if (ultimoAberto == true) GestoDasBarras.RECOLHER else GestoDasBarras.MOSTRAR)
        }
        // O play da tira usa o mesmo listener do play principal (que a GuidedReadingBarController já ligou).
        val principal: View? = raiz.findViewById(R.id.btnGuidedPlayPause)
        raiz.findViewById<ImageButton>(R.id.btnGuidedPlayPauseTira)?.setOnClickListener {
            principal?.performClick()
        }
    }

    private fun juntarComTag(v: View) {
        when (v.tag) {
            TAG_SO_ABERTO -> soAberto.add(v)
            TAG_SO_RECOLHIDO -> soRecolhido.add(v)
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) juntarComTag(v.getChildAt(i))
        }
    }

    /**
     * Mostra o painel aberto ([aberto] verdadeiro: tudo de hoje) ou recolhido (a tira). [seta] diz se a seta aparece:
     * ela some com TalkBack e no modo SEMPRE. Só mexe quando o valor muda.
     */
    fun aplicar(aberto: Boolean, seta: Boolean) {
        if (aberto != ultimoAberto) {
            ultimoAberto = aberto
            soAberto.forEach { it.visibility = visibilidade(aberto) }
            soRecolhido.forEach { it.visibility = visibilidade(!aberto) }
            botaoSeta?.let { trocarSeta(it, aberto) }
        }
        if (seta != ultimaSeta) {
            ultimaSeta = seta
            botaoSeta?.visibility = visibilidade(seta)
        }
    }

    /** Aberto, a seta recolhe (para baixo). Recolhido, ela mostra tudo (para cima). */
    private fun trocarSeta(botao: ImageButton, aberto: Boolean) {
        val descricao = botao.context.getString(
            if (aberto) R.string.cd_guided_recolher_controles else R.string.cd_guided_mostrar_controles,
        )
        botao.setImageResource(if (aberto) R.drawable.ic_busca_seta_baixo else R.drawable.ic_busca_seta_cima)
        botao.contentDescription = descricao
        botao.tooltipText = descricao
    }

    private fun visibilidade(mostrar: Boolean): Int = if (mostrar) View.VISIBLE else View.GONE
}

/**
 * Espelha o play principal na tira do painel recolhido (rb-66, passo 5): o mesmo ícone e a mesma descrição, e o
 * progresso fino (o parágrafo atual sobre o total). A GuidedReadingBarController chama esta função na raiz do painel,
 * logo depois de pôr o ícone do play principal. Sem tira (paisagem), não faz nada na parte que falta.
 */
internal fun View.espelharNaTira(tocando: Boolean, descricao: CharSequence, indice: Int, total: Int) {
    findViewById<ImageButton>(R.id.btnGuidedPlayPauseTira)?.let { tira ->
        tira.setImageResource(if (tocando) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        tira.contentDescription = descricao
        tira.tooltipText = descricao
    }
    findViewById<LinearProgressIndicator>(R.id.progressoGuiadaTira)?.let { progresso ->
        progresso.max = total.coerceAtLeast(1)
        progresso.progress = indice.coerceIn(0, progresso.max)
    }
}
