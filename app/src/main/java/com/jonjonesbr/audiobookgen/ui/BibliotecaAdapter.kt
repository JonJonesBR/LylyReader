package com.jonjonesbr.audiobookgen.ui

import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.LivroBiblioteca
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** O que a grade da biblioteca mostra: o cartão "Continuar" (largura total) e os livros. */
sealed interface ItemBiblioteca {
    val chave: String

    data class Continuar(val livro: LivroBiblioteca, val progresso: Float) : ItemBiblioteca {
        override val chave get() = "continuar"
    }

    data class Livro(val livro: LivroBiblioteca, val progresso: Float?, val temAudio: Boolean) : ItemBiblioteca {
        override val chave get() = "livro:${livro.id}"
    }
}

class BibliotecaAdapter(
    private val scope: CoroutineScope,
    private val capaDe: (LivroBiblioteca) -> File?,
    private val onAbrir: (LivroBiblioteca, continuar: Boolean) -> Unit,
    private val onMenu: (View, LivroBiblioteca) -> Unit
) : ListAdapter<ItemBiblioteca, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int) = if (getItem(position) is ItemBiblioteca.Continuar) TIPO_CONTINUAR else TIPO_LIVRO

    /** Quantas colunas o item ocupa: o cartão Continuar usa a linha toda. */
    fun colunasDoItem(position: Int, total: Int) = if (getItemViewType(position) == TIPO_CONTINUAR) total else 1

    class VHContinuar(view: View) : RecyclerView.ViewHolder(view) {
        val cartao: LinearLayout = view.findViewById(R.id.cartaoContinuar)
        val capa: CapaView = view.findViewById(R.id.ivContinuarCapa)
        val titulo: TextView = view.findViewById(R.id.tvContinuarTitulo)
        val autor: TextView = view.findViewById(R.id.tvContinuarAutor)
        val barra: ProgressBar = view.findViewById(R.id.pbContinuar)
        val pct: TextView = view.findViewById(R.id.tvContinuarPct)
    }

    class VHLivro(view: View) : RecyclerView.ViewHolder(view) {
        val capa: CapaView = view.findViewById(R.id.ivBibCapa)
        val titulo: TextView = view.findViewById(R.id.tvBibTitulo)
        val estado: TextView = view.findViewById(R.id.tvBibEstado)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TIPO_CONTINUAR) {
            VHContinuar(inflater.inflate(R.layout.item_biblioteca_continuar, parent, false))
        } else {
            VHLivro(inflater.inflate(R.layout.item_biblioteca_livro, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ItemBiblioteca.Continuar -> {
                val h = holder as VHContinuar
                val livro = item.livro
                h.titulo.text = livro.titulo
                h.autor.text = livro.autor.orEmpty()
                h.autor.visibility = if (livro.autor.isNullOrBlank()) View.GONE else View.VISIBLE
                h.barra.progress = (item.progresso * BARRA_MAX).toInt()
                h.pct.text = h.itemView.context.getString(R.string.bib_progresso, (item.progresso * 100).toInt())
                mostrarCapa(h.capa, livro)
                h.cartao.setOnClickListener { onAbrir(livro, true) }
                h.cartao.setOnLongClickListener { onMenu(h.cartao, livro); true }
            }
            is ItemBiblioteca.Livro -> {
                val h = holder as VHLivro
                val livro = item.livro
                val ctx = h.itemView.context
                h.titulo.text = livro.titulo
                h.estado.text = when {
                    item.temAudio -> ctx.getString(R.string.bib_audio_pronto)
                    item.progresso != null && item.progresso > 0f -> ctx.getString(R.string.bib_progresso, (item.progresso * 100).toInt())
                    else -> ctx.getString(R.string.bib_nao_iniciado)
                }
                mostrarCapa(h.capa, livro)
                h.itemView.setOnClickListener { onAbrir(livro, false) }
                h.itemView.setOnLongClickListener { onMenu(h.itemView, livro); true }
            }
        }
    }

    private var literata: android.graphics.Typeface? = null
    private var literataCarregada = false

    /** Literata (fonte embutida), carregada uma vez; null cai na serifa do sistema. */
    private fun serifa(view: View): android.graphics.Typeface? {
        if (!literataCarregada) {
            literataCarregada = true
            literata = runCatching { androidx.core.content.res.ResourcesCompat.getFont(view.context, R.font.literata) }.getOrNull()
        }
        return literata
    }

    /** Capa tipográfica na hora; se o livro tem imagem própria, troca quando ela carregar (a marca evita capa errada na rolagem). */
    private fun mostrarCapa(view: CapaView, livro: LivroBiblioteca) {
        view.tag = livro.id
        view.setImageDrawable(CapaTipografica(livro.titulo, livro.autor, livro.id, serifa(view)))
        val arquivo = capaDe(livro) ?: return
        CapasDaBiblioteca.emCache(arquivo)?.let {
            view.setImageDrawable(BitmapDrawable(view.resources, it))
            return
        }
        scope.launch {
            val imagem = CapasDaBiblioteca.carregar(arquivo) ?: return@launch
            if (view.tag == livro.id) view.setImageDrawable(BitmapDrawable(view.resources, imagem))
        }
    }

    private companion object {
        const val TIPO_CONTINUAR = 0
        const val TIPO_LIVRO = 1
        const val BARRA_MAX = 1000
        val DIFF = object : DiffUtil.ItemCallback<ItemBiblioteca>() {
            override fun areItemsTheSame(a: ItemBiblioteca, b: ItemBiblioteca) = a.chave == b.chave
            override fun areContentsTheSame(a: ItemBiblioteca, b: ItemBiblioteca) = a == b
        }
    }
}
