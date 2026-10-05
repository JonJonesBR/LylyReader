package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.QueueManager
import com.jonjonesbr.audiobookgen.data.QueueItem
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class QueueAdapter(
    private val onRemove: (QueueItem) -> Unit,
    private val onRetry: (QueueItem) -> Unit,
    private val onLer: (QueueItem) -> Unit,
    private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
) : ListAdapter<QueueItem, QueueAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<QueueItem>() {
            override fun areItemsTheSame(old: QueueItem, new: QueueItem) = old.id == new.id
            override fun areContentsTheSame(old: QueueItem, new: QueueItem) = old == new
        }
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvNome: TextView = itemView.findViewById(R.id.tvQueueNome)
        val tvStatus: TextView = itemView.findViewById(R.id.tvQueueStatus)
        val tvMeta: TextView = itemView.findViewById(R.id.tvQueueMeta)
        val btnRetry: ImageButton = itemView.findViewById(R.id.btnRepetirFila)
        val btnRemover: ImageButton = itemView.findViewById(R.id.btnRemoverFila)
        val btnLer: ImageButton = itemView.findViewById(R.id.btnLerFila)
        val tvDrag: View = itemView.findViewById(R.id.tvDragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.tvNome.text = item.nome
        holder.tvMeta.text = metadataText(item)
        holder.btnRetry.visibility = View.GONE

        when (item.status) {
            QueueStatus.PENDENTE -> {
                holder.tvStatus.text = ctx.getString(R.string.queue_status_pending)
                holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.color_on_surface_dim))
                holder.btnRemover.visibility = View.VISIBLE
                holder.tvDrag.visibility = View.VISIBLE
            }
            QueueStatus.PROCESSANDO -> {
                holder.tvStatus.text = ctx.getString(R.string.queue_status_processing, item.progressoPct)
                holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.color_primary))
                holder.btnRemover.visibility = View.VISIBLE
                holder.tvDrag.visibility = View.GONE
            }
            QueueStatus.CONCLUIDO -> {
                holder.tvStatus.text = ctx.getString(R.string.queue_status_completed)
                holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.color_on_surface_muted))
                holder.btnRemover.visibility = View.GONE
                holder.tvDrag.visibility = View.GONE
            }
            QueueStatus.ERRO -> {
                val erroItem = item.erro
                holder.tvStatus.text = when (erroItem) {
                    null -> ctx.getString(R.string.queue_status_error)
                    com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA -> ctx.getString(R.string.queue_erro_interrompida)
                    // Filas salvas por versões anteriores guardavam o texto em português.
                    else -> if (erroItem.startsWith("Conversao interrompida")) ctx.getString(R.string.queue_erro_interrompida) else erroItem
                }
                holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.color_error))
                holder.btnRetry.visibility = View.VISIBLE
                holder.btnRemover.visibility = View.VISIBLE
                holder.tvDrag.visibility = View.GONE
            }
        }

        holder.btnRetry.setOnClickListener { onRetry(item) }
        holder.btnRemover.setOnClickListener { onRemove(item) }
        holder.btnLer.setOnClickListener { onLer(item) }

        @Suppress("ClickableViewAccessibility")
        holder.tvDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(holder)
            false
        }
    }

    fun moverItem(de: Int, para: Int) {
        QueueManager.mover(de, para)
        // notifyItemMoved() sozinho só anima a view — a lista interna do ListAdapter (usada
        // pelo próximo DiffUtil) não fica sabendo do movimento. Sem submitList(), a próxima
        // atualização (rotação, remoção de outro item) desfazia a reordenação.
        submitList(QueueManager.items.toList())
    }

    private fun metadataText(item: QueueItem): String {
        val motor = item.motor?.let { "Motor: ${it.uppercase()}" }
        val timestamp = item.finalizadoEmMillis ?: item.iniciadoEmMillis ?: item.criadoEmMillis
        val quando = DateFormat.format("dd/MM HH:mm", timestamp).toString()
        return listOfNotNull(motor, quando).joinToString("  -  ")
    }
}
