package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.ExtractionResult
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.domain.TextoNarravelDoLivro
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.SynthesisWakeLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Livro pronto para narrar: caminho, título para a notificação e texto limpo (o mesmo da tela). */
internal data class LivroDaFila(val caminho: String, val titulo: String, val texto: TextoExtraido)

/**
 * Fila "Ler tudo" da leitura guiada: prepara o próximo livro da sequência e coordena a troca sem encerrar a
 * sessão (mesmo token, serviço e soneca). Só usado na main thread.
 *
 * Saiu do [GuidedPlayerManager]; ele continua dono da reprodução e entrega aqui só lambdas ([Callbacks]).
 */
internal class FilaDaLeituraGuiada(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val appPrefs: AppPrefs,
    private val pythonUseCase: PythonEngineUseCase,
    private val callbacks: Callbacks
) {
    /** Tudo que a fila precisa acionar no gerenciador da leitura. Só lambdas, avaliadas a cada uso. */
    class Callbacks(
        val caminhoAtual: () -> String,
        val interromperParaTroca: () -> Unit,
        val aguardarRetomada: suspend () -> Unit,
        val aplicarLivro: (LivroDaFila) -> Unit,
        val encerrarComoConcluido: () -> Unit
    )

    /** Próximos livros da fila, sem o atual. Vazia = leitura avulsa. */
    var filaSequencia: List<String> = emptyList()

    // Livros que a fila já deixou para trás nesta sessão: a tela velha usa para não voltar a eles.
    private val _livrosDeixadosNaFila = mutableSetOf<String>()
    val livrosDeixadosNaFila: Set<String> get() = _livrosDeixadosNaFila

    private var trocaDeLivroJob: Job? = null

    /** Preparando o próximo livro da fila (a sessão segue viva, só carregando). */
    val trocandoDeLivro: Boolean get() = trocaDeLivroJob?.isActive == true

    /** Injetável nos testes. */
    var prepararLivroDaFila: suspend (String) -> LivroDaFila? = { prepararLivroDaFilaPadrao(it) }

    fun cancelarTroca() {
        trocaDeLivroJob?.cancel()
        trocaDeLivroJob = null
    }

    /**
     * Fim do último parágrafo com fila: prepara o próximo livro sem parar a sessão. O serviço, a soneca e o bridge
     * seguem com o mesmo token, e a barra fica carregando no último parágrafo. Quando o livro abre, aplica o
     * novo texto e toca. Se nenhum abrir, encerra como livro concluído.
     */
    @Suppress("TooGenericExceptionCaught")
    fun iniciarTroca() {
        callbacks.interromperParaTroca()
        // Job novo no escopo: next() costuma vir de dentro do playJob, que interromperParaTroca() acabou de cancelar.
        trocaDeLivroJob = coroutineScope.launch {
            while (filaSequencia.isNotEmpty()) {
                val caminho = filaSequencia.first()
                val livro = carregarLivroComWakeLock(caminho)
                if (livro == null || livro.texto.paragrafos.isEmpty()) {
                    // Erro ao abrir ou livro sem texto: pula para o seguinte, sem parar a fila.
                    CrashLogWriter.log(null, "leituraGuiada.filaLerTudo", "Livro pulado: $caminho")
                    filaSequencia = filaSequencia.drop(1)
                    continue
                }
                try {
                    // Pronúncia do livro novo vai ao motor só agora. Se a troca for cancelada depois disso
                    // (toque em parágrafo, "anterior", fechar), o livro atual volta a valer: senão ele leria
                    // com o dicionário dos outros.
                    pythonUseCase.sincronizarConfiguracoes(
                        appPrefs.motorTts, SecurePreferences.getGeminiKeys(context), appPrefs.pausaMs,
                        caminhoDoLivro = livro.caminho
                    )
                    // Pausa, soneca ou ligação durante a preparação seguram o próximo livro.
                    callbacks.aguardarRetomada()
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        pythonUseCase.sincronizarDicionario(callbacks.caminhoAtual())
                    }
                    throw e
                }
                // Só sai da fila agora: se a troca for cancelada antes (fechar a guiada, tocar em outro
                // parágrafo), o livro continua na fila. Nada suspende daqui até aplicarLivro().
                filaSequencia = filaSequencia.drop(1)
                trocaDeLivroJob = null
                val caminhoAnterior = callbacks.caminhoAtual()
                if (caminhoAnterior.isNotEmpty()) _livrosDeixadosNaFila.add(caminhoAnterior)
                try {
                    callbacks.aplicarLivro(livro)
                } catch (e: Exception) {
                    CrashLogWriter.log(e, "leituraGuiada.filaLerTudo", "troca para caminho=$caminho")
                    callbacks.encerrarComoConcluido()
                }
                return@launch
            }
            trocaDeLivroJob = null
            callbacks.encerrarComoConcluido()
        }
    }

    /** A CPU não pode dormir durante a extração e a limpeza do texto; a trava só cobre essa etapa. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun carregarLivroComWakeLock(caminho: String): LivroDaFila? {
        SynthesisWakeLock.acquire(context)
        return try {
            prepararLivroDaFila(caminho)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogWriter.log(e, "leituraGuiada.filaLerTudo", "caminho=$caminho")
            null
        } finally {
            SynthesisWakeLock.release()
        }
    }

    /** Carrega o próximo livro da fila com o mesmo texto que a tela mostra (cache e regras de [TextoNarravelDoLivro]). */
    private suspend fun prepararLivroDaFilaPadrao(caminho: String): LivroDaFila? {
        val texto = when (val resultado = TextoNarravelDoLivro(context).carregar(caminho)) {
            is ExtractionResult.Success -> resultado.texto
            is ExtractionResult.Error -> {
                CrashLogWriter.log(null, "leituraGuiada.filaLerTudo", "Erro ao abrir $caminho: ${resultado.mensagem}")
                return null
            }
        }
        // Título da biblioteca (com acentos), como faz a ReaderActivity ao abrir o livro.
        val titulo = withContext(Dispatchers.IO) { BibliotecaStore.tituloPorCaminho(context, caminho) }
            ?: File(caminho).nameWithoutExtension
        return LivroDaFila(caminho, titulo, texto)
    }
}
