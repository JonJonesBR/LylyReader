package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.ExtractionResult
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.util.EditableTextPolicy
import com.jonjonesbr.audiobookgen.domain.ExtractTextUseCase
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.util.PlayerStateHolder
import com.jonjonesbr.audiobookgen.util.TextoBuscavel
import com.jonjonesbr.audiobookgen.util.indiceInicialBusca
import com.jonjonesbr.audiobookgen.util.normalizarConsulta
import com.jonjonesbr.audiobookgen.util.ocorrenciasBusca
import com.jonjonesbr.audiobookgen.util.posicaoResultadoBusca
import com.jonjonesbr.audiobookgen.util.prepararParaBusca
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
                    // limpandoTexto já junto com o texto: quem lê o estado nunca vê o texto bruto "pronto" antes de a
                    // limpeza começar (o "Continuar" da leitura guiada espera o texto limpo, cujo índice foi o salvo).
                    _uiState.update {
                        it.copy(carregando = false, texto = result.texto, limpandoTexto = true)
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

    /** Para o acompanhamento do audiobook (a leitura guiada assumiu) sem apagar o parágrafo em destaque. */
    fun pausarStandardSync() {
        syncJob?.cancel()
        syncJob = null
        // Sem isso o syncAtivo do audiobook ficava ligado e o leitor seguia rolando por ele durante a guiada.
        if (_uiState.value.syncAtivo) _uiState.update { it.copy(syncAtivo = false) }
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

    /** Âncoras de sincronia (trecho do MP3 → caractere do livro) do audiobook, calculadas uma vez por MP3 e texto. */
    private var ancorasCache: Triple<String, com.jonjonesbr.audiobookgen.domain.TextoExtraido, List<Pair<Long, Int>>>? = null

    private suspend fun ancorasDoAudiobook(
        mp3: String,
        texto: com.jonjonesbr.audiobookgen.domain.TextoExtraido,
    ): List<Pair<Long, Int>> {
        ancorasCache?.let { (m, t, a) -> if (m == mp3 && t === texto) return a }
        val ancoras = withContext(Dispatchers.Default) {
            val trechos = com.jonjonesbr.audiobookgen.data.SincroniaAudioStore.carregar(getApplication(), mp3)
            com.jonjonesbr.audiobookgen.domain.ancorasDeSincronia(trechos, texto)
        }
        ancorasCache = Triple(mp3, texto, ancoras)
        return ancoras
    }

    private fun iniciarSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            PlayerStateHolder.state.collect { playerState ->
                val texto = textoEfetivo() ?: return@collect

                // O audiobook gerado deste livro (vínculo gravado na conversão); o nome só vale para MP3 sem vínculo.
                val mp3DoLivro = com.jonjonesbr.audiobookgen.data.LinkedBookStore
                    .mp3Vinculado(getApplication(), caminhoArquivoAtual)
                val nomeArquivo = java.io.File(caminhoArquivoAtual).nameWithoutExtension
                val coincide = (mp3DoLivro != null && playerState.caminho == mp3DoLivro) ||
                    playerState.trackName.contains(nomeArquivo, ignoreCase = true) ||
                    nomeArquivo.contains(playerState.trackName, ignoreCase = true)

                if (!playerState.isPlaying || !coincide || playerState.duration == 0) {
                    if (_uiState.value.syncAtivo) {
                        _uiState.update { it.copy(syncAtivo = false, paragrafoSyncAtual = -1) }
                    }
                    return@collect
                }

                val ancoras = if (mp3DoLivro != null && playerState.caminho == mp3DoLivro) {
                    ancorasDoAudiobook(mp3DoLivro, texto)
                } else {
                    emptyList()
                }
                val paragrafoAtual = com.jonjonesbr.audiobookgen.domain.paragrafoNoAudiobook(
                    playerState.currentPosition.toLong(), playerState.duration.toLong(),
                    playerState.capitulos, texto, ancoras,
                ).coerceAtLeast(0)

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

    fun carregarPreferencias(prefs: AppPrefs) {
        _uiState.update {
            it.copy(
                tamanhoFonte = prefs.readerFonte,
                espacamento = prefs.readerEspacamento,
                tema = TemaLeitura.entries[prefs.readerTema],
                fonteSerifada = prefs.readerFonteSerifada,
                margemNivel = prefs.readerMargemNivel,
                alinhamento = com.jonjonesbr.audiobookgen.domain.AlinhamentoTexto.porNome(prefs.readerAlinhamento),
                rolagemHorizontal = prefs.readerRolagemHorizontal
            )
        }
    }

    fun salvarPreferencias(prefs: AppPrefs) {
        val state = _uiState.value
        prefs.salvarAparenciaLeitor(
            fonte = state.tamanhoFonte,
            espacamento = state.espacamento,
            temaOrdinal = state.tema.ordinal,
            fonteSerifada = state.fonteSerifada,
            margemNivel = state.margemNivel,
            rolagemHorizontal = state.rolagemHorizontal
        )
        prefs.readerAlinhamento = state.alinhamento.name
    }

    fun setFonteSerifada(serifada: Boolean) = _uiState.update { it.copy(fonteSerifada = serifada) }

    fun setAlinhamento(alinhamento: com.jonjonesbr.audiobookgen.domain.AlinhamentoTexto) =
        _uiState.update { it.copy(alinhamento = alinhamento) }

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
        // Partindo do original guardado, edições manuais e exclusões feitas depois ficam para trás.
        val descartaEdicoes = _uiState.value.textoOriginalBruto != null

        _uiState.update {
            it.copy(
                limpandoTexto = true
            )
        }

        viewModelScope.launch {
            try {
                val (textoLimpo, totalLinhasRemovidas) = withContext(Dispatchers.IO) {
                    limparComCache(textoAtual, formato)
                }

                _uiState.update {
                    it.copy(
                        // Preserva o original bruto na primeira limpeza para que
                        // "Refazer Limpeza" sempre parta do zero (não do já-processado)
                        textoOriginalBruto = it.textoOriginalBruto ?: textoAtual,
                        textoLimpo = textoLimpo,
                        spansRemovidos = emptyList(),
                        relatorioLinhasRemovidas = totalLinhasRemovidas,
                        temEdicoes = if (descartaEdicoes) false else it.temEdicoes,
                        limpandoTexto = false
                    )
                }
            } catch (e: Exception) {
                Log.e("ReaderViewModel", "Erro na limpeza de texto: ${e.message}", e)
                _uiState.update { it.copy(limpandoTexto = false) }
            }
        }
    }

    /**
     * Limpeza para a narração do texto bruto [textoAtual]. O resultado fica guardado
     * ([com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro]): abrir o livro de novo não chama o Python.
     * Parágrafos vão e voltam em JSON, uma string só pela ponte Java↔Python.
     */
    private suspend fun limparComCache(textoAtual: TextoExtraido, formato: String): Pair<TextoExtraido, Int> {
        val app = getApplication<Application>()
        val idioma = Locale.getDefault().language
        val blacklist = obterBlacklist()
        val chave = com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro.chaveBruto(app, caminhoArquivoAtual)
            ?.let { com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro.chaveLimpo(it, formato, idioma, blacklist) }
        com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro.lerComRemovidas(app, chave)?.let { return it }
        // O texto bruto pode ter vindo do cache, sem o Python ter sido iniciado nesta sessão.
        check(com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase(app).inicializarMotorSeNecessario()) {
            "Motor Python indisponível para a limpeza"
        }

        val entrada = org.json.JSONArray(textoAtual.paragrafos.map { it.texto }).toString()
        val saida = org.json.JSONObject(
            Python.getInstance().getModule("smart_cleaner").callAttr(
                "smart_clean_paragraphs_json", entrada, formato, idioma, org.json.JSONArray(blacklist).toString()
            ).toString()
        )
        val limpos = saida.getJSONArray("paragrafos")
        var offset = 0
        val paragrafosLimpos = (0 until limpos.length()).mapNotNull { indice ->
            val texto = limpos.getString(indice).trim()
            if (texto.isBlank()) return@mapNotNull null
            textoAtual.paragrafos[indice].copy(texto = texto, charOffset = offset).also {
                offset += texto.length + 2
            }
        }
        val textoLimpo = TextoExtraido(
            capitulos = reconstruirCapitulos(textoAtual.capitulos, paragrafosLimpos),
            paragrafos = paragrafosLimpos,
            totalChars = offset
        )
        val removidas = saida.optInt("removidas")
        com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro.gravar(app, chave, textoLimpo, removidas)
        return textoLimpo to removidas
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
                    // O texto pode ter vindo do cache, sem o Python ter sido iniciado nesta sessão (ver limparComCache).
                    val motor = com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase(getApplication<Application>())
                    check(motor.inicializarMotorSeNecessario()) { "Motor Python indisponível para excluir ocorrências" }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ReaderViewModel", "Erro ao excluir ocorrencias: ${e.message}", e)
                // Antes falhava em silêncio: o botão "excluir" parecia não fazer nada.
                android.widget.Toast.makeText(
                    getApplication<Application>(),
                    com.jonjonesbr.audiobookgen.R.string.erro_excluir_ocorrencias,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun obterBlacklist(): List<String> =
        com.jonjonesbr.audiobookgen.data.BlacklistStore(getApplication()).load()

    private fun salvarNaBlacklist(trecho: String) {
        // NonCancellable: sair do leitor logo em seguida limpa o ViewModel e não pode perder a gravação.
        viewModelScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            com.jonjonesbr.audiobookgen.data.BlacklistStore(getApplication()).add(trecho)
        }
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
        buscaJob?.cancel()
        _uiState.update {
            it.copy(
                buscaVisivel = visivel,
                buscaQuery = "",
                buscaResultados = emptyList(),
                buscaIndiceAtual = -1
            )
        }
        if (!visivel) adapter?.let { a ->
            a.buscaResultados = emptyList(); a.buscaIndiceAtual = -1; a.buscaTrechos = emptyMap()
            a.notifyDataSetChanged()
        }
    }

    /**
     * Busca com debounce: a consulta espera [DEBOUNCE_BUSCA_MS] sem digitação antes de filtrar.
     * Ignora maiúsculas, acentos, tipo de aspas e espaços repetidos; o primeiro resultado mostrado
     * é o primeiro a partir de [paragrafoNaTela] (onde o leitor está), não o do começo do livro.
     */
    fun buscarTexto(query: String, paragrafoNaTela: Int = 0) {
        val texto = textoEfetivo() ?: return
        _uiState.update { it.copy(buscaQuery = query) }
        buscaJob?.cancel()
        val consulta = normalizarConsulta(query)
        if (consulta.isEmpty()) {
            _uiState.update { it.copy(buscaResultados = emptyList(), buscaIndiceAtual = -1) }
            adapter?.let {
                it.buscaResultados = emptyList(); it.buscaIndiceAtual = -1; it.buscaTrechos = emptyMap()
                it.notifyDataSetChanged()
            }
            return
        }
        buscaJob = viewModelScope.launch {
            delay(DEBOUNCE_BUSCA_MS)
            val trechos = withContext(Dispatchers.Default) {
                val preparados = textoPreparadoParaBusca(texto.paragrafos)
                buildMap<Int, List<IntRange>> {
                    preparados.forEachIndexed { i, p ->
                        val achados = ocorrenciasBusca(p, consulta)
                        if (achados.isNotEmpty()) put(i, achados)
                    }
                }
            }
            val resultados = trechos.keys.sorted()
            val indiceAtual = indiceInicialBusca(resultados, paragrafoNaTela)
            _uiState.update {
                it.copy(buscaResultados = resultados, buscaIndiceAtual = indiceAtual)
            }
            adapter?.let {
                it.buscaResultados = resultados; it.buscaIndiceAtual = indiceAtual; it.buscaTrechos = trechos
                it.notifyDataSetChanged()
            }
        }
    }

    // Parágrafos já preparados para a busca, guardados para não refazer o trabalho a cada tecla.
    private var buscaPreparadaDe: List<Paragrafo>? = null
    private var buscaPreparada: List<TextoBuscavel> = emptyList()

    @Synchronized
    private fun textoPreparadoParaBusca(paragrafos: List<Paragrafo>): List<TextoBuscavel> {
        if (buscaPreparadaDe !== paragrafos) {
            buscaPreparada = paragrafos.map { prepararParaBusca(it.texto) }
            buscaPreparadaDe = paragrafos
        }
        return buscaPreparada
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
        adapter?.let { a ->
            // Só o parágrafo que deixou de ser o "atual" e o novo mudam de cor: repinta esses dois
            // em vez da lista inteira (notifyDataSetChanged) a cada Anterior/Próximo.
            val posAnterior = posicaoResultadoBusca(resultados, atual)
            val posNova = posicaoResultadoBusca(resultados, novo)
            a.buscaResultados = resultados
            a.buscaIndiceAtual = novo
            listOfNotNull(posAnterior, posNova).distinct().forEach { a.notifyItemChanged(it) }
        }
    }

    private var adapter: ParagrafoAdapter? = null

    // Job da busca em andamento (debounce + filtro); cancelado a cada tecla
    private var buscaJob: Job? = null

    fun registrarAdapter(adapter: ParagrafoAdapter) {
        this.adapter = adapter
    }

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
    }

    companion object {
        /** Espera após a última tecla antes de filtrar os parágrafos (ms). */
        private const val DEBOUNCE_BUSCA_MS = 250L
    }
}
