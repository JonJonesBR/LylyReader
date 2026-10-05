package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.domain.ProcessamentoResult
import com.jonjonesbr.audiobookgen.domain.ExportStatus
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.yield
import java.io.File

/**
 * Worker responsável por executar a conversão livro->MP3 em background.
 *
 * Usa [PythonEngineUseCase] para processar o arquivo via Chaquopy/Python.
 * Reporta progresso via [setProgressAsync] e retorna o resultado em [outputData].
 *
 * O WorkManager garante que a conversão continue mesmo que o app seja minimizado
 * ou o processo seja morto pelo sistema - o trabalho será retomado quando possível.
 */
class ConversionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        // Chaves de InputData
        const val KEY_ARQUIVO    = "arquivo"
        const val KEY_VOZ        = "voz"
        const val KEY_VELOCIDADE = "velocidade"
        const val KEY_ESTILO     = "estilo"
        const val KEY_SAIDA      = "saida"
        const val KEY_MOTOR      = "motor"
        const val KEY_PAUSA_MS   = "pausa_ms"
        const val KEY_RUN_TOKEN  = "run_token"
        /**
         * Token na ENTRADA. Em trabalhos encadeados (fila) o WorkManager mescla a saída do anterior na entrada do próximo, e
         * [KEY_RUN_TOKEN] também é chave de saída: o token do anterior sobrescreveria o do próximo. Esta chave só existe na entrada.
         */
        const val KEY_RUN_TOKEN_ENTRADA = "run_token_entrada"
        const val KEY_LIVRO_ORIGEM = "livro_origem"
        const val KEY_SPEAKER_MAP_PATH = "speaker_map_path"
        /** Tom/agudos do perfil de áudio do livro (opcionais); ausentes = ajustes globais. */
        const val KEY_TOM_MEIOS_TONS = "tom_meios_tons"
        const val KEY_AGUDOS_DB = "agudos_db"
        /** Item da fila de processamento atendido por esta execução (ausente = conversão avulsa). */
        const val KEY_FILA_ITEM_ID = "fila_item_id"

        // Chaves de progresso (setProgressAsync)
        const val KEY_PROGRESS_PCT = "progress_pct"
        const val KEY_PROGRESS_MSG = "progress_msg"

        // Chaves de OutputData (sucesso)
        const val KEY_RESULT_CAMINHO    = "result_caminho"
        const val KEY_RESULT_DURACAO    = "result_duracao"
        const val KEY_RESULT_TAMANHO    = "result_tamanho"
        const val KEY_RESULT_CAPITULOS  = "result_capitulos"
        const val KEY_RESULT_LIVRO_ORIGEM = "result_livro_origem"
        /** O próprio worker já guardou marcas de capítulo, vínculo com o livro e a cópia para a pasta de saída. */
        const val KEY_RESULT_FINALIZADO = "result_finalizado"
        const val KEY_RESULT_EXPORT_URI = "result_export_uri"
        const val KEY_RESULT_EXPORT_PASTA = "result_export_pasta"
        const val KEY_RESULT_EXPORT_ERRO = "result_export_erro"

        // Chave de OutputData (erro)
        const val KEY_ERROR_MSG = "error_msg"

        /** Nome único do trabalho - garante apenas uma conversão ativa por vez. */
        const val WORK_NAME = "lyly_conversion"

        /** Pausa padrão entre blocos (ms) — default do AppPrefs.pausaMs. */
        private const val PAUSA_MS_PADRAO = 600
        private const val SEM_VALOR = Int.MIN_VALUE
    }

    private fun tokenDaExecucao(): String? = inputData.getString(KEY_RUN_TOKEN_ENTRADA) ?: inputData.getString(KEY_RUN_TOKEN)

    override suspend fun doWork(): Result {
        val arquivo    = inputData.getString(KEY_ARQUIVO)
        val voz        = inputData.getString(KEY_VOZ)
        val velocidade = inputData.getString(KEY_VELOCIDADE)
        val estilo     = inputData.getString(KEY_ESTILO)
        val saida      = inputData.getString(KEY_SAIDA)
        val obrigatorios = listOf(arquivo, voz, velocidade, estilo, saida)
        if (obrigatorios.any { it == null }) {
            return Result.failure()
        }
        val filaItemId = inputData.getString(KEY_FILA_ITEM_ID)
        val runToken = tokenDaExecucao()
        return try {
            executarConversao(arquivo!!, voz!!, velocidade!!, estilo!!, saida!!)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelada pelo usuário: o item fica com erro e a fila para (os demais seguem pendentes).
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                FilaDeConversao.aoTerminarExecucao(
                    applicationContext, filaItemId, runToken, null,
                    applicationContext.getString(com.jonjonesbr.audiobookgen.R.string.status_conversion_canceled), avancar = false
                )
            }
            throw e
        } catch (e: Exception) {
            val mensagem = e.message ?: e.javaClass.simpleName
            FilaDeConversao.aoTerminarExecucao(applicationContext, filaItemId, runToken, null, mensagem)
            Result.failure(workDataOf(KEY_ERROR_MSG to mensagem, KEY_RUN_TOKEN to runToken))
        }
    }

    private suspend fun executarConversao(
        arquivo: String,
        voz: String,
        velocidade: String,
        estilo: String,
        saida: String
    ): Result {
        val motor      = inputData.getString(KEY_MOTOR)      ?: "edge"
        val chaveGemini    = SecurePreferences.getGeminiKeys(applicationContext)
        val pausaMs        = inputData.getInt(KEY_PAUSA_MS, PAUSA_MS_PADRAO)
        val runToken       = tokenDaExecucao()
        val livroOrigem    = inputData.getString(KEY_LIVRO_ORIGEM)

        // Se o trabalho já foi cancelado, yield() lança CancellationException
        yield()

        val notif = ConversionNotificationManager(applicationContext)
        val engine = PythonEngineUseCase(applicationContext)
        engine.inicializarMotorSeNecessario()
        val tomDoLivro = inputData.getInt(KEY_TOM_MEIOS_TONS, SEM_VALOR).takeIf { it != SEM_VALOR }
        val agudosDoLivro = inputData.getInt(KEY_AGUDOS_DB, SEM_VALOR).takeIf { it != SEM_VALOR }
        engine.sincronizarConfiguracoes(motor, chaveGemini, pausaMs, tomDoLivro, agudosDoLivro)

        val onProgress: (Int, String) -> Unit = { pct, msg ->
                notif.mostrar(pct, msg)
                setProgressAsync(workDataOf(
                    KEY_PROGRESS_PCT to pct,
                    KEY_PROGRESS_MSG to msg,
                    KEY_RUN_TOKEN to runToken
                ))
            }
        val speakerMapPath = inputData.getString(KEY_SPEAKER_MAP_PATH)
            ?.takeIf { File(it).isFile }
        val textoComMapa = if (speakerMapPath != null && File(arquivo).extension.equals("txt", true)) {
            runCatching { File(arquivo).readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        val result = if (speakerMapPath != null && textoComMapa != null) {
            engine.processarTexto(
                texto = textoComMapa,
                titulo = File(arquivo).nameWithoutExtension,
                voz = voz,
                velocidade = velocidade,
                estilo = estilo,
                saida = saida,
                motor = motor,
                chaveGemini = chaveGemini,
                speakerMapPath = speakerMapPath,
                onProgress = onProgress
            )
        } else {
            engine.processarArquivo(
                arquivo = arquivo,
                voz = voz,
                velocidade = velocidade,
                estilo = estilo,
                saida = saida,
                motor = motor,
                chaveGemini = chaveGemini,
                onProgress = onProgress
            )
        }

        return when (result) {
            is ProcessamentoResult.Success -> {
                // Finaliza aqui, e não na tela: se o usuário sair da tela de acompanhamento, o audiobook ainda é salvo na
                // pasta de saída (senão ele só apareceria na lista de Audiobooks depois de reabrir a tela).
                val capitulosJson = ChapterMarksStore.paraJson(result.capitulos)
                ChapterMarksStore.salvarJson(applicationContext, result.caminho, capitulosJson)
                livroOrigem?.let { com.jonjonesbr.audiobookgen.data.LinkedBookStore.vincular(applicationContext, it, result.caminho) }
                val pastaDestino = com.jonjonesbr.audiobookgen.data.AppPrefs(applicationContext).pastaDestino
                    ?.let { android.net.Uri.parse(it) }
                val exportacao = com.jonjonesbr.audiobookgen.domain.DocumentExportUseCase(applicationContext)
                    .exportar(File(result.caminho), pastaDestino)
                notif.mostrarConcluida(result.caminho, File(result.caminho).nameWithoutExtension)
                FilaDeConversao.aoTerminarExecucao(
                    applicationContext, inputData.getString(KEY_FILA_ITEM_ID), runToken, result.caminho, null
                )
                Result.success(
                    workDataOf(
                        KEY_RESULT_CAMINHO to result.caminho,
                        KEY_RESULT_DURACAO to result.duracao,
                        KEY_RESULT_TAMANHO to result.tamanhoMb,
                        KEY_RESULT_CAPITULOS to capitulosJson,
                        KEY_RESULT_LIVRO_ORIGEM to livroOrigem,
                        KEY_RESULT_FINALIZADO to true,
                        KEY_RESULT_EXPORT_URI to exportacao.getOrNull()?.destUri?.toString(),
                        KEY_RESULT_EXPORT_PASTA to exportacao.getOrNull()?.nomePasta,
                        KEY_RESULT_EXPORT_ERRO to exportacao.exceptionOrNull()?.let { it.message ?: "Erro ao exportar" },
                        KEY_RUN_TOKEN to runToken
                    )
                )
            }
            is ProcessamentoResult.Error -> {
                notif.cancelar()
                FilaDeConversao.aoTerminarExecucao(
                    applicationContext, inputData.getString(KEY_FILA_ITEM_ID), runToken, null, result.mensagem
                )
                Result.failure(
                    workDataOf(
                        KEY_ERROR_MSG to result.mensagem,
                        KEY_RUN_TOKEN to runToken
                    )
                )
            }
        }
    }
}
