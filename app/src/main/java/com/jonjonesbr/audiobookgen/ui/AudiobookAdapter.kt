package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.formatarDuracao
import com.jonjonesbr.audiobookgen.util.formatarTamanho
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

data class AudiobookItem(
    val nome: String,
    val uri: Uri,
    val caminho: String?,
    val tamanhoBytes: Long,
    val dataAdicionado: Long,
    val duracaoMs: Long,
    val localizacao: String,
    val progressoPct: Int = 0
)

class AudiobookAdapter(
    private val onOuvir: (AudiobookItem) -> Unit,
    private val onCompartilhar: (AudiobookItem) -> Unit,
    private val onDeletar: (AudiobookItem) -> Unit,
    private val onExportarVideo: (AudiobookItem) -> Unit
) : ListAdapter<AudiobookItem, AudiobookAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AudiobookItem>() {
            override fun areItemsTheSame(a: AudiobookItem, b: AudiobookItem) = a.uri == b.uri
            override fun areContentsTheSame(a: AudiobookItem, b: AudiobookItem) = a == b
        }
        private val COVER_CACHE = LruCache<String, Bitmap>(50)
        private const val PROGRESSO_COMPLETO = 100
        private const val ACAO_COMPARTILHAR = 1
        private const val ACAO_VIDEO = 2
        private const val ACAO_DELETAR = 3
    }

    var onBindCover: ((AudiobookItem, ImageView) -> Unit)? = null

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvNome: TextView = view.findViewById(R.id.tvAudiobookNome)
        val tvInfo: TextView = view.findViewById(R.id.tvAudiobookInfo)
        val tvLocal: TextView = view.findViewById(R.id.tvAudiobookLocal)
        val layoutProgresso: View = view.findViewById(R.id.layoutProgressoAudiobook)
        val tvProgresso: TextView = view.findViewById(R.id.tvAudiobookProgresso)
        val progressBar: ProgressBar = view.findViewById(R.id.progressAudiobook)
        val btnOuvir: ImageButton = view.findViewById(R.id.btnAudiobookOuvir)
        val btnMais: ImageButton = view.findViewById(R.id.btnAudiobookMais)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audiobook, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.tvNome.text = item.nome

        val durStr = if (item.duracaoMs > 0) formatarDuracao(item.duracaoMs) else "\u2014"
        val dataStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(item.dataAdicionado))
        val tamanhoStr = formatarTamanho(item.tamanhoBytes)
        holder.tvInfo.text = "$durStr  \u00b7  $dataStr  \u00b7  $tamanhoStr"
        holder.tvLocal.text = item.localizacao
        holder.tvLocal.visibility = if (item.localizacao.isBlank()) View.GONE else View.VISIBLE

        val ctx = holder.itemView.context
        if (item.progressoPct > 0) {
            holder.layoutProgresso.visibility = View.VISIBLE
            holder.progressBar.progress = item.progressoPct
            holder.tvProgresso.text = if (item.progressoPct >= PROGRESSO_COMPLETO) {
                ctx.getString(R.string.library_progress_done)
            } else {
                ctx.getString(R.string.library_progress_listened, item.progressoPct)
            }
        } else {
            holder.layoutProgresso.visibility = View.GONE
        }

        // Toque no cartão ou no botão toca; as ações secundárias ficam no menu ⋮.
        holder.itemView.setOnClickListener { onOuvir(item) }
        holder.btnOuvir.setOnClickListener { onOuvir(item) }
        holder.btnMais.setOnClickListener { ancora ->
            android.widget.PopupMenu(ancora.context, ancora).apply {
                menu.add(0, ACAO_COMPARTILHAR, 0, R.string.btn_compartilhar)
                menu.add(0, ACAO_VIDEO, 1, R.string.cd_exportar_video)
                menu.add(0, ACAO_DELETAR, 2, R.string.btn_deletar)
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        ACAO_COMPARTILHAR -> onCompartilhar(item)
                        ACAO_VIDEO -> onExportarVideo(item)
                        ACAO_DELETAR -> onDeletar(item)
                    }
                    true
                }
            }.show()
        }

        val caminho = item.caminho
        if (caminho != null) {
            val cached = COVER_CACHE.get(caminho)
            if (cached != null) {
                holder.ivCover.setImageBitmap(cached)
            } else {
                holder.ivCover.setImageDrawable(null)
                onBindCover?.invoke(item, holder.ivCover)
            }
        } else {
            holder.ivCover.setImageDrawable(null)
        }
    }

    fun cacheCover(caminho: String, bitmap: Bitmap) {
        COVER_CACHE.put(caminho, bitmap)
    }

}
