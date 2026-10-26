package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.NavegacaoCapitulos
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Lista de capítulos do bottom sheet "Capítulos": marca os já lidos (antes do [atual]) e o atual, mostra
 * discretas as partes técnicas (capa, direitos, sumário), filtra pela busca e, com [selecionados], deixa
 * marcar capítulos para converter em audiobook (o que os chips do leitor faziam).
 */
class CapituloTocAdapter(
    private val capitulos: List<Capitulo>,
    private val atual: Int,
    private val selecionados: MutableSet<Int>?,
    private val onCapituloTocado: (Int) -> Unit,
    private val onSelecaoMudou: () -> Unit = {}
) : RecyclerView.Adapter<CapituloTocAdapter.VH>() {

    private var visiveis: List<Int> = capitulos.indices.toList()

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvLido: TextView = itemView.findViewById(R.id.tvCapituloTocLido)
        val tvTitulo: TextView = itemView.findViewById(R.id.tvCapituloTocTitulo)
        val tvAtivo: TextView = itemView.findViewById(R.id.tvCapituloTocAtivo)
        val chkConverter: CheckBox = itemView.findViewById(R.id.chkCapituloTocConverter)
    }

    /** Mostra só os capítulos cujo título contém [busca]. */
    fun filtrar(busca: String) {
        visiveis = NavegacaoCapitulos.filtrar(capitulos, busca)
        notifyDataSetChanged()
        onSelecaoMudou() // o rótulo "Marcar todos" depende do que está à vista
    }

    /** Todos os capítulos mostrados (respeitando a busca) estão marcados para converter? */
    fun todosVisiveisMarcados(): Boolean = selecionados != null && visiveis.isNotEmpty() &&
        visiveis.all { it in selecionados }

    /** "Marcar todos" / "Desmarcar todos": vale para os capítulos mostrados (respeitando a busca). */
    fun marcarTodosVisiveis(marcar: Boolean) {
        val alvo = selecionados ?: return
        if (marcar) alvo.addAll(visiveis) else alvo.removeAll(visiveis.toSet())
        notifyDataSetChanged()
        onSelecaoMudou()
    }

    /** Posição do capítulo atual na lista mostrada (-1 se a busca o escondeu). */
    fun posicaoDoAtual(): Int = visiveis.indexOf(atual)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_capitulo_toc, parent, false)
        return VH(view)
    }

    override fun getItemCount() = visiveis.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val indice = visiveis[position]
        val titulo = capitulos[indice].titulo
        val ctx = holder.itemView.context
        holder.tvTitulo.text = NavegacaoCapitulos.tituloLimpo(titulo)
        holder.tvTitulo.alpha = if (NavegacaoCapitulos.ehParteTecnica(titulo)) ALFA_PARTE_TECNICA else 1f
        val estilo = if (indice == atual) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        holder.tvTitulo.setTypeface(null, estilo)
        holder.tvLido.visibility = if (indice < atual) View.VISIBLE else View.INVISIBLE
        holder.tvAtivo.visibility = if (indice == atual) View.VISIBLE else View.INVISIBLE
        holder.tvAtivo.text = "●"
        holder.itemView.contentDescription = when {
            indice == atual -> ctx.getString(R.string.cd_capitulo_toc_atual, holder.tvTitulo.text)
            indice < atual -> ctx.getString(R.string.cd_capitulo_toc_lido, holder.tvTitulo.text)
            else -> holder.tvTitulo.text
        }
        holder.itemView.setOnClickListener { onCapituloTocado(indice) }

        holder.chkConverter.setOnCheckedChangeListener(null)
        holder.chkConverter.visibility = if (selecionados != null) View.VISIBLE else View.GONE
        holder.chkConverter.isChecked = selecionados?.contains(indice) == true
        holder.chkConverter.contentDescription =
            ctx.getString(R.string.cd_marcar_capitulo_converter, holder.tvTitulo.text)
        holder.chkConverter.setOnCheckedChangeListener { _, marcado ->
            if (marcado) selecionados?.add(indice) else selecionados?.remove(indice)
            onSelecaoMudou()
        }
    }

    private companion object {
        const val ALFA_PARTE_TECNICA = 0.55f
    }
}
