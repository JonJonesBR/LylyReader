package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.Capitulo
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Lista de capítulos do bottom sheet "Capítulos" (T3.4 — sumário navegável). */
class CapituloTocAdapter(
    private val capitulos: List<Capitulo>,
    private val ativo: Int,
    private val onCapituloTocado: (Int) -> Unit
) : RecyclerView.Adapter<CapituloTocAdapter.VH>() {

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvTitulo: TextView = itemView.findViewById(R.id.tvCapituloTocTitulo)
        val tvAtivo: TextView = itemView.findViewById(R.id.tvCapituloTocAtivo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_capitulo_toc, parent, false)
        return VH(view)
    }

    override fun getItemCount() = capitulos.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val capitulo = capitulos[position]
        holder.tvTitulo.text = capitulo.titulo
        holder.tvAtivo.visibility = if (position == ativo) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.tvAtivo.text = "●"
        holder.itemView.setOnClickListener { onCapituloTocado(position) }
    }
}
