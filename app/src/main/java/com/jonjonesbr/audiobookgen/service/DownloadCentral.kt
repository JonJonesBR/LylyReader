package com.jonjonesbr.audiobookgen.service

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.EstadoDownload
import com.jonjonesbr.audiobookgen.domain.proximosDaFila
import com.jonjonesbr.audiobookgen.tts.PocketEncoderManager
import com.jonjonesbr.audiobookgen.util.ProgressoDownload
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class GrupoDownload { LIVROS, MOTORES, CODIFICADORES, IMPORTADAS }

/** Um pacote que a Central de downloads sabe baixar, mostrar e apagar. */
data class ItemBaixavel(
    val id: String,
    val nome: String,
    val grupo: GrupoDownload,
    val tamanhoMb: Int,
    val isPronto: (Context) -> Boolean,
    val ocupadoBytes: (Context) -> Long,
    /** Null = não se baixa (voz importada pelo usuário): só aparece para apagar. */
    val download: (suspend (Context, ProgressoDownload) -> Unit)?,
    val apagar: (Context) -> Unit,
    /** Códigos de idioma das vozes do pacote ([com.jonjonesbr.audiobookgen.domain.IdiomaDownload]); vazio = sem idioma conhecido. */
    val idiomas: Set<String> = emptySet()
)

