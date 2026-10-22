package com.jonjonesbr.audiobookgen.ui

import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.BlacklistStore

/**
 * Diálogo de gestão da blacklist (termos ignorados na síntese) — extraído de [SettingsActivity]
 * (padrão de [ReaderRetornoController]: activity + dependências + callback no construtor).
 * [onMudou] é chamado quando a lista muda, para a Activity atualizar o resumo na tela.
 */
class BlacklistDialog(
    private val activity: AppCompatActivity,
    private val store: BlacklistStore,
    private val onMudou: () -> Unit,
) {
    private val dialogos = DialogosAbertos(activity)

    private fun obterBlacklist(): List<String> = store.load()

    private fun salvarBlacklist(list: List<String>) = store.save(list)

    private fun atualizarListaDialogo(container: LinearLayout) {
        container.removeAllViews()
        val termos = obterBlacklist()
        if (termos.isEmpty()) {
            val emptyTv = TextView(activity).apply {
                text = activity.getString(R.string.blacklist_empty)
                textSize = BLACKLIST_TEXT_SIZE_SP
                setTextColor(ContextCompat.getColor(activity, R.color.color_on_surface_muted))
                setPadding(
                    BLACKLIST_ITEM_PADDING_DP,
                    BLACKLIST_ITEM_PADDING_DP,
                    BLACKLIST_ITEM_PADDING_DP,
                    BLACKLIST_ITEM_PADDING_DP
                )
                gravity = android.view.Gravity.CENTER
            }
            container.addView(emptyTv)
            return
        }

        val inflater = activity.layoutInflater
        for (termo in termos) {
            val itemView = inflater.inflate(R.layout.item_blacklist_term, container, false)
            val tvTermo = itemView.findViewById<TextView>(R.id.tvTermo)
            val btnRemoverTermo = itemView.findViewById<ImageButton>(R.id.btnRemoverTermo)

            tvTermo.text = termo
            btnRemoverTermo.setOnClickListener {
                val novaLista = obterBlacklist().toMutableList()
                novaLista.remove(termo)
                salvarBlacklist(novaLista)
                atualizarListaDialogo(container)
                onMudou()
            }
            container.addView(itemView)
        }
    }

    fun mostrarDialogoBlacklist() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_blacklist, null)
        val etNovoTermo = dialogView.findViewById<EditText>(R.id.etNovoTermo)
        val btnAdicionarTermo = dialogView.findViewById<Button>(R.id.btnAdicionarTermo)
        val containerTermos = dialogView.findViewById<LinearLayout>(R.id.containerTermos)

        atualizarListaDialogo(containerTermos)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.title_blacklist)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()

        btnAdicionarTermo.setOnClickListener {
            val novoTermo = etNovoTermo.text.toString().trim()
            if (novoTermo.isEmpty()) return@setOnClickListener

            val termos = obterBlacklist().toMutableList()
            if (termos.contains(novoTermo)) {
                Toast.makeText(activity, activity.getString(R.string.toast_blacklist_duplicate), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            termos.add(novoTermo)
            salvarBlacklist(termos)
            etNovoTermo.text.clear()
            atualizarListaDialogo(containerTermos)
            onMudou()
        }

        dialogos.mostrar(dialog)
    }

    private companion object {
        private const val BLACKLIST_TEXT_SIZE_SP = 14f
        private const val BLACKLIST_ITEM_PADDING_DP = 16
    }
}
