package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.ConversaoPausadaStore
import com.jonjonesbr.audiobookgen.data.ERRO_FILA_INTERROMPIDA
import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.domain.NomeDoAudiobook
import com.jonjonesbr.audiobookgen.service.ControleDaConversao
import com.jonjonesbr.audiobookgen.service.ConversionNotificationManager
import com.jonjonesbr.audiobookgen.service.QueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A lista da fila dentro da tela Conversões (C1): mostra [QueueManager.items], deixa reordenar arrastando a alça,
 * remover, repetir e abrir a leitura guiada de cada item, e a leitura guiada da fila toda.
 *
 * Não observa a fila sozinha: a Activity chama [mostrar] a cada mudança (o coletor do uiState termina em
 * `atualizarFila()`). Tudo roda na main; o título do livro na Biblioteca é lido do disco em IO.
 */
internal class FilaNaTelaDeConversoes(
    private val activity: AppCompatActivity,
    recycler: RecyclerView,
    private val salvar: () -> Unit,          // QueueManager.persistir
    private val redesenhar: () -> Unit,      // salvar + mostrar(uiState) na Activity
    private val aoLimparPausada: () -> Unit, // atualizarPausadaDoDisco()
) {
    /** Título do livro na Biblioteca, por caminho do arquivo. `null` quando o arquivo não é da Biblioteca. */
    private val titulos = mutableMapOf<String, String?>()
    private var buscandoTitulos = false
    /** Progresso da conversão em andamento, para redesenhar a linha sem esperar o próximo toque. */
    private var ultimoPct: Int? = null
    private lateinit var adapter: QueueAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    init {
        recycler.layoutManager = LinearLayoutManager(activity)
        adapter = QueueAdapter(
            onRemove = { remover(it.id) },
            onRetry = { repetir(it.id) },
            onLer = ::abrirLeituraGuiada,
            onDragStart = { holder -> itemTouchHelper.startDrag(holder) },
        )
        recycler.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean = mover(from, to)

            override fun onSwiped(holder: RecyclerView.ViewHolder, dir: Int) = Unit

            override fun isLongPressDragEnabled() = false
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recycler)
    }

    /**
     * Redesenha a lista. [pctEmAndamento] é o progresso da conversão em curso e vai só para a linha PROCESSANDO.
     * É uma cópia: o item real de [QueueManager.items] não recebe o progresso aqui.
     */
    fun mostrar(pctEmAndamento: Int?) {
        ultimoPct = pctEmAndamento
        val linhas = QueueManager.items.map { item ->
            val pct = pctEmAndamento.takeIf { item.status == QueueStatus.PROCESSANDO } ?: item.progressoPct
            LinhaDaFila(
                item = item.copy(progressoPct = pct),
                titulo = NomeDoAudiobook.doItemDaFila(titulos[item.caminhoLocal], item.nome),
            )
        }
        // A cópia (e não o item real) é o que o DiffUtil compara: sem ela, ele compara o objeto com ele mesmo.
        adapter.submitList(linhas)
        buscarTitulosQueFaltam()
    }

    /**
     * Busca em IO o título da Biblioteca dos caminhos que ainda não foram consultados. O `null` também é guardado,
     * então um arquivo que não é da Biblioteca não é consultado de novo. Ao terminar, redesenha a lista.
     */
    private fun buscarTitulosQueFaltam() {
        if (buscandoTitulos) return
        val faltam = QueueManager.items.map { it.caminhoLocal }.distinct().filterNot(titulos::containsKey)
        if (faltam.isEmpty()) return
        buscandoTitulos = true
        activity.lifecycleScope.launch {
            try {
                val contexto = activity.applicationContext
                val achados = withContext(Dispatchers.IO) {
                    faltam.associateWith { caminho ->
                        runCatching { BibliotecaStore.tituloPorCaminho(contexto, caminho) }.getOrNull()
                    }
                }
                titulos.putAll(achados)
            } finally {
                buscandoTitulos = false
            }
            mostrar(ultimoPct)
        }
    }

    /**
     * Arrasto: troca a posição de dois itens PENDENTES. Usa os ids, não as posições: o worker pode tirar itens
     * concluídos da lista no meio do arrasto, e a lista da tela pode estar um passo atrás da lista real.
     * Se a ordem relativa dos dois na fila real já não é a mostrada na tela, não mexe: sem essa conferência,
     * um onMove repetido com a lista atrasada desfazia a troca e o item oscilava.
     */
    private fun mover(de: RecyclerView.ViewHolder, para: RecyclerView.ViewHolder): Boolean {
        val idDe = idNaPosicao(de)
        val idPara = idNaPosicao(para)
        val iDe = QueueManager.items.indexOfFirst { it.id == idDe }
        val iPara = QueueManager.items.indexOfFirst { it.id == idPara }
        val ordemIgual = (iDe < iPara) == (de.bindingAdapterPosition < para.bindingAdapterPosition)
        val podeMover = ordemIgual &&
            QueueManager.items.getOrNull(iDe)?.status == QueueStatus.PENDENTE &&
            QueueManager.items.getOrNull(iPara)?.status == QueueStatus.PENDENTE
        if (podeMover) {
            QueueManager.mover(iDe, iPara)
            salvar()
            mostrar(ultimoPct)
        }
        return podeMover
    }

    /** Id do item mostrado na posição do holder, ou null se a posição não é válida (NO_POSITION). */
    private fun idNaPosicao(holder: RecyclerView.ViewHolder): String? =
        adapter.currentList.getOrNull(holder.bindingAdapterPosition)?.item?.id

    /** Tira o item da fila. Usa o item real, não a cópia da linha. Se ele já saiu da fila, só redesenha. */
    private fun remover(id: String) {
        QueueManager.items.find { it.id == id }?.let { encerrarItem(it) }
        QueueManager.remover(id)
        redesenhar()
    }

    /** Antes de tirar o item: cancela a conversão dele se estiver rodando e descarta a pausada, se for a dele. */
    private fun encerrarItem(item: QueueItem) {
        val interrompido = item.status == QueueStatus.ERRO && item.erro == ERRO_FILA_INTERROMPIDA
        if (item.status == QueueStatus.PROCESSANDO || interrompido) {
            ControleDaConversao.pedirCancelamento(activity)
        }
        if (item.status == QueueStatus.PAUSADO) descartarPausada(item.id)
    }

    /** Remover um item pausado descarta a conversão guardada, só se ela for deste item. Não há job rodando. */
    private fun descartarPausada(itemId: String) {
        // T8: descartar_job — o job dir do Python ainda não é apagado aqui.
        activity.lifecycleScope.launch {
            val guardada = withContext(Dispatchers.IO) { ConversaoPausadaStore.carregar(activity) }
            if (guardada?.filaItemId != itemId) return@launch
            withContext(Dispatchers.IO) { ConversaoPausadaStore.limpar(activity) }
            ConversionNotificationManager(activity).cancelarPausada()
            aoLimparPausada()
        }
    }

    private fun repetir(id: String) {
        QueueManager.repetir(id)
        redesenhar()
    }

    // Leitura guiada direto de um item da fila, sem esperar a conversão terminar — o
    // arquivo de origem (item.caminhoLocal) já está copiado pra dentro do app desde que
    // entrou na fila e nunca é apagado pelo ConversionWorker, então funciona em qualquer
    // status (pendente/processando/concluído/erro). Mesmo padrão de intent usado pelo botão
    // "Leitura guiada" da tela principal (MainActivity.abrirLeituraGuiadaDirect), mas via
    // startActivity simples (sem EXTRA_ABERTO_PARA_RESULTADO) por não haver launcher
    // esperando um resultado aqui — ver o contrato documentado em
    // ReaderActivity.EXTRA_ABERTO_PARA_RESULTADO.
    private fun abrirLeituraGuiada(item: QueueItem) {
        activity.startActivity(
            Intent(activity, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, item.caminhoLocal)
                .putExtra(ReaderActivity.EXTRA_AUTO_START_GUIDED, true)
        )
    }

    // Leitura guiada sequencial de TODA a fila, na ordem exibida (independente do status —
    // o arquivo de origem de cada item continua disponível mesmo já convertido/com erro).
    // Abre o 1º arquivo com o restante da lista em EXTRA_FILA_SEQUENCIA. Quem avança de livro
    // é o GuidedPlayerManager (com a tela apagada também), que guarda a fila; a tela só segue
    // o livro que está sendo narrado (ver ReaderActivity.abrirLeitorDoLivroDaFila). Esta
    // tela não fica observando o progresso.
    fun lerTudo() {
        val caminhos = QueueManager.items.map { it.caminhoLocal }
        if (caminhos.isEmpty()) return
        val restante = ArrayList(caminhos.drop(1))
        activity.startActivity(
            Intent(activity, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, caminhos.first())
                .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)
                .putStringArrayListExtra(ReaderActivity.EXTRA_FILA_SEQUENCIA, restante)
        )
    }
}
