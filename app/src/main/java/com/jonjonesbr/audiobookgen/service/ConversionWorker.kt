package com.jonjonesbr.audiobookgen.service

import com.jonjonesbr.audiobookgen.domain.ProcessamentoResult
import com.jonjonesbr.audiobookgen.domain.comprimirWavParaAac
import com.jonjonesbr.audiobookgen.domain.ehWavArquivo
import com.jonjonesbr.audiobookgen.domain.temEspacoParaCompressao
import com.jonjonesbr.audiobookgen.domain.ExportStatus
import com.jonjonesbr.audiobookgen.domain.PythonEngineUseCase
import com.jonjonesbr.audiobookgen.data.ConversaoPausadaStore
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.DestinoDaParada
import com.jonjonesbr.audiobookgen.domain.LimiteDeProgresso
import com.jonjonesbr.audiobookgen.domain.MotivoParada
import com.jonjonesbr.audiobookgen.domain.PoliticaPrimeiroPlano
import com.jonjonesbr.audiobookgen.domain.RegraDaParada
import com.jonjonesbr.audiobookgen.domain.RenovacaoDeTrava
import com.jonjonesbr.audiobookgen.domain.TipoServicoConversao
import com.jonjonesbr.audiobookgen.util.ConversionWakeLock
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.VisibilidadeDoApp
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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
        /** Caminho do livro dono do texto, para somar o dicionário de pronúncia dele ao global (ausente = usa [KEY_LIVRO_ORIGEM]). */
        const val KEY_LIVRO_DICIONARIO = "livro_dicionario"
        const val KEY_SPEAKER_MAP_PATH = "speaker_map_path"
        /** Tom/agudos do perfil de áudio do livro (opcionais); ausentes = ajustes globais. */
        const val KEY_TOM_MEIOS_TONS = "tom_meios_tons"
        const val KEY_AGUDOS_DB = "agudos_db"
        /** Item da fila de processamento atendido por esta execução (ausente = conversão avulsa). */
        const val KEY_FILA_ITEM_ID = "fila_item_id"

        // Chaves de progresso (setProgressAsync)
        const val KEY_PROGRESS_PCT = "progress_pct"
        const val KEY_PROGRESS_MSG = "progress_msg"
        /** Nome do audiobook em geração (do arquivo de saída), para o cartão "Gerando agora" das abas. */
        const val KEY_PROGRESS_NOME = "progress_nome"

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
        /** Parada pedida pelo app (pausa): não é erro nem conclusão; a conversão fica salva em [ConversaoPausadaStore]. */
        const val KEY_RESULT_PAUSADA = "result_pausada"

        // Chave de OutputData (erro)
        const val KEY_ERROR_MSG = "error_msg"

        /** Nome único do trabalho - garante apenas uma conversão ativa por vez. */
        const val WORK_NAME = "lyly_conversion"

        /** Pausa padrão entre blocos (ms) — default do AppPrefs.pausaMs. */
        private const val PAUSA_MS_PADRAO = 600
        private const val SEM_VALOR = Int.MIN_VALUE
        /** Percentual mostrado durante a compressão do WAV (não regride: a conversão já está em 99). */
        private const val PCT_COMPRIMINDO = 99
        /** Intervalo da renovação da trava de energia (cada renovação vale 30 min). */
        private const val RENOVAR_TRAVA_A_CADA_MS = 5 * 60 * 1000L
    }

    private fun tokenDaExecucao(): String? = inputData.getString(KEY_RUN_TOKEN_ENTRADA) ?: inputData.getString(KEY_RUN_TOKEN)

    override suspend fun doWork(): Result {
        ControleDaConversao.limpar()
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
            FilaDeConversao.aoIniciarExecucao(applicationContext, filaItemId, runToken)
            kotlinx.coroutines.coroutineScope {
                // Renova a trava de energia pelo relógio, não só quando o Python manda progresso: uma etapa longa sem
                // callback (junção, compressão) não deixa a trava vencer no meio da conversão.
                val renovador = launch {
                    while (true) {
                        kotlinx.coroutines.delay(RENOVAR_TRAVA_A_CADA_MS)
                        ConversionWakeLock.renovar()
                    }
                }
                try {
                    executarConversao(arquivo!!, voz!!, velocidade!!, estilo!!, saida!!)
                } finally {
                    renovador.cancel()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Parada pelo app/usuário (cancelar) ou pelo sistema (cota, limite de runtime). Só o cancelamento do app fecha o
            // item; nos demais casos o item continua PROCESSANDO e o WorkManager reexecuta o trabalho.
            // NOT_STOPPED: o WorkManager pode cancelar antes de gravar o motivo; tratar como cancelamento pelo app (comportamento anterior).
            // stopReason só existe a partir do Android 12; antes disso canceladoPeloApp ignora o valor.
            val motivoDaParada = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else WorkInfo.STOP_REASON_NOT_STOPPED
            val canceladoPeloApp = RegraDaParada.canceladoPeloApp(Build.VERSION.SDK_INT, motivoDaParada)
            val destino = RegraDaParada.destino(ControleDaConversao.pedido, canceladoPeloApp)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                when (destino) {
                    DestinoDaParada.CANCELADA -> FilaDeConversao.aoTerminarExecucao(
                        applicationContext, filaItemId, runToken, null,
                        applicationContext.getString(com.jonjonesbr.audiobookgen.R.string.status_conversion_canceled), avancar = false
                    )
                    DestinoDaParada.PAUSADA, DestinoDaParada.INTERROMPIDA ->
                        PythonEngineUseCase(applicationContext).cancelar()
                }
            }
            throw e
        } catch (e: Exception) {
            val mensagem = e.message ?: e.javaClass.simpleName
            FilaDeConversao.aoTerminarExecucao(applicationContext, filaItemId, runToken, null, mensagem)
            Result.failure(workDataOf(KEY_ERROR_MSG to mensagem, KEY_RUN_TOKEN to runToken))
        } catch (t: Throwable) {
            // Error (ex.: OutOfMemoryError com livro muito grande): antes o item ficava em PROCESSANDO.
            CrashLogWriter.log(t, "conversionWorker", "Erro grave na conversão")
            val mensagem = applicationContext.getString(com.jonjonesbr.audiobookgen.R.string.erro_conversao_memoria)
            FilaDeConversao.aoTerminarExecucao(applicationContext, filaItemId, runToken, null, mensagem)
            Result.failure(workDataOf(KEY_ERROR_MSG to mensagem, KEY_RUN_TOKEN to runToken))
        } finally {
            ConversionWakeLock.release()
            // O pedido não pode sobreviver ao worker: a próxima execução começa sem pausa/cancelamento pendente.
            ControleDaConversao.limpar()
            // Fim da conversão (sucesso, erro, pausa ou cancelamento): o Python volta a ter só o dicionário global.
            if (PythonEngineUseCase.conversaoAtiva()) {
                PythonEngineUseCase.marcarConversaoAtiva(false)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    PythonEngineUseCase(applicationContext).sincronizarDicionario(null)
                }
            }
        }
    }

    /**
     * Entra em primeiro plano quando a [PoliticaPrimeiroPlano] permite (app visível, fora da otimização de bateria
     * ou Android abaixo de 12). Falha do sistema ao iniciar não derruba a conversão: segue sem primeiro plano.
     * Devolve true só se o primeiro plano foi de fato iniciado.
     */
    private suspend fun iniciarPrimeiroPlano(notif: ConversionNotificationManager): Boolean {
        try {
            val semOtimizacaoBateria = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(applicationContext.packageName)
            if (!PoliticaPrimeiroPlano.podeIniciar(Build.VERSION.SDK_INT, VisibilidadeDoApp.appVisivel, semOtimizacaoBateria)) {
                return false
            }
            setForeground(infoPrimeiroPlano(notif))
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogWriter.log(e, "ConversionWorker", "setForeground")
            return false
        }
    }

    /**
     * Notificação e tipo de serviço do primeiro plano. O tipo segue o SDK pela [PoliticaPrimeiroPlano]:
     * as constantes de ServiceInfo só são usadas nos ramos de SDK correspondentes (29+ e 35+).
     */
    private fun infoPrimeiroPlano(notif: ConversionNotificationManager): ForegroundInfo {
        val notificacao = notif.construir(0, "")
        return when (PoliticaPrimeiroPlano.tipoDeServico(Build.VERSION.SDK_INT)) {
            TipoServicoConversao.MEDIA_PROCESSING -> ForegroundInfo(
                ConversionNotificationManager.NOTIF_ID, notificacao,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            TipoServicoConversao.DATA_SYNC -> ForegroundInfo(
                ConversionNotificationManager.NOTIF_ID, notificacao,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            TipoServicoConversao.NENHUM -> ForegroundInfo(ConversionNotificationManager.NOTIF_ID, notificacao)
        }
    }

    /** Exigido pelo WorkManager para trabalho em primeiro plano; devolve a mesma notificação de [iniciarPrimeiroPlano]. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        infoPrimeiroPlano(ConversionNotificationManager(applicationContext))

    /** Há pedido de pausa do app (ou do limite do sistema) que deve parar a conversão preservando o job. */
    private fun devePausar(): Boolean =
        RegraDaParada.destino(ControleDaConversao.pedido, canceladoPeloApp = false) == DestinoDaParada.PAUSADA

    /**
     * Pausa de verdade: o motor já parou (job dir do Python preservado, ele só limpa em sucesso). Guarda o [inputData]
     * exato para retomar, avisa a fila e a notificação, e devolve sucesso marcado com [KEY_RESULT_PAUSADA].
     */
    private suspend fun pausar(
        notif: ConversionNotificationManager, arquivo: String, pct: Int, runToken: String?, job: String? = null
    ): Result {
        val titulo = File(arquivo).nameWithoutExtension
        notif.cancelar()
        if (!ConversaoPausadaStore.salvar(applicationContext, inputData, titulo, pct, job)) {
            // Sem o job guardado não há como retomar: trata como erro, pelo mesmo caminho do erro comum.
            val mensagem = applicationContext.getString(com.jonjonesbr.audiobookgen.R.string.conv_pausada_falha_salvar)
            FilaDeConversao.aoTerminarExecucao(applicationContext, inputData.getString(KEY_FILA_ITEM_ID), runToken, null, mensagem)
            return Result.failure(workDataOf(KEY_ERROR_MSG to mensagem, KEY_RUN_TOKEN to runToken))
        }
        FilaDeConversao.aoPausarExecucao(applicationContext, inputData.getString(KEY_FILA_ITEM_ID), runToken)
        notif.mostrarPausada(titulo)
        return Result.success(workDataOf(KEY_RESULT_PAUSADA to true, KEY_RUN_TOKEN to runToken))
    }

    /**
     * Comprime o WAV disfarçado de .mp3 (motor local) para AAC, se houver espaço (≥ 0,2 × o WAV). Mostra a etapa com
     * [PCT_COMPRIMINDO]. Devolve true se o arquivo foi substituído. Falhas ficam com o WAV; cancelamento propaga.
     */
    private suspend fun comprimirSeNecessario(arquivo: File, notif: ConversionNotificationManager, runToken: String?): Boolean {
        if (!ehWavArquivo(arquivo)) return false
        if (!temEspacoParaCompressao(arquivo.usableSpace, arquivo.length())) return false
        val mensagem = applicationContext.getString(com.jonjonesbr.audiobookgen.R.string.conv_comprimindo_audio)
        notif.mostrar(PCT_COMPRIMINDO, mensagem)
        setProgressAsync(workDataOf(
            KEY_PROGRESS_PCT to PCT_COMPRIMINDO,
            KEY_PROGRESS_MSG to mensagem,
            KEY_PROGRESS_NOME to arquivo.nameWithoutExtension,
            KEY_RUN_TOKEN to runToken
        ))
        return comprimirWavParaAac(arquivo)
    }

    /** Tamanho no mesmo formato que o Python devolve (MB com uma casa decimal, ponto como separador). */
    private fun formatarTamanhoMb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)

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
        val emPrimeiroPlano = iniciarPrimeiroPlano(notif)
        val inicioPrimeiroPlanoMs = if (emPrimeiroPlano) System.currentTimeMillis() else 0L
        ConversionWakeLock.acquire(applicationContext)
        var ultimaRenovacaoMs = System.currentTimeMillis()
        val engine = PythonEngineUseCase(applicationContext)
        engine.inicializarMotorSeNecessario()
        val tomDoLivro = inputData.getInt(KEY_TOM_MEIOS_TONS, SEM_VALOR).takeIf { it != SEM_VALOR }
        val agudosDoLivro = inputData.getInt(KEY_AGUDOS_DB, SEM_VALOR).takeIf { it != SEM_VALOR }
        // Dicionário de pronúncia efetivo: o global mais o do livro. Vai como caminho; o JSON é montado aqui, no processo.
        val livroDoDicionario = inputData.getString(KEY_LIVRO_DICIONARIO) ?: livroOrigem
        engine.sincronizarConfiguracoes(motor, chaveGemini, pausaMs, tomDoLivro, agudosDoLivro, livroDoDicionario)
        // A partir daqui o dicionário do Python é deste livro: leitura e diálogos não o reescrevem até o fim da conversão.
        PythonEngineUseCase.marcarConversaoAtiva(true)

        var ultimaAtualizacaoMs: Long? = null
        // Último percentual visto (fora do limitador), para a pausa salvar onde a conversão estava.
        val ultimoPct = AtomicInteger(0)
        val onProgress: (Int, String) -> Unit = { pct, msg ->
                ultimoPct.set(pct)
                val agora = System.currentTimeMillis()
                // Limite do sistema para mediaProcessing (~6 h): pausa antes de o Android matar o primeiro plano.
                if (PoliticaPrimeiroPlano.deveAutoPausar(
                        Build.VERSION.SDK_INT, emPrimeiroPlano, VisibilidadeDoApp.appVisivel,
                        maxOf(inicioPrimeiroPlanoMs, VisibilidadeDoApp.ultimaVisivelMs), agora
                    ) && ControleDaConversao.pedido == null
                ) {
                    ControleDaConversao.pedirPausa(applicationContext, MotivoParada.LIMITE_DO_SISTEMA)
                }
                // Pausa pedida antes/durante a extração: o Python zera a flag em _reset_cancelar, então reforça a cada chamada.
                if (devePausar()) engine.cancelar()
                if (LimiteDeProgresso.deveAtualizar(ultimaAtualizacaoMs, agora, pct)) {
                    notif.mostrar(pct, msg)
                    setProgressAsync(workDataOf(
                        KEY_PROGRESS_PCT to pct,
                        KEY_PROGRESS_MSG to msg,
                        KEY_PROGRESS_NOME to File(saida).nameWithoutExtension,
                        KEY_RUN_TOKEN to runToken
                    ))
                    ultimaAtualizacaoMs = agora
                }
                if (RenovacaoDeTrava.deveRenovar(ultimaRenovacaoMs, agora)) {
                    ConversionWakeLock.renovar()
                    ultimaRenovacaoMs = agora
                }
            }
        val speakerMapPath = inputData.getString(KEY_SPEAKER_MAP_PATH)
            ?.takeIf { File(it).isFile }
        val textoComMapa = if (speakerMapPath != null && File(arquivo).extension.equals("txt", true)) {
            runCatching { File(arquivo).readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        // O Python zera a flag de cancelamento ao iniciar: se a pausa já foi pedida, não inicia o motor.
        if (devePausar()) return pausar(notif, arquivo, ultimoPct.get(), runToken)
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
                // Motor local gravou WAV com nome .mp3: comprime para AAC no mesmo caminho antes de marcar, vincular e exportar.
                val arquivoGerado = File(result.caminho)
                val comprimido = comprimirSeNecessario(arquivoGerado, notif, runToken)
                val tamanhoMb = if (comprimido) formatarTamanhoMb(arquivoGerado.length()) else result.tamanhoMb
                // O histórico foi gravado pelo Python com o tamanho do WAV: corrige a entrada desta conversão.
                if (comprimido) engine.atualizarTamanhoHistorico(result.tamanhoMb, tamanhoMb)
                val capitulosJson = ChapterMarksStore.paraJson(result.capitulos)
                ChapterMarksStore.salvarJson(applicationContext, result.caminho, capitulosJson)
                // Fica fora do outputData (limite de 10 KB do WorkManager): o leitor lê do arquivo.
                com.jonjonesbr.audiobookgen.data.SincroniaAudioStore.salvarJson(applicationContext, result.caminho, result.trechosJson)
                TimbreAudiobookStore.salvarDaConversao(applicationContext, result.caminho, tomDoLivro, agudosDoLivro)
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
                        KEY_RESULT_TAMANHO to tamanhoMb,
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
                // Pausa pedida pelo app interrompe o motor e chega aqui como erro ("Cancelado pelo usuário."): não é erro.
                if (devePausar()) return pausar(notif, arquivo, ultimoPct.get(), runToken, result.job)
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
