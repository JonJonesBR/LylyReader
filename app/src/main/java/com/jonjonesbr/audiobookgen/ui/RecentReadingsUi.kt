package com.jonjonesbr.audiobookgen.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.RecentReadingsStore
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge

/** Preenche um container com a lista "Continuar lendo" (usado no início e na biblioteca). */
object RecentReadingsUi {

    /**
     * Popula [container] com até [max] recentes válidos. Retorna a quantidade exibida
     * (0 → o chamador deve esconder a seção).
     */
    fun bind(
        container: ViewGroup,
        max: Int,
        onOpen: (RecentReadingsStore.Entry) -> Unit,
        onRemove: (RecentReadingsStore.Entry) -> Unit
    ): Int {
        val ctx = container.context
        // Livro já em leitura guiada ativa aparece no indicador do mini-player — evita
        // mostrar o mesmo livro duplicado ali e nesta lista ao mesmo tempo.
        val caminhoEmLeituraAtiva = GuidedPlaybackBridge.caminhoArquivo.takeIf { it.isNotEmpty() }
        val recentes = RecentReadingsStore.load(ctx)
            .filter { it.caminho != caminhoEmLeituraAtiva }
            .take(max)
        container.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        for (e in recentes) {
            val row = inflater.inflate(R.layout.view_recent_reading_item, container, false)
            row.findViewById<TextView>(R.id.tvRecentNome).text = e.nome
            row.setOnClickListener { onOpen(e) }
            row.findViewById<ImageButton>(R.id.btnRecentRemover).setOnClickListener { onRemove(e) }
            container.addView(row)
        }
        return recentes.size
    }

    /**
     * Liga a seção "Continuar lendo" (R.id.continuarLendoSection / continuarLendoContainer) a
     * uma [BasePlayerActivity], com o comportamento padrão: tocar abre o [ReaderActivity] no
     * documento continuando a leitura guiada; o × remove da lista e re-renderiza.
     *
     * Centraliza a fiação que estava duplicada em MainActivity e LibraryActivity — cada uma
     * chamava [bind] manualmente com os mesmos callbacks, variando só [max].
     */
    fun attach(activity: BasePlayerActivity, max: Int) {
        val section = activity.findViewById<View>(R.id.continuarLendoSection) ?: return
        val container = activity.findViewById<ViewGroup>(R.id.continuarLendoContainer) ?: return

        fun render() {
            val count = bind(
                container = container,
                max = max,
                onOpen = { e ->
                    activity.startActivity(
                        android.content.Intent(activity, ReaderActivity::class.java)
                            .putExtra(ReaderActivity.EXTRA_CAMINHO, e.caminho)
                            .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)
                    )
                },
                onRemove = { e ->
                    RecentReadingsStore.remove(activity, e.caminho)
                    render()
                }
            )
            section.visibility = if (count > 0) View.VISIBLE else View.GONE
        }
        render()
    }
}
