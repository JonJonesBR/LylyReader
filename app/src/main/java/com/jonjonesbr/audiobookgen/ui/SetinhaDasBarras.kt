package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.ImageButton
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.GestoDasBarras
import com.jonjonesbr.audiobookgen.domain.Setinha

/**
 * Setinha que mostra ou recolhe a barra de cima no modo vertical (rb-66, passo 4). Quem decide se ela aparece e qual
 * seta mostra é a regra ([Setinha], pela [TelaCheiaDoLeitor]). Recolhida, a setinha fica no alto, com ⌄ para mostrar.
 * Com a barra aberta, ela desce até logo abaixo do cabeçalho ([translationY] = altura dele), com ⌃ para recolher.
 *
 * O clique só manda o gesto: o coordenador rearma o tempo pelo próprio caminho de gestos.
 */
internal class SetinhaDasBarras(
    private val botao: ImageButton,
    private val cabecalho: View,
    private val aoGesto: (GestoDasBarras) -> Unit,
) {
    // A seta que está valendo agora: o clique lê daqui o gesto a mandar.
    private var atual = Setinha.NENHUMA

    init {
        botao.setOnClickListener {
            when (atual) {
                Setinha.MOSTRAR -> aoGesto(GestoDasBarras.MOSTRAR)
                Setinha.RECOLHER -> aoGesto(GestoDasBarras.RECOLHER)
                Setinha.NENHUMA -> Unit
            }
        }
    }

    /** Aplica a setinha da regra: nenhuma (some), a que mostra ou a que recolhe, com ícone e descrição dela. */
    fun aplicar(setinha: Setinha) {
        atual = setinha
        botao.visibility = if (setinha == Setinha.NENHUMA) View.GONE else View.VISIBLE
        val recolher = setinha == Setinha.RECOLHER
        val descricao = botao.context.getString(
            if (recolher) R.string.cd_recolher_barras else R.string.cd_mostrar_barras,
        )
        botao.setImageResource(if (recolher) R.drawable.ic_busca_seta_cima else R.drawable.ic_busca_seta_baixo)
        botao.contentDescription = descricao
        botao.tooltipText = descricao
        atualizarPosicao()
    }

    /**
     * Desce a setinha até logo abaixo do cabeçalho quando a barra está aberta, e a deixa no alto quando não está.
     * Chamada também quando o cabeçalho muda de altura (a busca, o título do capítulo), porque a altura pode ainda ser
     * a antiga quando a barra acaba de aparecer.
     */
    fun atualizarPosicao() {
        botao.translationY = if (atual == Setinha.RECOLHER) cabecalho.height.toFloat() else 0f
    }
}