data class AndamentoDownload(
    val estado: EstadoDownload = EstadoDownload.NAO_BAIXADO,
    val bytes: Long = 0L,
    val total: Long = 0L,
    /** Bytes em disco quando o pacote está pronto. */
    val ocupadoBytes: Long = 0L,
    val erro: String? = null
) {
    val fracao: Float get() = if (total <= 0L) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Fila central de downloads dos pacotes de voz (Supertonic, Kokoro, Piper, MMS, Pocket, codificadores),
 * acima dos `*ModelManager`. Vive no processo (não na tela): fechar a Central não interrompe nada; o
 * [DownloadService] mantém o processo vivo e mostra a notificação agregada.
 *
 * **Pausar** cancela a corrotina e mantém o `.part` (todos os gerenciadores retomam por Range);
 * **Cancelar** pausa e apaga o parcial. Máximo de downloads simultâneos em [AppPrefs.downloadsSimultaneos].
 */
object DownloadCentral {
    private const val TAG = "DownloadCentral"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val fila = ArrayList<String>()
    private val jobs = HashMap<String, Job>()

    private val _andamento = MutableStateFlow<Map<String, AndamentoDownload>>(emptyMap())
    val andamento: StateFlow<Map<String, AndamentoDownload>> = _andamento

    private val encoders = listOf("pt-BR" to "pt", "en-US" to "en", "es-ES" to "es")

    /** Livros em download (itens que só existem enquanto baixam; somem da lista pouco depois de entrar na biblioteca). */
    private val livrosEmDownload = LinkedHashMap<String, ItemBaixavel>()
    private const val PAUSA_ANTES_DE_SUMIR_MS = 4_000L

    /**
     * Põe livros de fontes legais na fila de downloads (segundo plano, com notificação). Pula os que já estão na biblioteca ou
     * já na fila. Devolve quantos entraram na fila.
     */
    suspend fun baixarLivros(context: Context, livros: List<com.jonjonesbr.audiobookgen.domain.LivroEncontrado>): Int {
        val ctx = context.applicationContext
        val naBiblioteca = com.jonjonesbr.audiobookgen.data.BibliotecaStore.listar(ctx).mapNotNull { it.chaveOrigem }.toSet()
        val novos = synchronized(lock) {
            livros.filter { it.chave !in naBiblioteca && "livro-${it.chave}" !in livrosEmDownload }.also { lista ->
                lista.forEach { livro ->
                    val id = "livro-${livro.chave}"
                    livrosEmDownload[id] = ItemBaixavel(
                        id = id,
                        nome = livro.titulo,
                        grupo = GrupoDownload.LIVROS,
                        tamanhoMb = 1,
                        isPronto = { c -> kotlinx.coroutines.runBlocking { com.jonjonesbr.audiobookgen.data.BibliotecaStore.porChaveOrigem(c, livro.chave) != null } },
                        ocupadoBytes = { 0L },
                        download = { c, progresso -> com.jonjonesbr.audiobookgen.data.LivrosDownloads.guardar(c, livro, progresso) },
                        apagar = { },
                        idiomas = com.jonjonesbr.audiobookgen.domain.IdiomaDownload.codigos(listOfNotNull(livro.idioma))
                    )
                }
            }
        }
        if (novos.isEmpty()) return 0
        enfileirar(ctx, novos.map { "livro-${it.chave}" })
        return novos.size
    }

    private fun sumirDepois(id: String) {
        scope.launch {
            kotlinx.coroutines.delay(PAUSA_ANTES_DE_SUMIR_MS)
            val removido = synchronized(lock) { livrosEmDownload.remove(id) }
            if (removido != null) _andamento.update { it - id }
        }
    }

    /** Todos os itens conhecidos agora (pacotes de voz + codificadores + vozes importadas). */
    fun itens(): List<ItemBaixavel> {
        val pacotes = VoiceCatalog.pacotesRegistrados().map { p ->
            ItemBaixavel(
                id = p.id,
                nome = p.nomeExibicao,
                // Voz importada (BYOM) ou clonada pelo usuário: não é um pacote do catálogo.
                grupo = if (p.download != null && !p.id.startsWith("pocket-custom-")) GrupoDownload.MOTORES
                else GrupoDownload.IMPORTADAS,
                tamanhoMb = p.tamanhoDownloadMb,
                isPronto = p.isPronto,
                ocupadoBytes = p.tamanhoOcupadoBytes,
                download = p.download,
                apagar = p.delete,
                idiomas = com.jonjonesbr.audiobookgen.domain.IdiomaDownload.codigos(p.vozes.map { it.language })
            )
        }
        val codificadores = encoders.map { (tag, codigo) ->
            ItemBaixavel(
                id = "pocket-encoder-$codigo",
                nome = "Pocket · codificador de voz ($tag)",
                grupo = GrupoDownload.CODIFICADORES,
                tamanhoMb = PocketEncoderManager.DOWNLOAD_MB,
                isPronto = { ctx -> PocketEncoderManager.isInstalled(ctx, tag) },
                ocupadoBytes = { ctx -> PocketEncoderManager.installedFile(ctx, tag).let { if (it.isFile) it.length() else 0L } },
                download = { ctx, progresso -> PocketEncoderManager.download(ctx, tag, progresso) },
                apagar = { ctx -> PocketEncoderManager.delete(ctx, tag) },
                idiomas = com.jonjonesbr.audiobookgen.domain.IdiomaDownload.codigos(listOf(tag))
            )
        }
        val livros = synchronized(lock) { livrosEmDownload.values.toList() }
        return livros + pacotes + codificadores
    }

    private fun item(id: String): ItemBaixavel? = itens().firstOrNull { it.id == id }

    /** Recalcula o que está pronto/ocupado em disco, sem mexer em quem está na fila ou baixando. */
    suspend fun atualizar(context: Context) = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val medidos = itens().associate { it.id to (it.isPronto(ctx) to it.ocupadoBytes(ctx)) }
        _andamento.update { atual ->
            val novo = HashMap(atual)
            for ((id, medida) in medidos) {
                val (pronto, ocupado) = medida
                val corrente = atual[id]
                val ativo = corrente?.estado == EstadoDownload.NA_FILA || corrente?.estado == EstadoDownload.BAIXANDO
                novo[id] = when {
                    ativo -> corrente!!
                    pronto -> AndamentoDownload(EstadoDownload.PRONTO, ocupadoBytes = ocupado)
                    corrente?.estado == EstadoDownload.PAUSADO || corrente?.estado == EstadoDownload.ERRO -> corrente
                    else -> AndamentoDownload(EstadoDownload.NAO_BAIXADO, ocupadoBytes = ocupado)
                }
            }
            novo
        }
    }

    /** Coloca os pacotes na fila (ou retoma os pausados / com erro) e começa os que couberem. */
    fun enfileirar(context: Context, ids: Collection<String>) {
        val ctx = context.applicationContext
        synchronized(lock) {
            for (id in ids) {
                val item = item(id) ?: continue
                if (item.download == null) continue
                val estado = _andamento.value[id]?.estado ?: EstadoDownload.NAO_BAIXADO
                if (estado == EstadoDownload.NA_FILA || estado == EstadoDownload.BAIXANDO || estado == EstadoDownload.PRONTO) continue
                _andamento.update { it + (id to AndamentoDownload(EstadoDownload.NA_FILA, it[id]?.bytes ?: 0L, it[id]?.total ?: 0L)) }
                fila.remove(id)
                fila.add(id)
            }
        }
        despachar(ctx)
    }

    private fun despachar(ctx: Context) {
        val comecar = synchronized(lock) {
            val limite = AppPrefs(ctx).downloadsSimultaneos
            val estados = _andamento.value.mapValues { it.value.estado }
            // Quem acabou de ser pausado ainda pode estar encerrando o job antigo: espera ele sair
            // (o finally dele chama despachar de novo) para dois jobs não gravarem o mesmo .part.
            val candidatos = fila.filter { jobs[it]?.isActive != true }
            val proximos = proximosDaFila(candidatos, estados, limite)
            for (id in proximos) {
                fila.remove(id)
                _andamento.update { it + (id to (it[id] ?: AndamentoDownload()).copy(estado = EstadoDownload.BAIXANDO, erro = null)) }
            }
            proximos
        }
        if (comecar.isEmpty()) return
        DownloadService.start(ctx)
        for (id in comecar) {
            val item = item(id) ?: continue
            val baixar = item.download ?: continue
            val job = scope.launch(Dispatchers.IO) { executar(ctx, item, baixar) }
            synchronized(lock) { jobs[id] = job }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executar(
        ctx: Context,
        item: ItemBaixavel,
        baixar: suspend (Context, ProgressoDownload) -> Unit
    ) {
        try {
            baixar(ctx) { _, _, bytes, total ->
                _andamento.update { atual ->
                    val corrente = atual[item.id] ?: return@update atual
                    if (corrente.estado != EstadoDownload.BAIXANDO) atual
                    else atual + (item.id to corrente.copy(bytes = bytes, total = if (total > 0L) total else corrente.total))
                }
            }
            if (item.isPronto(ctx)) {
                _andamento.update { it + (item.id to AndamentoDownload(EstadoDownload.PRONTO, ocupadoBytes = item.ocupadoBytes(ctx))) }
                if (item.grupo == GrupoDownload.LIVROS) sumirDepois(item.id)
            } else {
                _andamento.update { it + (item.id to AndamentoDownload(EstadoDownload.ERRO, erro = "verificação final falhou")) }
            }
        } catch (e: CancellationException) {
            // Pausar/cancelar já definiram o estado novo; aqui só deixa a corrotina morrer.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Download ${item.id} falhou: ${e.message}", e)
            _andamento.update { atual ->
                val corrente = atual[item.id] ?: AndamentoDownload()
                // Se o usuário já pausou/cancelou, o erro é só o efeito de ter interrompido.
                if (corrente.estado != EstadoDownload.BAIXANDO) atual
                else atual + (item.id to corrente.copy(estado = EstadoDownload.ERRO, erro = e.message ?: e.javaClass.simpleName))
            }
        } finally {
            synchronized(lock) { jobs.remove(item.id) }
            despachar(ctx)
        }
    }

    /** Pausa (mantém o que já baixou) ou tira da fila quem ainda não começou. */
    fun pausar(id: String) {
        val job = synchronized(lock) {
            fila.remove(id)
            val atual = _andamento.value[id] ?: return
            if (atual.estado != EstadoDownload.NA_FILA && atual.estado != EstadoDownload.BAIXANDO) return
            val novo = if (atual.bytes > 0L) EstadoDownload.PAUSADO else EstadoDownload.NAO_BAIXADO
            _andamento.update { it + (id to atual.copy(estado = novo)) }
            jobs[id]
        }
        job?.cancel()
    }

    fun pausarTodos() {
        _andamento.value.filterValues {
            it.estado == EstadoDownload.NA_FILA || it.estado == EstadoDownload.BAIXANDO
        }.keys.forEach(::pausar)
    }

    /** Cancela e apaga o parcial; o pacote volta a "não baixado". */
    fun cancelar(context: Context, id: String) = apagarArquivos(context, id)

    /** Apaga o pacote do disco (pronto ou parcial) depois de parar o download, se houver. */
    fun apagar(context: Context, id: String) = apagarArquivos(context, id)

    private fun apagarArquivos(context: Context, id: String) {
        val ctx = context.applicationContext
        val item = item(id) ?: return
        val job = synchronized(lock) {
            fila.remove(id)
            jobs[id]
        }
        _andamento.update { it + (id to AndamentoDownload(EstadoDownload.NAO_BAIXADO)) }
        scope.launch(Dispatchers.IO) {
            job?.cancelAndJoin()
            item.apagar(ctx)
            _andamento.update { it + (id to AndamentoDownload(EstadoDownload.NAO_BAIXADO)) }
            atualizar(ctx)
        }
    }

    /**
     * Baixa um pacote e espera terminar, repassando o progresso — para os fluxos que mostram um
     * diálogo (escolha de voz, Ajustes). Só OBSERVA: fechar/girar a tela (cancelar a espera) não
     * interrompe o download; o botão Cancelar do diálogo deve chamar [pausar] (aí a espera termina
     * com [CancellationException]).
     */
    suspend fun baixarEAguardar(context: Context, id: String, onProgress: ProgressoDownload) {
        val item = item(id) ?: throw IOException("Pacote desconhecido: $id")
        enfileirar(context, listOf(id))
        _andamento.first { mapa ->
            val a = mapa[id] ?: AndamentoDownload()
            onProgress(item.nome, a.fracao, a.bytes, a.total)
            when (a.estado) {
                EstadoDownload.PRONTO -> true
                EstadoDownload.ERRO -> throw IOException(a.erro ?: "download falhou")
                EstadoDownload.PAUSADO, EstadoDownload.NAO_BAIXADO -> throw CancellationException("download pausado")
                else -> false
            }
        }
    }

    /** Há algo na fila ou baixando? (o serviço em primeiro plano se encerra quando não houver.) */
    fun temAtividade(): Boolean = _andamento.value.values.any {
        it.estado == EstadoDownload.NA_FILA || it.estado == EstadoDownload.BAIXANDO
    }
}
