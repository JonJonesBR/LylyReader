package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca

/** O resultado da busca fica a 1/[FRACAO_MARGEM_BUSCA] da altura, contando do topo da lista. */
private const val FRACAO_MARGEM_BUSCA = 4

/** Margem do resultado na rolagem vertical (um quarto da altura); na horizontal, sem margem. */
internal fun margemDoResultadoBusca(vertical: Boolean, altura: Int): Int =
    if (vertical) altura / FRACAO_MARGEM_BUSCA else 0

/** O que o contador da busca mostra: nada, "sem resultados" ou a posição "atual de total". */
internal sealed interface ContadorBusca {
    object Oculto : ContadorBusca
    object SemResultados : ContadorBusca
    data class Posicao(val atual: Int, val total: Int) : ContadorBusca
}

/** Contador da busca: [indiceAtual] é base zero (o texto mostra [indiceAtual] + 1). */
internal fun contadorDaBusca(total: Int, indiceAtual: Int, consulta: String): ContadorBusca = when {
    total > 0 -> ContadorBusca.Posicao(atual = indiceAtual + 1, total = total)
    consulta.isNotEmpty() -> ContadorBusca.SemResultados
    else -> ContadorBusca.Oculto
}

/**
 * Busca no texto do leitor: barra com campo, contador, anterior/próximo e fechar. Leva o leitor ao primeiro achado
 * de cada resultado novo e ao resultado escolhido. Extraído de [ReaderActivity] pra reduzir o tamanho do arquivo
 * (rb-5). Padrão do projeto: activity + viewModel + views e callbacks no construtor, sem acessar campos da Activity.
 */
class ReaderBuscaController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val recyclerView: RecyclerView,
    private val views: Views,
    private val obterAdapter: () -> ParagrafoAdapter,
) {
    data class Views(
        val abrir: ImageButton,
        val layout: View,
        val campo: EditText,
        val contador: TextView,
        val anterior: ImageButton,
        val proximo: ImageButton,
        val fechar: ImageButton,
    )

    /** Último resultado já rolado: um resultado novo (identidade diferente) leva o leitor ao primeiro achado. */
    private var ultimaBuscaRolada: List<Int> = emptyList()

    /** Liga os botões e o campo. Chamado uma vez, no onCreate: o campo restaura o texto e dispara o TextWatcher. */
    fun configurar() {
        views.abrir.setOnClickListener { alternar() }

        views.campo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val naTela = (recyclerView.layoutManager as? LinearLayoutManager)
                    ?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
                viewModel.buscarTexto(s?.toString() ?: "", naTela)
            }
        })

        views.campo.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.navegarBusca(1)
                rolarParaResultadoAtual()
                true
            } else false
        }

        views.anterior.setOnClickListener {
            viewModel.navegarBusca(-1)
            rolarParaResultadoAtual()
        }

        views.proximo.setOnClickListener {
            viewModel.navegarBusca(1)
            rolarParaResultadoAtual()
        }

        views.fechar.setOnClickListener { alternar() }
    }

    /**
     * Abre ou fecha a busca. Ao fechar, a ordem importa: primeiro o ViewModel, depois a barra, depois o campo
     * (o setText("") manda a busca vazia ao ViewModel) e por fim o teclado.
     */
    fun alternar() {
        viewModel.toggleBusca()
        views.layout.visibility = if (viewModel.uiState.value.buscaVisivel) View.VISIBLE else View.GONE
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (viewModel.uiState.value.buscaVisivel) {
            views.campo.requestFocus()
            imm.showSoftInput(views.campo, InputMethodManager.SHOW_IMPLICIT)
        } else {
            views.campo.setText("")
            imm.hideSoftInputFromWindow(views.campo.windowToken, 0)
        }
    }

    /** Reflete o estado da busca na tela: barra, contador e rolagem até o primeiro achado de um resultado novo. */
    fun aplicar(state: ReaderUiState) {
        val totalResultados = state.buscaResultados.size
        // Chegou um resultado novo da busca: leva o leitor direto ao primeiro achado (antes ficava
        // parado, e o "Próximo" pulava o primeiro resultado).
        if (state.buscaResultados !== ultimaBuscaRolada) {
            ultimaBuscaRolada = state.buscaResultados
            if (totalResultados > 0) recyclerView.post { rolarParaResultadoAtual() }
        }
        views.layout.visibility = if (state.buscaVisivel) View.VISIBLE else View.GONE
        when (val contador = contadorDaBusca(totalResultados, state.buscaIndiceAtual, state.buscaQuery)) {
            ContadorBusca.Oculto -> views.contador.visibility = View.GONE
            ContadorBusca.SemResultados -> {
                views.contador.visibility = View.VISIBLE
                views.contador.text = activity.getString(R.string.busca_sem_resultados)
            }
            is ContadorBusca.Posicao -> {
                views.contador.visibility = View.VISIBLE
                views.contador.text = activity.getString(R.string.busca_contador, contador.atual, contador.total)
            }
        }
    }

    private fun rolarParaResultadoAtual() {
        val adapter = obterAdapter()
        val posicao = posicaoResultadoBusca(adapter.buscaResultados, adapter.buscaIndiceAtual)
        val lm = recyclerView.layoutManager as? LinearLayoutManager
        if (posicao != null && lm != null) {
            // Salto direto (o smoothScroll era lento em livros longos e deixava o parágrafo achado
            // colado na borda, às vezes atrás da barra de busca). Fica a ~1/4 da altura da tela.
            val vertical = lm.orientation == LinearLayoutManager.VERTICAL
            lm.scrollToPositionWithOffset(posicao, margemDoResultadoBusca(vertical, recyclerView.height))
        }
    }
}
