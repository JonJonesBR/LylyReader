package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.jonjonesbr.audiobookgen.data.BlacklistStore
import com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Texto pronto para a narração de um livro: extrai e limpa, com o mesmo cache e as mesmas regras para a tela do
 * leitor ([com.jonjonesbr.audiobookgen.ui.ReaderViewModel]) e para a fila "Ler tudo" (GuidedPlayerManager). Os
 * índices dos parágrafos precisam bater nos dois lados (posição salva, marcador, áudio), por isso a limpeza não
 * pode ser copiada para outro lugar.
 */
class TextoNarravelDoLivro(private val context: Context) {

    /** Extrai (ExtractTextUseCase) e limpa. A limpeza falhou: devolve o bruto, como o ReaderViewModel. */
    suspend fun carregar(caminho: String): ExtractionResult {
        val extraido = ExtractTextUseCase(context).invoke(caminho)
        if (extraido !is ExtractionResult.Success) return extraido
        val formato = File(caminho).extension.lowercase()
        return try {
            val (limpo, _) = limpar(caminho, extraido.texto, formato)
            ExtractionResult.Success(limpo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Limpeza do texto falhou; usando o texto bruto", e)
            extraido
        }
    }

    /** Corpo de ReaderViewModel.limparComCache movido sem mudanças; roda em Dispatchers.IO. */
    suspend fun limpar(caminho: String, bruto: TextoExtraido, formato: String): Pair<TextoExtraido, Int> =
        withContext(Dispatchers.IO) {
            /*
             * O resultado fica guardado ([CacheDeTextoDoLivro]): abrir o livro de novo não chama o Python.
             * Parágrafos vão e voltam em JSON, uma string só pela ponte Java↔Python.
             */
            val idioma = Locale.getDefault().language
            val blacklist = BlacklistStore(context).load()
            val chave = CacheDeTextoDoLivro.chaveBruto(context, caminho)
                ?.let { CacheDeTextoDoLivro.chaveLimpo(it, formato, idioma, blacklist) }
            val cacheado = CacheDeTextoDoLivro.lerComRemovidas(context, chave)
            if (cacheado != null) return@withContext cacheado
            // O texto bruto pode ter vindo do cache, sem o Python ter sido iniciado nesta sessão.
            check(PythonEngineUseCase(context).inicializarMotorSeNecessario()) {
                "Motor Python indisponível para a limpeza"
            }

            val entrada = org.json.JSONArray(bruto.paragrafos.map { it.texto }).toString()
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
                bruto.paragrafos[indice].copy(texto = texto, charOffset = offset).also {
                    offset += texto.length + 2
                }
            }
            val textoLimpo = TextoExtraido(
                capitulos = reconstruirCapitulos(bruto.capitulos, paragrafosLimpos),
                paragrafos = paragrafosLimpos,
                totalChars = offset
            )
            val removidas = saida.optInt("removidas")
            CacheDeTextoDoLivro.gravar(context, chave, textoLimpo, removidas)
            textoLimpo to removidas
        }

    private companion object {
        const val TAG = "TextoNarravelDoLivro"
    }
}

/** Movido do ReaderViewModel (ele continua usando em excluirOcorrencias). */
internal fun reconstruirCapitulos(capitulos: List<Capitulo>, paragrafos: List<Paragrafo>): List<Capitulo> =
    capitulos.mapIndexedNotNull { indice, capitulo ->
        val indices = paragrafos.mapIndexedNotNull { novoIndice, paragrafo ->
            novoIndice.takeIf { paragrafo.indiceCapitulo == indice }
        }
        if (indices.isEmpty()) null else capitulo.copy(
            indiceParagrafoInicio = indices.first(),
            indiceParagrafoFim = indices.last()
        )
    }
