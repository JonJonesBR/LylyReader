package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.ExtractionResult
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.util.EditableTextPolicy
import com.jonjonesbr.audiobookgen.domain.ExtractTextUseCase
import com.jonjonesbr.audiobookgen.domain.MARGEM_NIVEL_PADRAO
import com.jonjonesbr.audiobookgen.util.PlayerStateHolder
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val extractUseCase = ExtractTextUseCase(app)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    // Caminho do arquivo aberto no leitor - usado para verificar se o sync é relevante
    private var caminhoArquivoAtual: String = ""

    // Job de scroll sync
    private var syncJob: Job? = null

    // ── Extração ──────────────────────────────────────────────────────────────

    fun carregarArquivo(caminho: String) {
        caminhoArquivoAtual = caminho
        _uiState.update { it.copy(carregando = true, erro = null, texto = null) }
        viewModelScope.launch {
            when (val result = extractUseCase.invoke(caminho)) {
                is ExtractionResult.Success -> {
                    _uiState.update {
                        it.copy(carregando = false, texto = result.texto)
                    }
                    executarLimpeza(java.io.File(caminho).extension.lowercase())
                    iniciarSync()
                }
                is ExtractionResult.Error -> {
                    _uiState.update { it.copy(carregando = false, erro = result.mensagem) }
                }
            }
        }
    }

    // ── Seleção de trecho ─────────────────────────────────────────────────────

    fun onParagrafoLongPress(indice: Int) {
        _uiState.update { it.copy(selecaoInicio = indice, selecaoFim = indice) }
    }

    fun onParagrafoToque(indice: Int) {
        val state = _uiState.value
        val inicio = state.selecaoInicio
        val fim = state.selecaoFim

        if (inicio == null) return // sem seleção ativa

        when {
            indice == fim -> {
                // Toque no próprio fim: contrai para o início
                _uiState.update { it.copy(selecaoFim = inicio) }
            }
            indice > (fim ?: inicio) -> {
                // Estende para frente
                _uiState.update { it.copy(selecaoFim = indice) }
            }
            indice < inicio -> {
                // Fora do range: reinicia
                _uiState.update { it.copy(selecaoInicio = indice, selecaoFim = indice) }
            }
            else -> {
                // Dentro do range: contrai até aqui
                _uiState.update { it.copy(selecaoFim = indice) }
            }
        }
    }

    fun selecionarCapitulo(indiceCapitulo: Int) {
        val texto = textoEfetivo() ?: return
        val cap = texto.capitulos.getOrNull(indiceCapitulo) ?: return
        _uiState.update {
            it.copy(
                selecaoInicio = cap.indiceParagrafoInicio,
                selecaoFim = cap.indiceParagrafoFim
            )
        }
    }

    fun limparSelecao() {
        _uiState.update { it.copy(selecaoInicio = null, selecaoFim = null) }
    }

    fun navegarParaCapitulo(indiceCapitulo: Int) {
        _uiState.update { it.copy(capituloAtivo = indiceCapitulo) }
    }

    // ── Aparência ─────────────────────────────────────────────────────────────

    fun setTamanhoFonte(sp: Float) = _uiState.update { it.copy(tamanhoFonte = sp) }
    fun setEspacamento(mult: Float) = _uiState.update { it.copy(espacamento = mult) }
    fun setTema(tema: TemaLeitura) = _uiState.update { it.copy(tema = tema) }

    // ── Scroll sync ───────────────────────────────────────────────────────────

    fun pausarSync() {
        syncJob?.cancel()
        _uiState.update { it.copy(syncAtivo = false, paragrafoSyncAtual = -1) }
    }

    fun pausarStandardSync() {
        syncJob?.cancel()
        syncJob = null
    }

    fun retomarSync() {
        iniciarSync()
    }

    fun setParagrafoSyncAtual(indice: Int) {
        _uiState.update { it.copy(paragrafoSyncAtual = indice) }
    }

    fun setProgressPct(pct: Float) {
        _uiState.update { it.copy(progressPct = pct) }
    }

    private fun iniciarSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            PlayerStateHolder.state.collect { playerState ->
                val texto = textoEfetivo() ?: return@collect

                val nomeArquivo = java.io.File(caminhoArquivoAtual).nameWithoutExtension
                val coincide = playerState.trackName.contains(nomeArquivo, ignoreCase = true)
                    || nomeArquivo.contains(playerState.trackName, ignoreCase = true)

                if (!playerState.isPlaying || !coincide || playerState.duration == 0) {
                    if (_uiState.value.syncAtivo) {
                        _uiState.update { it.copy(syncAtivo = false, paragrafoSyncAtual = -1) }
                    }
                    return@collect
                }

                val charEstimado = (playerState.currentPosition.toFloat() /
                        playerState.duration) * texto.totalChars
                val paragrafoAtual = texto.paragrafos
                    .indexOfLast { it.charOffset <= charEstimado }
                    .coerceAtLeast(0)

                val current = _uiState.value
                if (!current.syncAtivo || current.paragrafoSyncAtual != paragrafoAtual) {
                    _uiState.update {
                        it.copy(syncAtivo = true, paragrafoSyncAtual = paragrafoAtual)
                    }
                }
            }
        }
    }

    // ── Salvar preferências de aparência ─────────────────────────────────────

    fun carregarPreferencias(prefs: android.content.SharedPreferences) {
        val fonte = prefs.getFloat("reader_fonte", FONTE_PADRAO)
        val espacamento = prefs.getFloat("reader_espacamento", ESPACAMENTO_PADRAO)
        val temaOrd = prefs.getInt("reader_tema", TemaLeitura.CLARO.ordinal)
        val fonteSerifada = prefs.getBoolean("reader_fonte_serifada", false)
        val margemNivel = prefs.getInt("reader_margem_nivel", MARGEM_NIVEL_PADRAO)
        val rolagemHorizontal = prefs.getBoolean("reader_rolagem_horizontal", false)
        _uiState.update {
            it.copy(
                tamanhoFonte = fonte,
                espacamento = espacamento,
                tema = TemaLeitura.entries[temaOrd],
                fonteSerifada = fonteSerifada,
                margemNivel = margemNivel,
                rolagemHorizontal = rolagemHorizontal
            )
        }
    }

    fun salvarPreferencias(prefs: android.content.SharedPreferences) {
        val state = _uiState.value
        prefs.edit()
            .putFloat("reader_fonte", state.tamanhoFonte)
            .putFloat("reader_espacamento", state.espacamento)
            .putInt("reader_tema", state.tema.ordinal)
            .putBoolean("reader_fonte_serifada", state.fonteSerifada)
            .putInt("reader_margem_nivel", state.margemNivel)
            .putBoolean("reader_rolagem_horizontal", state.rolagemHorizontal)
            .apply()
    }

    fun setFonteSerifada(serifada: Boolean) = _uiState.update { it.copy(fonteSerifada = serifada) }

    fun setRolagemHorizontal(horizontal: Boolean) =
        _uiState.update { it.copy(rolagemHorizontal = horizontal) }

    /** @param nivel 0=estreita, 1=média, 2=larga (fora do intervalo é ignorado). */
    fun setMargemNivel(nivel: Int) {
        if (nivel !in 0..2) return
        _uiState.update { it.copy(margemNivel = nivel) }
    }

    // ── Limpeza de texto ──────────────────────────────────────────────────────

    fun executarLimpeza(formato: String) {
        // Sempre operar sobre o texto original bruto para que a blacklist e todos os
        // filtros sejam aplicados desde o início, e não sobre texto já processado.
        val textoOriginal = _uiState.value.textoOriginalBruto
            ?: _uiState.value.texto
            ?: return
        val textoAtual = textoOriginal

        _uiState.update {
            it.copy(
                limpandoTexto = true
            )
        }

        viewModelScope.launch {
            try {
                val resultado = withContext(Dispatchers.IO) {
                    val py = Python.getInstance()
                    val module = py.getModule("smart_cleaner")
                    val blacklist = obterBlacklist()
                    val blacklistJson = org.json.JSONArray(blacklist).toString()
                    module.callAttr(
                        "smart_clean_paragraphs_for_tts",
                        textoAtual.paragrafos.map { it.texto },
                        formato,
                        Locale.getDefault().language,
                        blacklistJson
                    )
                }

                // resultado é uma tupla Python: (paragrafos_limpos: list[str], linhas_removidas: int)
                val lista = resultado.asList()
                var offset = 0
                val paragrafosLimpos = lista[0].asList().mapIndexedNotNull { indice, pyTexto ->
                    val texto = pyTexto.toString().trim()
                    if (texto.isBlank()) return@mapIndexedNotNull null
                    textoAtual.paragrafos[indice].copy(texto = texto, charOffset = offset).also {
                        offset += texto.length + 2
                    }
                }

                val textoLimpo = TextoExtraido(
                    capitulos = reconstruirCapitulos(textoAtual.capitulos, paragrafosLimpos),
                    paragrafos = paragrafosLimpos,
                    totalChars = offset
                )

                val totalLinhasRemovidas = lista[1].toInt()

                _uiState.update {
                    it.copy(
                        // Preserva o original bruto na primeira limpeza para que
                        // "Refazer Limpeza" sempre parta do zero (não do já-processado)
                        textoOriginalBruto = it.textoOriginalBruto ?: textoAtual,
                        textoLimpo = textoLimpo,
                        spansRemovidos = emptyList(),
                        relatorioLinhasRemovidas = totalLinhasRemovidas,
                        limpandoTexto = false
                    )
                }
            } catch (e: Exception) {
                Log.e("ReaderViewModel", "Erro na limpeza de texto: ${e.message}", e)
                _uiState.update { it.copy(limpandoTexto = false) }
            }
        }
    }

    fun ativarModoEdicao() {
        _uiState.update { it.copy(modoEdicao = true, mostrandoAlteracoes = false) }
    }

    fun excluirOcorrencias(trecho: String) {
        val textoAtual = textoEfetivo() ?: return
        if (trecho.isBlank()) return
        salvarNaBlacklist(trecho)
        viewModelScope.launch {
            try {
                val resultado = withContext(Dispatchers.IO) {
                    Python.getInstance()
                        .getModule("smart_cleaner")
                        .callAttr("remove_global_occurrences", textoAtual.paragrafos.map { it.texto }, trecho)
                        .asList()
                }
                var offset = 0
                val paragrafos = resultado[0].asList().mapIndexedNotNull { indice, pyTexto ->
                    val texto = pyTexto.toString().trim()
                    if (texto.isBlank()) return@mapIndexedNotNull null
                    textoAtual.paragrafos[indice].copy(texto = texto, charOffset = offset).also {
                        offset += texto.length + 2
                    }
                }
                _uiState.update {
                    it.copy(
                        textoLimpo = TextoExtraido(
                            capitulos = reconstruirCapitulos(textoAtual.capitulos, paragrafos),
                            paragrafos = paragrafos,
                            totalChars = offset
                        ),
                        relatorioLinhasRemovidas = it.relatorioLinhasRemovidas + resultado[1].toInt(),
                        temEdicoes = true
                    )
                }
            } catch (e: Exception) {
                Log.e("ReaderViewModel", "Erro ao excluir ocorrencias: ${e.message}", e)
            }
        }
    }

    private fun obterBlacklist(): List<String> =
        com.jonjonesbr.audiobookgen.data.BlacklistStore(getApplication()).load()

    private fun salvarNaBlacklist(trecho: String) {
        com.jonjonesbr.audiobookgen.data.BlacklistStore(getApplication()).add(trecho)
    }

    private fun reconstruirCapitulos(
        capitulos: List<Capitulo>,
        paragrafos: List<Paragrafo>
    ): List<Capitulo> = capitulos.mapIndexedNotNull { indice, capitulo ->
        val indices = paragrafos.mapIndexedNotNull { novoIndice, paragrafo ->
            novoIndice.takeIf { paragrafo.indiceCapitulo == indice }
        }
        if (indices.isEmpty()) null else capitulo.copy(
            indiceParagrafoInicio = indices.first(),
            indiceParagrafoFim = indices.last()
        )
    }

    fun desativarModoEdicao() {
        _uiState.update { it.copy(modoEdicao = false) }
    }

    fun toggleMostrarAlteracoes() {
        _uiState.update { it.copy(mostrandoAlteracoes = !it.mostrandoAlteracoes, modoEdicao = false) }
    }

    fun atualizarParagrafoEditado(indice: Int, novoTexto: String) {
        _uiState.update { state ->
            val atual = state.textoLimpo ?: state.texto ?: return@update state
            val paragrafo = atual.paragrafos.getOrNull(indice) ?: return@update state
            if (paragrafo.texto == novoTexto) return@update state

            val atualizado = EditableTextPolicy.updateParagraph(atual, indice, novoTexto)

            if (state.textoLimpo != null) {
                state.copy(textoLimpo = atualizado, temEdicoes = true)
            } else {
                state.copy(texto = atualizado, temEdicoes = true)
            }
        }
    }

    fun textoEfetivo(): TextoExtraido? = _uiState.value.let { it.textoLimpo ?: it.texto }

    // ── Busca (T4.1) ──────────────────────────────────────────────────────────

    fun toggleBusca() {
        val visivel = !_uiState.value.buscaVisivel
        _uiState.update {
            it.copy(
                buscaVisivel = visivel,
                buscaQuery = "",
                buscaResultados = emptyList(),
                buscaIndiceAtual = -1
            )
        }
        if (!visivel) adapter?.let { a ->
            a.buscaResultados = emptyList(); a.buscaIndiceAtual = -1; a.notifyDataSetChanged()
        }
    }

    fun buscarTexto(query: String) {
        val texto = textoEfetivo() ?: return
        _uiState.update { it.copy(buscaQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(buscaResultados = emptyList(), buscaIndiceAtual = -1) }
            adapter?.let { it.buscaResultados = emptyList(); it.buscaIndiceAtual = -1; it.notifyDataSetChanged() }
            return
        }
        val q = query.lowercase()
        val resultados = texto.paragrafos.indices.filter { i ->
            texto.paragrafos[i].texto.lowercase().contains(q)
        }
        val indiceAtual = if (resultados.isNotEmpty()) 0 else -1
        _uiState.update {
            it.copy(buscaResultados = resultados, buscaIndiceAtual = indiceAtual)
        }
        adapter?.let { it.buscaResultados = resultados; it.buscaIndiceAtual = indiceAtual; it.notifyDataSetChanged() }
    }

    fun navegarBusca(direcao: Int) {
        val resultados = _uiState.value.buscaResultados
        if (resultados.isEmpty()) return
        val atual = _uiState.value.buscaIndiceAtual
        val novo = when (direcao) {
            1 -> if (atual + 1 < resultados.size) atual + 1 else 0
            -1 -> if (atual - 1 >= 0) atual - 1 else resultados.size - 1
            else -> atual
        }
        _uiState.update { it.copy(buscaIndiceAtual = novo) }
        adapter?.let { it.buscaResultados = resultados; it.buscaIndiceAtual = novo; it.notifyDataSetChanged() }
    }

    private var adapter: ParagrafoAdapter? = null

    fun registrarAdapter(adapter: ParagrafoAdapter) {
        this.adapter = adapter
    }

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
    }

    companion object {
        /** Tamanho de fonte padrão do leitor (sp). */
        private const val FONTE_PADRAO = 16f
        /** Espaçamento padrão entre linhas do leitor. */
        private const val ESPACAMENTO_PADRAO = 1.5f
    }
}
