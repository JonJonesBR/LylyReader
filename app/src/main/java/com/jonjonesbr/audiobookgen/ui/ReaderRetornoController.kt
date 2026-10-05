package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.tabs.TabLayout
import com.jonjonesbr.audiobookgen.R
import java.io.File

/**
 * Retorno do leitor com seleção/edições, bottom sheet de ações e diálogo de exclusão de
 * ocorrências — extraído de [ReaderActivity] pra reduzir o tamanho do arquivo (T2.2).
 * Padrão do projeto: activity + viewModel + callbacks no construtor, sem acessar campos da
 * Activity.
 */
class ReaderRetornoController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val obterCaminhoArquivo: () -> String,
        val obterCapitulosSelecionados: () -> Set<Int>,
        val consumirEdicoesAdapter: () -> Map<Int, String>,
        val onRegistrarTrechoTemp: (arquivo: File) -> Unit,
        val onExportarTextoLimpo: () -> Unit,
        val onExportarCapitulosPasta: () -> Unit,
        val onCompartilharTrecho: () -> Unit,
        val onBaixarCapitulo: () -> Unit,
        val onBaixarLivro: () -> Unit,
        val onOuvirAudiobook: () -> Unit,
        val onAbrirCapitulos: () -> Unit,
        val onConfigurarVozesPersonagens: () -> Unit,
        val onAbrirMarcadores: () -> Unit,
        val onAbrirDestaques: () -> Unit,
        val onIniciarLeituraGuiada: () -> Unit,
        val onPrepararCapitulo: () -> Unit,
        val onAbrirAparencia: () -> Unit,
        val onAbrirSleepTimer: () -> Unit,
    )

    fun retornarComSelecao() {
        aplicarEdicoesPendentes()
        val state = viewModel.uiState.value
        val texto = viewModel.textoEfetivo() ?: return
        val trechoTexto = when {
            callbacks.obterCapitulosSelecionados().isNotEmpty() -> {
                callbacks.obterCapitulosSelecionados().sorted()
                    .mapNotNull { texto.capitulos.getOrNull(it) }
                    .flatMap { cap ->
                        texto.paragrafos.subList(
                            cap.indiceParagrafoInicio.coerceAtLeast(0),
                            (cap.indiceParagrafoFim + 1).coerceAtMost(texto.paragrafos.size)
                        )
                    }
                    .joinToString("\n\n") { it.texto }
            }
            state.selecaoInicio != null -> {
                val inicio = state.selecaoInicio
                val fim = state.selecaoFim ?: inicio
                texto.paragrafos
                    .subList(inicio, (fim + 1).coerceAtMost(texto.paragrafos.size))
                    .joinToString("\n\n") { it.texto }
            }
            // Sem seleção nenhuma = FAB no estado "Converter para áudio" (livro inteiro),
            // não "Converter seleção" — era aqui que o clique não fazia nada.
            else -> texto.paragrafos.joinToString("\n\n") { it.texto }
        }

        salvarTextoTemporario(trechoTexto, ReaderActivity.ACAO_CONVERTER)
    }

    /**
     * Retorno do estado de ERRO do leitor (a extração de texto falhou e não há
     * [com.jonjonesbr.audiobookgen.domain.TextoExtraido] pra devolver): envia o arquivo ORIGINAL
     * pra MainActivity converter pelo pipeline tolerante (processar_arquivo), que não exige
     * estrutura de capítulos. MainActivity trata via [ReaderActivity.ACAO_CONVERTER_SEM_ESTRUTURA].
     *
     * Valida que o arquivo ainda existe: se sumiu, volta com CANCELED + aviso visível — a
     * MainActivity ignora silenciosamente EXTRA_TRECHO_PATH inexistente, então devolver um path
     * morto seria um dead end silencioso.
     */
    fun retornarConvertendoOriginalSemEstrutura() {
        val caminho = callbacks.obterCaminhoArquivo()
        if (caminho.isEmpty() || !File(caminho).exists()) {
            Toast.makeText(
                activity, R.string.toast_arquivo_nao_disponivel, Toast.LENGTH_LONG
            ).show()
            activity.setResult(android.app.Activity.RESULT_CANCELED)
            activity.finish()
            return
        }
        finalizarComResultado(caminho, File(caminho).name, ReaderActivity.ACAO_CONVERTER_SEM_ESTRUTURA)
    }

    fun retornarMantendoEdicoes() {
        aplicarEdicoesPendentes()
        val state = viewModel.uiState.value
        val texto = viewModel.textoEfetivo()
        if (!state.temEdicoes || texto == null) {
            activity.setResult(android.app.Activity.RESULT_CANCELED)
            activity.finish()
            return
        }

        val nomeBase = File(callbacks.obterCaminhoArquivo()).nameWithoutExtension.removeSuffix("_editado")
        salvarTextoTemporario(
            texto.paragrafos.joinToString("\n\n") { it.texto },
            ReaderActivity.ACAO_MANTER_EDICOES,
            "${nomeBase}_editado.txt"
        )
    }

    /** Itens da seção "LEITURA" do bottom sheet de ações — extraído pra manter [mostrarBottomSheetAcoes] enxuto. */
    private fun configurarItensSecaoLeitura(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        view: android.view.View
    ) {
        view.findViewById<android.view.View>(R.id.itemAcaoCapitulos).setOnClickListener {
            dialog.dismiss()
            callbacks.onAbrirCapitulos()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoMarcadores).setOnClickListener {
            dialog.dismiss()
            callbacks.onAbrirMarcadores()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoDestaques).setOnClickListener {
            dialog.dismiss()
            callbacks.onAbrirDestaques()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoLeituraGuiada).setOnClickListener {
            dialog.dismiss()
            callbacks.onIniciarLeituraGuiada()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoPrepararCapitulo).setOnClickListener {
            dialog.dismiss()
            callbacks.onPrepararCapitulo()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoConfigLivro).setOnClickListener {
            dialog.dismiss()
            ConfigLivroDialog.mostrar(activity, callbacks.obterCaminhoArquivo())
        }
        view.findViewById<android.view.View>(R.id.itemAcaoVozesPersonagens).setOnClickListener {
            dialog.dismiss()
            callbacks.onConfigurarVozesPersonagens()
        }
    }

    /** Abas do bottom sheet de ações — alterna qual grupo de itens fica visível. */
    private fun configurarTabsAcoesReader(view: android.view.View) {
        val tabs = view.findViewById<TabLayout>(R.id.tabsAcoesReader)
        val grupos = listOf(
            view.findViewById<android.view.View>(R.id.grupoAcoesConversao),
            view.findViewById<android.view.View>(R.id.grupoAcoesLeitura),
            view.findViewById<android.view.View>(R.id.grupoAcoesEdicao)
        )
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                grupos.forEachIndexed { i, grupo ->
                    grupo.visibility = if (i == tab.position) View.VISIBLE else View.GONE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /** Itens da aba "CONVERSÃO" do bottom sheet de ações — extraído pra manter [mostrarBottomSheetAcoes] enxuto. */
    private fun configurarItensSecaoConversao(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        view: android.view.View
    ) {
        view.findViewById<android.view.View>(R.id.itemAcaoConverterTudo).setOnClickListener {
            dialog.dismiss()
            retornarDocumentoAtual()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoExportar).setOnClickListener {
            dialog.dismiss()
            callbacks.onExportarTextoLimpo()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoExportarCapitulos).setOnClickListener {
            dialog.dismiss()
            callbacks.onExportarCapitulosPasta()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoCompartilharTrecho).setOnClickListener {
            dialog.dismiss()
            callbacks.onCompartilharTrecho()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoBaixarCapitulo).setOnClickListener {
            dialog.dismiss()
            callbacks.onBaixarCapitulo()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoBaixarLivro).setOnClickListener {
            dialog.dismiss()
            callbacks.onBaixarLivro()
        }

        val itemOuvirAudiobook = view.findViewById<android.view.View>(R.id.itemAcaoOuvirAudiobook)
        val mp3Vinculado =
            com.jonjonesbr.audiobookgen.data.LinkedBookStore.mp3Vinculado(activity, callbacks.obterCaminhoArquivo())
        itemOuvirAudiobook.visibility =
            if (mp3Vinculado != null && File(mp3Vinculado).exists()) View.VISIBLE else View.GONE
        itemOuvirAudiobook.setOnClickListener {
            dialog.dismiss()
            callbacks.onOuvirAudiobook()
        }
    }

    fun mostrarBottomSheetAcoes() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_acoes_reader, null)
        dialog.setContentView(view)
        configurarTabsAcoesReader(view)

        configurarItensSecaoConversao(dialog, view)
        configurarItensSecaoLeitura(dialog, view)
        view.findViewById<android.view.View>(R.id.itemAcaoEditar).setOnClickListener {
            dialog.dismiss()
            viewModel.ativarModoEdicao()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoExcluirPalavras).setOnClickListener {
            dialog.dismiss()
            mostrarDialogoExcluirOcorrencias()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoAparencia).setOnClickListener {
            dialog.dismiss()
            callbacks.onAbrirAparencia()
        }
        view.findViewById<android.view.View>(R.id.itemAcaoRefazerLimpeza).setOnClickListener {
            dialog.dismiss()
            aplicarEdicoesPendentes()
            viewModel.executarLimpeza(File(callbacks.obterCaminhoArquivo()).extension.lowercase())
        }
        view.findViewById<android.view.View>(R.id.itemAcaoSleepTimer).setOnClickListener {
            dialog.dismiss()
            callbacks.onAbrirSleepTimer()
        }

        view.findViewById<android.view.View>(R.id.itemAcaoAjuda).setOnClickListener {
            dialog.dismiss()
            com.jonjonesbr.audiobookgen.ui.HelpActivity.start(
                activity,
                com.jonjonesbr.audiobookgen.ui.HelpTopico.LEITOR
            )
        }

        dialog.show()
    }

    private fun mostrarDialogoExcluirOcorrencias() {
        val campo = EditText(activity).apply {
            hint = activity.getString(R.string.exclude_occurrences_hint)
            setSingleLine(false)
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.exclude_occurrences_title)
            .setMessage(R.string.exclude_occurrences_message)
            .setView(campo)
            .setPositiveButton(R.string.exclude_occurrences_confirm) { _, _ ->
                aplicarEdicoesPendentes()
                viewModel.excluirOcorrencias(campo.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun retornarDocumentoAtual() {
        aplicarEdicoesPendentes()
        val state = viewModel.uiState.value
        val texto = viewModel.textoEfetivo() ?: return
        if (!state.temEdicoes && state.textoLimpo == null) {
            finalizarComResultado(callbacks.obterCaminhoArquivo(), null, ReaderActivity.ACAO_CONVERTER)
            return
        }
        salvarTextoTemporario(texto.paragrafos.joinToString("\n\n") { it.texto }, ReaderActivity.ACAO_CONVERTER)
    }

    private fun salvarTextoTemporario(
        texto: String,
        acaoRetorno: String,
        nomeExibicao: String = "reader_selection.txt"
    ) {
        try {
            val arquivo = File(activity.cacheDir, "reader_selection_${System.currentTimeMillis()}.txt")
            arquivo.writeText(texto, Charsets.UTF_8)
            callbacks.onRegistrarTrechoTemp(arquivo)
            finalizarComResultado(arquivo.absolutePath, nomeExibicao, acaoRetorno)
        } catch (e: Exception) {
            Log.w("ReaderActivity", "Erro ao salvar seleção", e)
            Toast.makeText(activity, R.string.toast_save_selection_error, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Devolve [trechoPath]/[trechoNome]/[acaoRetorno] pra quem abriu o leitor. Se o leitor foi
     * aberto esperando um ActivityResult (ver [ReaderActivity.EXTRA_ABERTO_PARA_RESULTADO]),
     * `setResult` normal — o launcher em MainActivity está ouvindo. Caso contrário (leitor
     * aberto por notificação/mini-player/"Continuar Lendo", via `startActivity` simples), não
     * há ninguém esperando um ActivityResult — reabre a MainActivity com a MESMA ação pendente
     * nos extras do Intent (mesmo padrão de `processarNotificacaoAudiobook`) em vez de perder o
     * resultado em silêncio, que era o bug real: o usuário tocava "Converter" e o leitor só
     * fechava, sem converter nada e sem nenhum aviso.
     */
    private fun finalizarComResultado(trechoPath: String, trechoNome: String?, acaoRetorno: String) {
        val caminhoDoLivro = callbacks.obterCaminhoArquivo()
        if (acaoRetorno == ReaderActivity.ACAO_MANTER_EDICOES) {
            guardarEdicoesComoLivro(trechoPath, trechoNome)
            return
        }
        // Converter: vai direto para a tela Conversões, com a voz/velocidade/tom do livro (antes caía na tela antiga, que usava
        // só as preferências globais). Texto inteiro = vincula o MP3 ao livro; trecho = não vincula.
        val nomeLivro = File(caminhoDoLivro).nameWithoutExtension
        val inteiro = trechoNome == null || trechoPath == caminhoDoLivro
        activity.startActivity(
            Intent(activity, ConversoesActivity::class.java)
                .putExtra(ConversoesActivity.EXTRA_ARQUIVO, trechoPath)
                .putExtra(ConversoesActivity.EXTRA_LIVRO_CAMINHO, caminhoDoLivro)
                .putExtra(ConversoesActivity.EXTRA_LIVRO_INTEIRO, inteiro)
                .putExtra(ConversoesActivity.EXTRA_NOME, if (inteiro) nomeLivro else "$nomeLivro - trecho")
                .putExtra(ConversoesActivity.EXTRA_TITULO, nomeLivro)
        )
        activity.finish()
    }

    /** "Manter edições": o texto editado vira um novo livro da biblioteca (`<nome>_editado`), pronto para ler ou gerar audiobook. */
    private fun guardarEdicoesComoLivro(trechoPath: String, trechoNome: String?) {
        val nome = trechoNome ?: File(trechoPath).name
        activity.lifecycleScope.launch {
            val livro = runCatching {
                com.jonjonesbr.audiobookgen.data.BibliotecaStore.importarArquivo(
                    activity.applicationContext, File(trechoPath), nome, com.jonjonesbr.audiobookgen.domain.OrigemLivro.ARQUIVO, null
                )
            }.getOrNull()
            Toast.makeText(
                activity,
                if (livro != null) activity.getString(R.string.toast_edicoes_salvas, livro.titulo) else activity.getString(R.string.toast_save_selection_error),
                Toast.LENGTH_LONG
            ).show()
            activity.finish()
        }
    }

    fun aplicarEdicoesPendentes() {
        callbacks.consumirEdicoesAdapter().forEach { (indice, texto) ->
            viewModel.atualizarParagrafoEditado(indice, texto)
        }
    }
}
