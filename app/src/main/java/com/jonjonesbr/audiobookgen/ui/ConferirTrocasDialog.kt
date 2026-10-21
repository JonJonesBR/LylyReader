package com.jonjonesbr.audiobookgen.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.AgrupadorDeTrocas
import com.jonjonesbr.audiobookgen.domain.ExtractTextUseCase
import com.jonjonesbr.audiobookgen.domain.ExtractionResult
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.domain.formatarLinhaTroca
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Diálogo somente leitura: mostra como a normalização trocaria o texto do livro, com a voz dada, antes da conversão.
 * Não altera nada. Fechar o diálogo (ou destruir a tela) cancela a conferência em andamento.
 */
object ConferirTrocasDialog {

    private const val LIMITE_TROCAS = 300

    fun show(activity: AppCompatActivity, caminhoDoLivro: String, voz: String) {
        val vista = LayoutInflater.from(activity).inflate(R.layout.dialog_conferir_trocas, null)
        val estado = vista.findViewById<TextView>(R.id.tvConferirEstado)
        val progresso = vista.findViewById<View>(R.id.pbConferir)
        val lista = vista.findViewById<ListView>(R.id.lvConferirTrocas)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.conferir_trocas_titulo)
            .setView(vista)
            .setPositiveButton(R.string.conv_fechar, null)
            .create()

        fun mostrarEstado(texto: Int) {
            progresso.visibility = View.GONE
            lista.visibility = View.GONE
            estado.setText(texto)
            estado.visibility = View.VISIBLE
        }

        val tarefa = activity.lifecycleScope.launch {
            try {
                val python = PythonEngineUseCase(activity)
                // Dicionário efetivo do livro (global + do livro) antes de normalizar, como a síntese faria.
                python.sincronizarDicionario(caminhoDoLivro)
                val texto = when (val extraido = ExtractTextUseCase(activity).invoke(caminhoDoLivro)) {
                    is ExtractionResult.Success -> extraido.texto.paragrafos.joinToString("\n") { it.texto }
                    is ExtractionResult.Error -> null
                }
                if (texto == null) {
                    mostrarEstado(R.string.conferir_trocas_erro)
                    return@launch
                }
                val trocas = python.relatorioDeTrocas(texto, voz)
                val agrupadas = AgrupadorDeTrocas.agrupar(trocas, LIMITE_TROCAS)
                if (agrupadas.isEmpty()) {
                    mostrarEstado(R.string.conferir_trocas_vazio)
                } else {
                    progresso.visibility = View.GONE
                    estado.visibility = View.GONE
                    lista.adapter = ArrayAdapter(
                        activity,
                        android.R.layout.simple_list_item_1,
                        agrupadas.map { formatarLinhaTroca(it) },
                    )
                    lista.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mostrarEstado(R.string.conferir_trocas_erro)
            }
        }

        // Fechar o diálogo cancela a conferência; destruir a tela fecha o diálogo (evita janela órfã).
        dialogo.setOnDismissListener { tarefa.cancel() }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                dialogo.dismiss()
            }
        })
        activity.mostrarDialogo(dialogo)
    }
}
