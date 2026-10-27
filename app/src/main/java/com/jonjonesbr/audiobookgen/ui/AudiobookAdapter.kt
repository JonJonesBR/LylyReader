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
        private const val PAYLOAD_PLAYER = "player"
    }

    var onBindCover: ((AudiobookItem, ImageView) -> Unit)? = null

    // O que está no player agora (uri do item, como o AudioPlayerService recebe): o botão desse
    // cartão vira pausar e a porcentagem acompanha a posição em tempo real.
    private var chaveNoPlayer: String? = null
    private var tocandoAgora = false
    private var pctAoVivo: Int? = null

    fun atualizarPlayer(caminho: String?, tocando: Boolean, pct: Int?) {
        val anterior = chaveNoPlayer
        if (anterior == caminho && tocandoAgora == tocando && pctAoVivo == pct) return
        chaveNoPlayer = caminho
        tocandoAgora = tocando
        pctAoVivo = pct
        currentList.forEachIndexed { i, item ->
            if (item.ehOArquivo(caminho) || item.ehOArquivo(anterior)) notifyItemChanged(i, PAYLOAD_PLAYER)
        }
    }

    /** O player pode ter recebido a uri do item (aba Audiobooks) ou o caminho do arquivo (tela do livro). */
    private fun AudiobookItem.ehOArquivo(chave: String?) =
        chave != null && (uri.toString() == chave || caminho == chave)

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

        mostrarPlayer(holder, item)

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

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PLAYER }) {
            mostrarPlayer(holder, getItem(position))
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /** Botão tocar/pausar e porcentagem ouvida (ao vivo para o item que está no player). */
    private fun mostrarPlayer(holder: ViewHolder, item: AudiobookItem) {
        val ctx = holder.itemView.context
        val noPlayer = item.ehOArquivo(chaveNoPlayer)
        val tocando = noPlayer && tocandoAgora
        holder.btnOuvir.setImageResource(if (tocando) R.drawable.ic_player_pause else R.drawable.ic_tocar)
        val rotulo = ctx.getString(if (tocando) R.string.cd_play_pause else R.string.btn_ouvir)
        holder.btnOuvir.contentDescription = rotulo
        holder.btnOuvir.tooltipText = rotulo

        val pct = (if (noPlayer) pctAoVivo else null) ?: item.progressoPct
        if (pct > 0) {
            holder.layoutProgresso.visibility = View.VISIBLE
            holder.progressBar.progress = pct
            holder.tvProgresso.text = if (pct >= PROGRESSO_COMPLETO) {
                ctx.getString(R.string.library_progress_done)
            } else {
                ctx.getString(R.string.library_progress_listened, pct)
            }
        } else {
            holder.layoutProgresso.visibility = View.GONE
        }
    }

    fun cacheCover(caminho: String, bitmap: Bitmap) {
        COVER_CACHE.put(caminho, bitmap)
    }

}
