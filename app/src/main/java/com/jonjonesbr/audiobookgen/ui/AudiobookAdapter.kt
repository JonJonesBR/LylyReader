package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.ChaveDoPlayer
import com.jonjonesbr.audiobookgen.util.IdentidadeDoAudiobook
import com.jonjonesbr.audiobookgen.util.formatarDuracao
import com.jonjonesbr.audiobookgen.util.indicesDoAudiobookNoPlayer
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
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
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Um audiolivro da aba Audiobooks. [nome] é o que aparece (título da etiqueta ou nome do arquivo);
 * [nomeArquivo] é o nome do arquivo com extensão, para os detalhes. [titulo] e [autor] vêm das etiquetas do MP3.
 */
data class AudiobookItem(
    val nome: String,
    val uri: Uri,
    val caminho: String?,
    val tamanhoBytes: Long,
    val dataAdicionado: Long,
    val duracaoMs: Long,
    val localizacao: String,
    val progressoPct: Int = 0,
    val nomeArquivo: String? = null,
    val titulo: String? = null,
    val autor: String? = null,
    /** Caminho no disco, quando o MediaStore informa (DATA): só serve para casar o cartão com o player. */
    val caminhoNoDisco: String? = null
) {
    /** Chave da capa em cache: muda com o arquivo (reconversão com o mesmo nome, arquivo ainda sendo gravado). */
    val chaveDaCapa: String get() = "$uri#$dataAdicionado#$tamanhoBytes"

    /** O que o cartão sabe do próprio áudio, para casar com o que o player toca (ver [mesmoAudiobook]). */
    val identidade: IdentidadeDoAudiobook
        get() = IdentidadeDoAudiobook(uri.toString(), caminho, caminhoNoDisco, nomeArquivo)
}

