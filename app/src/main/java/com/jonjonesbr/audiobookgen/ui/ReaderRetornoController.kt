package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import com.jonjonesbr.audiobookgen.util.expandirEmPaisagem
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
    /** "Ler sem áudio": o menu de ações esconde tudo que converte, toca ou configura voz. */
    private val somenteLeitura: Boolean = false,
) {
    private val dialogos = DialogosAbertos(activity)
    private fun androidx.appcompat.app.AlertDialog.Builder.exibir(): androidx.appcompat.app.AlertDialog = dialogos.mostrar(this)

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
        val onConfigurarVozesPersonagens: () -> Unit,
        val onAbrirMarcacoes: () -> Unit,
        val onAbrirPronuncia: () -> Unit,
        val onAbrirAparencia: () -> Unit,
        val onAbrirSleepTimer: () -> Unit,
        val onClonarVoz: () -> Unit = {},
        /** Gera o audiobook dos capítulos marcados em ⋮ › Capítulos. */
        val onGerarCapitulosMarcados: () -> Unit = {},
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

    /** Liga um item do menu: fecha o menu e executa [acao]. */
    private fun android.view.View.item(
        id: Int,
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        acao: () -> Unit
    ) {
        findViewById<android.view.View>(id).setOnClickListener {
            dialog.dismiss()
            acao()
        }
    }

    /** Grupos "Leitura" e "Voz e pronúncia". */
    private fun configurarItensLeituraEVoz(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        view: android.view.View
    ) {
        view.item(R.id.itemAcaoMarcacoes, dialog) { callbacks.onAbrirMarcacoes() }
        view.item(R.id.itemAcaoSleepTimer, dialog) { callbacks.onAbrirSleepTimer() }
        view.item(R.id.itemAcaoAparencia, dialog) { callbacks.onAbrirAparencia() }
        view.item(R.id.itemAcaoConfigLivro, dialog) {
            ConfigLivroDialog.mostrar(activity, callbacks.obterCaminhoArquivo(), callbacks.onClonarVoz)
        }
        view.item(R.id.itemAcaoVozesPersonagens, dialog) { callbacks.onConfigurarVozesPersonagens() }
        view.item(R.id.itemAcaoPronuncia, dialog) { callbacks.onAbrirPronuncia() }
    }

    /** Grupos "Audiobook" e "Mais opções" (texto e arquivo). */
    private fun configurarItensAudiobookEMais(
        dialog: com.google.android.material.bottomsheet.BottomSheetDialog,
        view: android.view.View
    ) {
        view.item(R.id.itemAcaoGerarAudiobook, dialog) { mostrarDialogoGerarAudiobook() }
        val itemOuvirAudiobook = view.findViewById<android.view.View>(R.id.itemAcaoOuvirAudiobook)
        val mp3Vinculado =
            com.jonjonesbr.audiobookgen.data.LinkedBookStore.mp3Vinculado(activity, callbacks.obterCaminhoArquivo())
        itemOuvirAudiobook.visibility =
            if (mp3Vinculado != null && File(mp3Vinculado).exists()) View.VISIBLE else View.GONE
        view.item(R.id.itemAcaoOuvirAudiobook, dialog) { callbacks.onOuvirAudiobook() }

        val grupoMais = view.findViewById<android.view.View>(R.id.grupoAcoesMais)
        val setaMais = view.findViewById<android.view.View>(R.id.ivAcoesMaisOpcoes)
        val cabecalhoMais = view.findViewById<android.view.View>(R.id.itemAcaoMaisOpcoes)
        fun mostrarMais(aberto: Boolean) {
            grupoMais.visibility = if (aberto) View.VISIBLE else View.GONE
            setaMais.rotation = if (aberto) SETA_ABERTA else SETA_FECHADA
            androidx.core.view.ViewCompat.setStateDescription(
                cabecalhoMais,
                activity.getString(if (aberto) R.string.cd_section_expanded else R.string.cd_section_collapsed)
            )
        }
        mostrarMais(false)
        cabecalhoMais.setOnClickListener { mostrarMais(grupoMais.visibility != View.VISIBLE) }

        view.item(R.id.itemAcaoEditar, dialog) { viewModel.ativarModoEdicao() }
        view.item(R.id.itemAcaoExcluirPalavras, dialog) { mostrarDialogoExcluirOcorrencias() }
        view.item(R.id.itemAcaoRefazerLimpeza, dialog) {
            aplicarEdicoesPendentes()
            viewModel.executarLimpeza(File(callbacks.obterCaminhoArquivo()).extension.lowercase())
        }
        view.item(R.id.itemAcaoExportar, dialog) { mostrarDialogoExportarTexto() }
        view.item(R.id.itemAcaoCompartilharTrecho, dialog) { callbacks.onCompartilharTrecho() }
    }

    /**
     * Menu de ações do leitor: uma lista agrupada (Leitura, Voz e pronúncia, Audiobook) e "Mais opções"
     * fechado. Antes eram 3 abas com 19 itens, alguns repetidos.
     */
    fun mostrarBottomSheetAcoes() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_acoes_reader, null)
        dialog.setContentView(view)
        dialog.expandirEmPaisagem()
        itensAcaoClicaveis.forEach { view.findViewById<android.view.View>(it).marcarComoBotao() }

        configurarItensLeituraEVoz(dialog, view)
        configurarItensAudiobookEMais(dialog, view)
        view.item(R.id.itemAcaoAjuda, dialog) {
            com.jonjonesbr.audiobookgen.ui.HelpActivity.start(
                activity,
                com.jonjonesbr.audiobookgen.ui.HelpTopico.LEITOR
            )
        }

        if (somenteLeitura) {
            // "Ler sem áudio": some tudo que converte, toca ou configura voz.
            listOf(R.id.grupoAcoesVoz, R.id.grupoAcoesAudiobook, R.id.itemAcaoSleepTimer)
                .forEach { view.findViewById<android.view.View>(it).visibility = View.GONE }
        }

        dialog.show()
    }

    /**
     * "Gerar audiobook": o livro inteiro, só o capítulo em leitura ou, se houver, os capítulos marcados em
     * ⋮ › Capítulos (antes os marcados só tinham o botão sobre o texto, que some com a leitura guiada aberta).
     */
    private fun mostrarDialogoGerarAudiobook() {
        val texto = viewModel.textoEfetivo() ?: return
        val capitulo = texto.capitulos.getOrNull(viewModel.uiState.value.capituloAtivo)
        val marcados = callbacks.obterCapitulosSelecionados().size
        if (capitulo == null && marcados == 0) {
            callbacks.onBaixarLivro()
            return
        }
        val opcoes = buildList<Pair<String, () -> Unit>> {
            if (marcados > 0) {
                add(
                    activity.resources.getQuantityString(R.plurals.gerar_audiobook_marcados, marcados, marcados) to
                        callbacks.onGerarCapitulosMarcados
                )
            }
            add(activity.getString(R.string.gerar_audiobook_livro) to callbacks.onBaixarLivro)
            if (capitulo != null) {
                val titulo = com.jonjonesbr.audiobookgen.domain.NavegacaoCapitulos.tituloLimpo(capitulo.titulo)
                add(activity.getString(R.string.gerar_audiobook_capitulo, titulo) to callbacks.onBaixarCapitulo)
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.gerar_audiobook_titulo)
            .setItems(opcoes.map { it.first }.toTypedArray()) { _, which -> opcoes[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /** "Exportar texto": um arquivo só ou um por capítulo. */
    private fun mostrarDialogoExportarTexto() {
        val opcoes = arrayOf(
            activity.getString(R.string.exportar_arquivo_unico),
            activity.getString(R.string.exportar_por_capitulo)
        )
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(R.string.exportar_texto_titulo)
            .setItems(opcoes) { _, which ->
                if (which == 0) callbacks.onExportarTextoLimpo() else callbacks.onExportarCapitulosPasta()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }

    /** Todos os itens clicáveis do bottom sheet de ações: contêineres que o TalkBack anuncia como botão. */
    private val itensAcaoClicaveis = listOf(
        R.id.itemAcaoMarcacoes, R.id.itemAcaoSleepTimer, R.id.itemAcaoAparencia, R.id.itemAcaoConfigLivro,
        R.id.itemAcaoVozesPersonagens, R.id.itemAcaoPronuncia, R.id.itemAcaoGerarAudiobook,
        R.id.itemAcaoOuvirAudiobook, R.id.itemAcaoMaisOpcoes, R.id.itemAcaoEditar, R.id.itemAcaoExcluirPalavras,
        R.id.itemAcaoRefazerLimpeza, R.id.itemAcaoExportar, R.id.itemAcaoCompartilharTrecho, R.id.itemAcaoAjuda,
    )

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
            .exibir()
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

    private companion object {
        /** A seta de "Mais opções" é o chevron "próximo" girado: para baixo fechado, para cima aberto. */
        const val SETA_FECHADA = 90f
        const val SETA_ABERTA = -90f
    }
}
