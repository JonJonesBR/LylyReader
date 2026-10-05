package com.jonjonesbr.audiobookgen.ui

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.EstadoDownload
import com.jonjonesbr.audiobookgen.service.AndamentoDownload
import com.jonjonesbr.audiobookgen.service.GrupoDownload
import com.jonjonesbr.audiobookgen.service.ItemBaixavel

/** Uma linha da Central: o item, o andamento atual e se abre uma nova seção. */
data class LinhaDownload(
    val item: ItemBaixavel,
    val andamento: AndamentoDownload,
    val abreGrupo: Boolean,
    val selecionado: Boolean
) {
    val estado: EstadoDownload get() = andamento.estado
    val selecionavel: Boolean
        get() = item.download != null &&
            (estado == EstadoDownload.NAO_BAIXADO || estado == EstadoDownload.PAUSADO || estado == EstadoDownload.ERRO)
}

class DownloadCentralAdapter(
    private val onPrincipal: (LinhaDownload) -> Unit,
    private val onSecundario: (LinhaDownload) -> Unit,
    private val onSelecionar: (String, Boolean) -> Unit
) : ListAdapter<LinhaDownload, DownloadCentralAdapter.VH>(DIFF) {

    companion object {
        private const val BARRA_MAX = 1000
        private val DIFF = object : DiffUtil.ItemCallback<LinhaDownload>() {
            override fun areItemsTheSame(old: LinhaDownload, new: LinhaDownload) = old.item.id == new.item.id
            // ItemBaixavel carrega lambdas: a identidade do item já basta, o que muda é o resto.
            override fun areContentsTheSame(old: LinhaDownload, new: LinhaDownload) =
                old.andamento == new.andamento && old.abreGrupo == new.abreGrupo && old.selecionado == new.selecionado
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val grupo: TextView = view.findViewById(R.id.tvDownloadGrupo)
        val check: CheckBox = view.findViewById(R.id.cbDownloadSelecionar)
        val nome: TextView = view.findViewById(R.id.tvDownloadNome)
        val estado: TextView = view.findViewById(R.id.tvDownloadEstado)
        val barra: ProgressBar = view.findViewById(R.id.pbDownload)
        val principal: Button = view.findViewById(R.id.btnDownloadPrincipal)
        val secundario: Button = view.findViewById(R.id.btnDownloadSecundario)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val linha = getItem(position)
        val ctx = holder.itemView.context
        val a = linha.andamento

        holder.grupo.visibility = if (linha.abreGrupo) View.VISIBLE else View.GONE
        if (linha.abreGrupo) {
            holder.grupo.setText(
                when (linha.item.grupo) {
                    GrupoDownload.LIVROS -> R.string.dl_central_grupo_livros
                    GrupoDownload.MOTORES -> R.string.dl_central_grupo_motores
                    GrupoDownload.CODIFICADORES -> R.string.dl_central_grupo_codificadores
                    GrupoDownload.IMPORTADAS -> R.string.dl_central_grupo_importadas
                }
            )
        }
        holder.nome.text = linha.item.nome

        holder.check.setOnCheckedChangeListener(null)
        holder.check.visibility = if (linha.selecionavel) View.VISIBLE else View.INVISIBLE
        holder.check.isChecked = linha.selecionado
        holder.check.setOnCheckedChangeListener { _, marcado -> onSelecionar(linha.item.id, marcado) }

        val mostraBarra = linha.estado == EstadoDownload.BAIXANDO || linha.estado == EstadoDownload.PAUSADO ||
            linha.estado == EstadoDownload.NA_FILA
        holder.barra.visibility = if (mostraBarra) View.VISIBLE else View.GONE
        holder.barra.isIndeterminate = linha.estado == EstadoDownload.BAIXANDO && a.total <= 0L
        holder.barra.max = BARRA_MAX
        holder.barra.progress = (a.fracao * BARRA_MAX).toInt()

        val pct = (a.fracao * 100).toInt()
        holder.estado.text = when (linha.estado) {
            EstadoDownload.NAO_BAIXADO -> ctx.getString(R.string.dl_central_estado_nao_baixado, linha.item.tamanhoMb)
            EstadoDownload.NA_FILA -> ctx.getString(R.string.dl_central_estado_fila)
            EstadoDownload.BAIXANDO ->
                if (a.total > 0L) {
                    ctx.getString(
                        R.string.dl_central_estado_baixando,
                        Formatter.formatShortFileSize(ctx, a.bytes), Formatter.formatShortFileSize(ctx, a.total), pct
                    )
                } else {
                    ctx.getString(R.string.dl_central_estado_baixando_sem_total)
                }
            EstadoDownload.PAUSADO -> ctx.getString(R.string.dl_central_estado_pausado, pct)
            EstadoDownload.PRONTO ->
                ctx.getString(R.string.dl_central_estado_pronto, Formatter.formatShortFileSize(ctx, a.ocupadoBytes))
            EstadoDownload.ERRO -> ctx.getString(R.string.dl_central_estado_erro, a.erro.orEmpty())
        }

        val (textoPrincipal, textoSecundario) = when (linha.estado) {
            EstadoDownload.NAO_BAIXADO -> R.string.dl_central_baixar to null
            EstadoDownload.NA_FILA -> R.string.dl_central_tirar_fila to null
            EstadoDownload.BAIXANDO -> R.string.dl_central_pausar to R.string.dl_central_cancelar
            EstadoDownload.PAUSADO -> R.string.dl_central_continuar to R.string.dl_central_cancelar
            EstadoDownload.ERRO -> R.string.dl_central_tentar_novamente to R.string.dl_central_cancelar
            EstadoDownload.PRONTO -> null to R.string.dl_central_apagar
        }
        holder.principal.visibility = if (textoPrincipal == null) View.GONE else View.VISIBLE
        textoPrincipal?.let { holder.principal.setText(it) }
        holder.secundario.visibility = if (textoSecundario == null) View.GONE else View.VISIBLE
        textoSecundario?.let { holder.secundario.setText(it) }
        holder.principal.setOnClickListener { onPrincipal(linha) }
        holder.secundario.setOnClickListener { onSecundario(linha) }
    }
}
