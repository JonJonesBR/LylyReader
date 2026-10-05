package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.domain.LivroBiblioteca
import kotlinx.coroutines.launch

/** Ações de um livro da biblioteca, usadas pela Biblioteca e pela tela do livro (um só comportamento). */
object AcoesDoLivro {

    /**
     * Abre o livro no leitor. [continuar] retoma a leitura guiada de onde parou; [acaoInicial] (uma das
     * `ReaderActivity.ACAO_INICIAL_*`) abre direto um painel do leitor assim que o texto carrega.
     */
    fun abrirNoLeitor(activity: AppCompatActivity, livro: LivroBiblioteca, continuar: Boolean, acaoInicial: String? = null) {
        val arquivo = BibliotecaStore.arquivoDo(activity, livro)
        if (!arquivo.isFile) {
            Toast.makeText(activity, R.string.bib_erro_abrir, Toast.LENGTH_SHORT).show()
            return
        }
        activity.startActivity(
            Intent(activity, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
                .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, continuar)
                .apply { acaoInicial?.let { putExtra(ReaderActivity.EXTRA_ACAO_INICIAL, it) } }
        )
    }

    /**
     * Gera o audiobook do livro inteiro direto na tela "Conversões" (sem a tela antiga). [direto] traz o que foi escolhido na
     * hora (voz/motor/ritmo); o que faltar vem do perfil do livro e, por fim, das preferências globais.
     */
    fun gerarAudiobookDireto(activity: AppCompatActivity, livro: LivroBiblioteca, direto: OpcoesConversao) {
        val arquivo = BibliotecaStore.arquivoDo(activity, livro)
        if (!arquivo.isFile) {
            Toast.makeText(activity, R.string.bib_erro_abrir, Toast.LENGTH_SHORT).show()
            return
        }
        activity.startActivity(
            Intent(activity, ConversoesActivity::class.java)
                .putExtra(ConversoesActivity.EXTRA_LIVRO_CAMINHO, arquivo.absolutePath)
                .putExtra(ConversoesActivity.EXTRA_TITULO, livro.titulo)
                .apply {
                    direto.voz?.let { putExtra(ConversoesActivity.EXTRA_VOZ, it) }
                    direto.motor?.let { putExtra(ConversoesActivity.EXTRA_MOTOR, it) }
                    direto.ritmo?.let { putExtra(ConversoesActivity.EXTRA_RITMO, it) }
                    direto.estilo?.let { putExtra(ConversoesActivity.EXTRA_ESTILO, it) }
                }
        )
    }

    /**
     * Põe o livro na fila de processamento (voz/velocidade/tom são os do livro; a fila roda na tela Conversões).
     * Carrega a fila salva antes de mexer: persistir sem isso apagaria a que já estava guardada.
     */
    fun adicionarAFila(activity: AppCompatActivity, livro: LivroBiblioteca) {
        val arquivo = BibliotecaStore.arquivoDo(activity, livro)
        if (!arquivo.isFile) {
            Toast.makeText(activity, R.string.bib_erro_abrir, Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = activity.getSharedPreferences("audiobookgen_prefs", android.content.Context.MODE_PRIVATE)
        activity.lifecycleScope.launch {
            val app = activity.applicationContext
            com.jonjonesbr.audiobookgen.service.QueueManager.garantirCarregada(app, prefs)
            val ativo = com.jonjonesbr.audiobookgen.service.QueueManager.items.any {
                it.caminhoLocal == arquivo.absolutePath &&
                    (it.status == com.jonjonesbr.audiobookgen.data.QueueStatus.PENDENTE ||
                        it.status == com.jonjonesbr.audiobookgen.data.QueueStatus.PROCESSANDO)
            }
            if (ativo) {
                Toast.makeText(activity, R.string.gerar_fila_ja, Toast.LENGTH_SHORT).show()
                return@launch
            }
            com.jonjonesbr.audiobookgen.service.QueueManager.adicionar(arquivo.name, arquivo.absolutePath)
            com.jonjonesbr.audiobookgen.service.QueueManager.persistir(activity.lifecycleScope, app, prefs)
            Toast.makeText(
                activity,
                activity.getString(R.string.gerar_fila_ok, com.jonjonesbr.audiobookgen.service.QueueManager.contarPendentes()),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Confirma e apaga o livro (o audiobook gerado continua em Audiobooks). [aoApagar] roda depois. */
    fun confirmarApagar(activity: AppCompatActivity, livro: LivroBiblioteca, aoApagar: () -> Unit) {
        val liberado = Formatter.formatShortFileSize(activity, livro.tamanhoBytes)
        AlertDialog.Builder(activity)
            .setTitle(R.string.bib_apagar_titulo)
            .setMessage(activity.getString(R.string.bib_apagar_msg, livro.titulo, liberado))
            .setPositiveButton(R.string.bib_acao_apagar) { _, _ ->
                activity.lifecycleScope.launch {
                    BibliotecaStore.apagar(activity.applicationContext, livro)
                    Toast.makeText(activity, R.string.bib_apagado, Toast.LENGTH_SHORT).show()
                    aoApagar()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