class AudiobookAdapter(
    private val onOuvir: (AudiobookItem) -> Unit,
    private val onCompartilhar: (AudiobookItem) -> Unit,
    private val onDeletar: (AudiobookItem) -> Unit,
    private val onExportarVideo: (AudiobookItem) -> Unit,
    private val onDetalhes: (AudiobookItem) -> Unit
) : ListAdapter<AudiobookItem, AudiobookAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AudiobookItem>() {
            override fun areItemsTheSame(a: AudiobookItem, b: AudiobookItem) = a.uri == b.uri
            override fun areContentsTheSame(a: AudiobookItem, b: AudiobookItem) = a == b
        }
        // Chaveadas por AudiobookItem.chaveDaCapa (uri, data e tamanho): arquivo novo não herda a capa antiga.
        private val COVER_CACHE = LruCache<String, Bitmap>(50)
        // Arquivos já lidos sem capa embutida: não tenta ler de novo a cada rolagem.
        private val SEM_CAPA_EMBUTIDA = LruCache<String, Boolean>(200)
        private const val PROGRESSO_COMPLETO = 100
        private const val ACAO_COMPARTILHAR = 1
        private const val ACAO_VIDEO = 2
        private const val ACAO_DETALHES = 3
        private const val ACAO_DELETAR = 4
        private const val ORDEM_MENU_DELETAR = 3
        private const val PAYLOAD_PLAYER = "player"
    }

    var onBindCover: ((AudiobookItem, ImageView) -> Unit)? = null

    // O que está no player agora (ver ChaveDoPlayer): o botão desse cartão vira pausar e a porcentagem
    // acompanha a posição em tempo real.
    private var chaveNoPlayer = ChaveDoPlayer(null)
    private var tocandoAgora = false
    private var pctAoVivo: Int? = null

    // Posições da lista que tocam no player (ver indicesDoAudiobookNoPlayer). Recalculadas só quando muda o
    // player ou a lista: o bind de cada cartão consulta isto, e refazer a conta da lista inteira a cada bind pesaria.
    private var marcadasNoPlayer: Set<Int> = emptySet()
    private var listaDoCalculo: List<AudiobookItem>? = null
    private var chaveDoCalculo: ChaveDoPlayer? = null

    /**
     * Guarda o que o player toca agora e repinta só os cartões afetados. Chamado a cada estado do serviço
     * (cerca de duas vezes por segundo): sem mudança, não faz nada. Cartão novo ou reaproveitado da lista
     * recebe o estado atual no onBindViewHolder, que lê estes campos.
     */
    fun atualizarPlayer(chave: ChaveDoPlayer, tocando: Boolean, pct: Int?) {
        val anterior = chaveNoPlayer
        if (anterior == chave && tocandoAgora == tocando && pctAoVivo == pct) return
        // Só mudou o pct (ou o estado de tocar) no mesmo audiolivro: as posições já estão no cache
        val eraDoAnterior = if (anterior == chave) {
            emptySet<Int>()
        } else {
            indicesDoAudiobookNoPlayer(anterior, currentList.map { it.identidade })
        }
        chaveNoPlayer = chave
        tocandoAgora = tocando
        pctAoVivo = pct
        (eraDoAnterior + posicoesNoPlayer()).forEach { notifyItemChanged(it, PAYLOAD_PLAYER) }
    }

    /**
     * Trocar a lista (filtro, exclusão, carga) pode deixar um só cartão com o nome repetido ou tirar a marca
     * de outro, e o DiffUtil não revincula cartão que não mudou. Repinta a marca de todos: é só o botão e a
     * barra, e a troca de lista é rara.
     */
    override fun onCurrentListChanged(previousList: List<AudiobookItem>, currentList: List<AudiobookItem>) {
        super.onCurrentListChanged(previousList, currentList)
        if (currentList.isNotEmpty()) notifyItemRangeChanged(0, currentList.size, PAYLOAD_PLAYER)
    }

    /**
     * Posições da lista atual que tocam no player. O player pode ter recebido a uri do item (aba Audiobooks), o
     * caminho do arquivo interno (tela do livro, leitor) ou uma uri "file:", e o cartão lista a cópia exportada:
     * [indicesDoAudiobookNoPlayer] casa tudo isso (P2).
     */
    private fun posicoesNoPlayer(): Set<Int> {
        if (listaDoCalculo !== currentList || chaveDoCalculo != chaveNoPlayer) {
            listaDoCalculo = currentList
            chaveDoCalculo = chaveNoPlayer
            marcadasNoPlayer = indicesDoAudiobookNoPlayer(chaveNoPlayer, currentList.map { it.identidade })
        }
        return marcadasNoPlayer
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvNome: TextView = view.findViewById(R.id.tvAudiobookNome)
        val tvInfo: TextView = view.findViewById(R.id.tvAudiobookInfo)
        val tvAutor: TextView = view.findViewById(R.id.tvAudiobookAutor)
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
        val chave = item.chaveDaCapa
        holder.tvNome.text = item.nome

        val durStr = if (item.duracaoMs > 0) formatarDuracao(item.duracaoMs) else "\u2014"
        val dataStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(item.dataAdicionado))
        holder.tvInfo.text = "$durStr  \u00b7  $dataStr"
        holder.tvAutor.text = item.autor.orEmpty()
        holder.tvAutor.visibility = if (item.autor.isNullOrBlank()) View.GONE else View.VISIBLE

        mostrarPlayer(holder, item, position in posicoesNoPlayer())

        // Toque no cartão ou no botão toca; as ações secundárias ficam no menu ⋮.
        holder.itemView.setOnClickListener { onOuvir(item) }
        holder.btnOuvir.setOnClickListener { onOuvir(item) }
        holder.btnMais.setOnClickListener { ancora ->
            android.widget.PopupMenu(ancora.context, ancora).apply {
                menu.add(0, ACAO_COMPARTILHAR, 0, R.string.btn_compartilhar)
                menu.add(0, ACAO_VIDEO, 1, R.string.cd_exportar_video)
                menu.add(0, ACAO_DETALHES, 2, R.string.bib_acao_detalhes)
                menu.add(0, ACAO_DELETAR, ORDEM_MENU_DELETAR, R.string.btn_deletar)
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        ACAO_COMPARTILHAR -> onCompartilhar(item)
                        ACAO_VIDEO -> onExportarVideo(item)
                        ACAO_DETALHES -> onDetalhes(item)
                        ACAO_DELETAR -> onDeletar(item)
                    }
                    true
                }
            }.show()
        }

        // A capa tipográfica entra já, como placeholder; a embutida (se houver) troca quando carregar.
        // A tag guarda a chave do item: a view reaproveitada na rolagem não pode receber a capa de outro.
        // A capa tipográfica é desenhada no quadrado da view (FIT_XY): com centerCrop, o recorte cortaria o título.
        holder.ivCover.tag = chave
        val cached = COVER_CACHE.get(chave)
        if (cached != null) {
            holder.ivCover.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.ivCover.setImageBitmap(cached)
        } else {
            holder.ivCover.scaleType = ImageView.ScaleType.FIT_XY
            holder.ivCover.setImageDrawable(
                CapaTipografica(item.nome, item.autor, chave, serifa(holder.itemView.context))
            )
            if (SEM_CAPA_EMBUTIDA.get(chave) == null) onBindCover?.invoke(item, holder.ivCover)
        }
    }

    private var literata: Typeface? = null
    private var literataCarregada = false

    /** Literata (fonte embutida), carregada uma vez; null cai na serifa do sistema. */
    private fun serifa(ctx: Context): Typeface? {
        if (!literataCarregada) {
            literataCarregada = true
            literata = runCatching { ResourcesCompat.getFont(ctx, R.font.literata) }.getOrNull()
        }
        return literata
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PLAYER }) {
            mostrarPlayer(holder, getItem(position), position in posicoesNoPlayer())
        } else {
            onBindViewHolder(holder, position)
        }
    }

    /** Botão tocar/pausar e porcentagem ouvida (ao vivo para o item que está no player). */
    private fun mostrarPlayer(holder: ViewHolder, item: AudiobookItem, noPlayer: Boolean) {
        val ctx = holder.itemView.context
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

    /** Guarda a capa embutida de um item (chave = [AudiobookItem.chaveDaCapa]). */
    fun cacheCover(chave: String, bitmap: Bitmap) {
        COVER_CACHE.put(chave, bitmap)
    }

    /** O arquivo do item não tem capa embutida (chave = [AudiobookItem.chaveDaCapa]): a lista não pede de novo. */
    fun marcarSemCapaEmbutida(chave: String) {
        SEM_CAPA_EMBUTIDA.put(chave, true)
    }

}
