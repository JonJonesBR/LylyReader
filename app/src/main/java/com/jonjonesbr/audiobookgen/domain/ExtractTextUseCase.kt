package com.jonjonesbr.audiobookgen.domain

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
    // Rolagem horizontal (paginada) como alternativa ao scroll vertical contínuo.
    val rolagemHorizontal: Boolean = false
)

/** Índice do nível de margem horizontal padrão (0=estreita, 1=média, 2=larga). */
const val MARGEM_NIVEL_PADRAO = 1

// ── Use Case ─────────────────────────────────────────────────────────────────

private const val TAG = "ExtractTextUseCase"
private const val MAX_BYTES = 50L * 1024 * 1024 // 50 MB

class ExtractTextUseCase(private val context: android.content.Context) {

    suspend fun invoke(caminho: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            val arquivo = java.io.File(caminho)
            if (arquivo.length() > MAX_BYTES) {
                return@withContext ExtractionResult.Error(
                    "Arquivo muito grande para visualização (máx. 50 MB)."
                )
            }

            // Python é iniciado sob demanda (não mais eager no app startup) — a leitura
            // é o próprio motivo de precisar dele, então garante aqui antes de qualquer uso.
            if (!PythonEngineUseCase(context).inicializarMotorSeNecessario()) {
                return@withContext ExtractionResult.Error(
                    "Não foi possível iniciar o motor de leitura. Tente novamente."
                )
            }

            val py = com.chaquo.python.Python.getInstance()
            val mod = py.getModule("audiobook_android")
            val resultado = mod.callAttr("extrair_texto_estruturado", caminho).asMap()

            val ok = resultado[py.builtins.callAttr("str", "ok")]?.toBoolean() ?: false
            if (!ok) {
                val erro = resultado[py.builtins.callAttr("str", "erro")]
                    ?.toString() ?: "Erro ao abrir o arquivo. Tente converter diretamente."
                return@withContext ExtractionResult.Error(traduzirErro(erro))
            }

            val capitulosPy = resultado[py.builtins.callAttr("str", "capitulos")]
                ?.asList() ?: emptyList()
            val paragrafosPy = resultado[py.builtins.callAttr("str", "paragrafos")]
                ?.asList() ?: emptyList()
            val totalChars = resultado[py.builtins.callAttr("str", "total_chars")]
                ?.toInt() ?: 0

            if (paragrafosPy.isEmpty()) {
                return@withContext ExtractionResult.Error("Nenhum texto encontrado neste arquivo.")
            }

            val capitulos = capitulosPy.map { cap ->
                val m = cap.asMap()
                Capitulo(
                    titulo = m[py.builtins.callAttr("str", "titulo")]?.toString() ?: "Sem título",
                    indiceParagrafoInicio = m[py.builtins.callAttr("str", "inicio")]?.toInt() ?: 0,
                    indiceParagrafoFim = m[py.builtins.callAttr("str", "fim")]?.toInt() ?: 0
                )
            }

            val paragrafos = paragrafosPy.map { par ->
                val m = par.asMap()
                Paragrafo(
                    texto = m[py.builtins.callAttr("str", "texto")]?.toString() ?: "",
                    charOffset = m[py.builtins.callAttr("str", "char_offset")]?.toInt() ?: 0,
                    indiceCapitulo = m[py.builtins.callAttr("str", "capitulo_idx")]?.toInt() ?: 0
                )
            }

            ExtractionResult.Success(
                TextoExtraido(
                    capitulos = capitulos,
                    paragrafos = paragrafos,
                    totalChars = totalChars
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro na extração: ${e.message}", e)
            ExtractionResult.Error("Erro ao abrir o arquivo. Tente converter diretamente.")
        }
    }

    private fun traduzirErro(erro: String): String = when {
        "senha" in erro.lowercase() || "drm" in erro.lowercase() || "password" in erro.lowercase() ->
            "Arquivo protegido por senha não pode ser lido."
        "encoding" in erro.lowercase() || "codec" in erro.lowercase() ->
            "Formato de texto não reconhecido. Tente converter diretamente."
        "vazio" in erro.lowercase() || "empty" in erro.lowercase() ->
            "Nenhum texto encontrado neste arquivo."
        else -> "Erro ao abrir o arquivo. Tente converter diretamente."
    }
}
