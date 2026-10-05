package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.data.QueueItem
import com.jonjonesbr.audiobookgen.data.QueueStatus
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.service.ConversionWorker
import com.jonjonesbr.audiobookgen.data.QueueRepository
import com.jonjonesbr.audiobookgen.service.QueueManager
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QueueActivity : BasePlayerActivity() {

    companion object {
        const val RESULT_INICIAR_FILA = 42
    }

    private lateinit var recyclerFila: RecyclerView
    private lateinit var tvFilaVazia: TextView
    private lateinit var btnIniciarFila: Button
    private lateinit var btnLimparFila: Button
    private lateinit var btnLerTudoFila: Button
    private lateinit var adapter: QueueAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val prefs get() = getSharedPreferences("audiobookgen_prefs", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)
        configurarMiniPlayerBotoes()

        recyclerFila = findViewById(R.id.recyclerFila)
        tvFilaVazia = findViewById(R.id.tvFilaVazia)
        btnIniciarFila = findViewById(R.id.btnIniciarFila)
        btnLimparFila = findViewById(R.id.btnLimparFila)
        btnLerTudoFila = findViewById(R.id.btnLerTudoFila)
        // T6.1 (edge-to-edge): footer ancorado na base — evita ficar atrás da barra de gestos.
        findViewById<View>(R.id.layoutFooterFila).aplicarPaddingInferiorComNavigationBar()

        findViewById<ImageButton>(R.id.btnVoltarFila).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnAjudaQueue)
            .setOnClickListener { HelpActivity.start(this, HelpTopico.FILA) }

        setupRecycler()
        carregarFilaPersistida()

        btnIniciarFila.setOnClickListener {
            salvarFila()
            // O processamento da fila roda na tela Conversões (não devolve resultado para a tela antiga, que iniciaria de novo).
            startActivity(Intent(this, ConversoesActivity::class.java).putExtra(ConversoesActivity.EXTRA_INICIAR_FILA, true))
            finish()
        }

        btnLimparFila.setOnClickListener {
            val processando = QueueManager.items.any { it.status == QueueStatus.PROCESSANDO }
            if (processando) {
                QueueManager.limparNaoAtivos()
            } else {
                QueueManager.items.clear()
            }
            salvarFila()
            adapter.submitList(QueueManager.items.toList())
            atualizarEstado()
        }

        btnLerTudoFila.setOnClickListener { lerTodaFila() }
    }

    private fun setupRecycler() {
        adapter = QueueAdapter(
            onRemove = { item ->
                if (item.status == QueueStatus.PROCESSANDO) {
                    androidx.work.WorkManager.getInstance(this)
                        .cancelUniqueWork(ConversionWorker.WORK_NAME)
                }
                QueueManager.remover(item.id)
                adapter.submitList(QueueManager.items.toList())
                salvarFila()
                atualizarEstado()
            },
            onRetry = { item ->
                QueueManager.repetir(item.id)
                adapter.submitList(QueueManager.items.toList())
                salvarFila()
                atualizarEstado()
            },
            onLer = { item -> abrirLeituraGuiada(item) },
            onDragStart = { holder -> itemTouchHelper.startDrag(holder) }
        )

        recyclerFila.layoutManager = LinearLayoutManager(this)
        recyclerFila.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val fromItem = QueueManager.items.getOrNull(from.bindingAdapterPosition)
                val toItem = QueueManager.items.getOrNull(to.bindingAdapterPosition)
                val podeMover = fromItem != null && toItem != null &&
                    fromItem.status == QueueStatus.PENDENTE && toItem.status == QueueStatus.PENDENTE
                if (!podeMover) return false
                adapter.moverItem(from.bindingAdapterPosition, to.bindingAdapterPosition)
                salvarFila()
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, dir: Int) = Unit

            override fun isLongPressDragEnabled() = false
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(recyclerFila)
    }

    private fun atualizarEstado() {
        val temItens = QueueManager.items.isNotEmpty()
        val temPendente = QueueManager.contarPendentes() > 0
        val resumo = QueueManager.resumo()
        recyclerFila.visibility = if (temItens) View.VISIBLE else View.GONE
        tvFilaVazia.visibility = if (!temItens) View.VISIBLE else View.GONE
        btnLimparFila.text = if (resumo.concluidos + resumo.erros > 0) {
            getString(R.string.btn_limpar_historico)
        } else {
            getString(R.string.btn_limpar_fila)
        }
        btnIniciarFila.isEnabled = temPendente
        btnLerTudoFila.isEnabled = temItens
    }

    private fun carregarFilaPersistida() {
        if (QueueManager.items.isNotEmpty()) {
            adapter.submitList(QueueManager.items.toList())
            atualizarEstado()
            salvarFila()
            return
        }
        lifecycleScope.launch {
            val itens = withContext(Dispatchers.IO) {
                QueueRepository.get(applicationContext).loadItems(prefs)
            }
            QueueManager.replaceAll(itens)
            adapter.submitList(QueueManager.items.toList())
            atualizarEstado()
        }
    }

    private fun salvarFila() {
        QueueManager.persistir(lifecycleScope, applicationContext, prefs)
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
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, item.caminhoLocal)
                .putExtra(ReaderActivity.EXTRA_AUTO_START_GUIDED, true)
        )
    }

    // Leitura guiada sequencial de TODA a fila, na ordem exibida (independente do status —
    // o arquivo de origem de cada item continua disponível mesmo já convertido/com erro).
    // Abre o 1º arquivo com o restante da lista em EXTRA_FILA_SEQUENCIA; ao terminar cada
    // livro, o ReaderActivity mesmo se encarrega de abrir o próximo e se fechar (ver
    // ReaderActivity.avancarSequenciaFila) — esta Activity não fica observando o progresso.
    private fun lerTodaFila() {
        val caminhos = QueueManager.items.map { it.caminhoLocal }
        if (caminhos.isEmpty()) return
        val restante = ArrayList(caminhos.drop(1))
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_CAMINHO, caminhos.first())
                .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)
                .putStringArrayListExtra(ReaderActivity.EXTRA_FILA_SEQUENCIA, restante)
        )
    }
}
