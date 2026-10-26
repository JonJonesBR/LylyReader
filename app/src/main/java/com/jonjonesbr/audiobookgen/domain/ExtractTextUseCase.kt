package com.jonjonesbr.audiobookgen.domain

import android.util.Log
import com.jonjonesbr.audiobookgen.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ── Modelos de domínio ────────────────────────────────────────────────────────

data class Capitulo(
    val titulo: String,
    val indiceParagrafoInicio: Int,
    val indiceParagrafoFim: Int
)

data class Paragrafo(
    val texto: String,
    val charOffset: Int,
    val indiceCapitulo: Int
)

data class TextoExtraido(
    val capitulos: List<Capitulo>,
    val paragrafos: List<Paragrafo>,
    val totalChars: Int
)

sealed class ExtractionResult {
    data class Success(val texto: TextoExtraido) : ExtractionResult()
    data class Error(val mensagem: String) : ExtractionResult()
}

// OLED sempre por último: o ordinal é persistido em SharedPreferences (reader_tema) — inserir
// no meio quebraria o tema salvo de quem já usa o app.
enum class TemaLeitura { CLARO, ESCURO, SEPIA, OLED }

data class ReaderUiState(
    val carregando: Boolean = false,
    val erro: String? = null,
    val texto: TextoExtraido? = null,
    val capituloAtivo: Int = 0,
    val paragrafoSyncAtual: Int = -1,
    val selecaoInicio: Int? = null,
    val selecaoFim: Int? = null,
    val tamanhoFonte: Float = 16f,
    val espacamento: Float = 1.5f,
    val tema: TemaLeitura = TemaLeitura.CLARO,
    val progressPct: Float = 0f,
    val syncAtivo: Boolean = false,
    val textoOriginalBruto: TextoExtraido? = null,
    val textoLimpo: TextoExtraido? = null,
    val spansRemovidos: List<Pair<Int, Int>> = emptyList(),
    val relatorioLinhasRemovidas: Int = 0,
    val limpandoTexto: Boolean = false,
    val modoEdicao: Boolean = false,
    val mostrandoAlteracoes: Boolean = false,
    val temEdicoes: Boolean = false,
    // T4.1 — Busca
    val buscaQuery: String = "",
    val buscaResultados: List<Int> = emptyList(),
    val buscaIndiceAtual: Int = -1,
    val buscaVisivel: Boolean = false,
    // T3.2 — Tipografia
    val fonteSerifada: Boolean = false,
    val margemNivel: Int = MARGEM_NIVEL_PADRAO,
    val alinhamento: AlinhamentoTexto = AlinhamentoTexto.JUSTIFICADO,
    // Rolagem horizontal (paginada) como alternativa ao scroll vertical contínuo.
    val rolagemHorizontal: Boolean = false
)

/** Índice do nível de margem horizontal padrão (0=estreita, 1=média, 2=larga). */
const val MARGEM_NIVEL_PADRAO = 1

// ── Use Case ─────────────────────────────────────────────────────────────────

private const val TAG = "ExtractTextUseCase"
private const val MAX_BYTES = 50L * 1024 * 1024 // 50 MB

class ExtractTextUseCase(private val context: android.content.Context) {

    /**
     * Um livro é extraído por vez: se o preparo em segundo plano ([PreparoDoLeitor]) ainda está extraindo
     * quando o leitor abre, o leitor espera e pega o resultado do cache, em vez de extrair tudo de novo.
     */
    suspend fun invoke(caminho: String): ExtractionResult =
        travas.getOrPut(caminho) { Mutex() }.withLock { extrair(caminho) }

    private suspend fun extrair(caminho: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            val arquivo = java.io.File(caminho)
            if (arquivo.length() > MAX_BYTES) {
                return@withContext ExtractionResult.Error(
                    context.getString(R.string.extracao_arquivo_grande)
                )
            }

            // Já aberto antes: o texto guardado dispensa o Python (abertura quase instantânea).
            val chave = com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro.chaveBruto(context, caminho)
            com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro.ler(context, chave)?.let {
                return@withContext ExtractionResult.Success(it)
            }

            // Python é iniciado sob demanda (não mais eager no app startup) — a leitura
            // é o próprio motivo de precisar dele, então garante aqui antes de qualquer uso.
            if (!PythonEngineUseCase(context).inicializarMotorSeNecessario()) {
                return@withContext ExtractionResult.Error(
                    context.getString(R.string.extracao_motor_indisponivel)
                )
            }

            // Uma string JSON atravessa a ponte Java↔Python de uma vez (antes, ~6 chamadas por parágrafo).
            val json = com.chaquo.python.Python.getInstance().getModule("audiobook_android")
                .callAttr("extrair_texto_estruturado_json", caminho).toString()
            val texto = TextoExtraidoJson.daExtracao(json, context.getString(R.string.capitulo_sem_titulo))
                .getOrElse { erro ->
                    Log.w(TAG, "Extração falhou: ${erro.message.orEmpty().take(500)}")
                    val mensagem = erro.message.orEmpty()
                    val amigavel = if (mensagem.isBlank()) {
                        context.getString(R.string.extracao_erro_abrir)
                    } else {
                        traduzirErro(mensagem)
                    }
                    return@withContext ExtractionResult.Error(amigavel)
                }
            if (texto.paragrafos.isEmpty()) {
                return@withContext ExtractionResult.Error(context.getString(R.string.extracao_sem_texto))
            }
            com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro.gravar(context, chave, texto)
            ExtractionResult.Success(texto)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Sair da tela durante a extração cancela a coroutine: não é erro, não mostra mensagem.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Erro na extração: ${e.message}", e)
            ExtractionResult.Error(context.getString(R.string.extracao_erro_abrir))
        }
    }

    private companion object {
        val travas = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    }

    private fun traduzirErro(erro: String): String = when {
        "escaneado" in erro.lowercase() -> context.getString(R.string.extracao_pdf_escaneado)
        "senha" in erro.lowercase() || "drm" in erro.lowercase() || "password" in erro.lowercase() ->
            context.getString(R.string.extracao_protegido_senha)
        "encoding" in erro.lowercase() || "codec" in erro.lowercase() ->
            context.getString(R.string.extracao_formato_nao_reconhecido)
        "vazio" in erro.lowercase() || "empty" in erro.lowercase() ->
            context.getString(R.string.extracao_sem_texto)
        else -> context.getString(R.string.extracao_erro_abrir)
    }
}
