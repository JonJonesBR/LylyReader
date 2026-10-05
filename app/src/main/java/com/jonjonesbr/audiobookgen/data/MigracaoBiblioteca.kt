package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.util.Log
import com.jonjonesbr.audiobookgen.domain.OrigemLivro
import com.jonjonesbr.audiobookgen.domain.ehFormatoSuportado
import com.jonjonesbr.audiobookgen.domain.mesmaObra
import com.jonjonesbr.audiobookgen.domain.nomeSemPrefixoDeEntrada
import com.jonjonesbr.audiobookgen.domain.origemDaChave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Migração única (flag [AppPrefs.bibliotecaMigradaV1]): livros que estavam no cache do app (importações antigas
 * `entrada_<ts>_<nome>`, leituras recentes e downloads da busca) vão para a biblioteca persistente, e os dados
 * indexados pelo caminho antigo seguem para o caminho novo ([DadosDoLivro.migrar]).
 *
 * - Não apaga nada do cache (a fila de conversão pode apontar para os arquivos antigos; o sistema limpa sozinho).
 * - Idempotente: o mesmo conteúdo dá o mesmo livro; nunca sobrescreve dados que o caminho novo já tenha.
 * - Falha em um livro é registrada e a migração segue; só uma falha geral deixa a flag sem marcar (roda de novo).
 */
object MigracaoBiblioteca {
    private const val TAG = "MigracaoBiblioteca"
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rodando = AtomicBoolean(false)

    private class Candidato(val arquivo: File, val origem: OrigemLivro, val chaveOrigem: String?)

    /** Dispara em 2º plano (escopo próprio: não morre se a tela fechar). Não faz nada se já migrou ou já está rodando. */
    fun iniciarSeNecessario(ctx: Context) {
        val app = ctx.applicationContext
        if (AppPrefs(app).bibliotecaMigradaV1 || !rodando.compareAndSet(false, true)) return
        escopo.launch {
            try {
                executar(app)
            } finally {
                rodando.set(false)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executar(ctx: Context) {
        try {
            val candidatos = candidatos(ctx)
            var migrados = 0
            var fundidos = 0
            // Versões do mesmo texto (cópias editadas, reimportações): a mais recente vira o livro; as outras só
            // levam seus dados (posição, marcadores…) para ele, sem criar livro repetido.
            val vencedores = mutableListOf<Triple<String, Long, String>>() // nome, tamanho, caminho novo
            for (c in candidatos) {
                try {
                    val antigo = c.arquivo.absolutePath
                    val versao = if (c.chaveOrigem != null) null else vencedores.firstOrNull {
                        mesmaObra(it.first, it.second, c.arquivo.name, c.arquivo.length())
                    }
                    if (versao != null) {
                        DadosDoLivro.migrar(ctx, antigo, versao.third)
                        fundidos++
                        continue
                    }
                    val livro = BibliotecaStore.importarArquivo(
                        ctx, c.arquivo, nomeSemPrefixoDeEntrada(c.arquivo.name), c.origem, c.chaveOrigem
                    )
                    val novo = BibliotecaStore.arquivoDo(ctx, livro).absolutePath
                    DadosDoLivro.migrar(ctx, antigo, novo)
                    if (c.chaveOrigem == null) vencedores += Triple(c.arquivo.name, c.arquivo.length(), novo)
                    migrados++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Livro ${c.arquivo.name} não migrou: ${e.message}")
                }
            }
            AppPrefs(ctx).bibliotecaMigradaV1 = true
            Log.i(TAG, "Migração concluída: $migrados livros, $fundidos versões fundidas, de ${candidatos.size} arquivos")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Migração falhou (tenta de novo na próxima abertura): ${e.message}", e)
        }
    }

    /** Recentes primeiro (mais novos antes), depois o resto do cache do mais novo ao mais antigo; sem repetir arquivo. */
    private fun candidatos(ctx: Context): List<Candidato> {
        val vistos = mutableSetOf<String>()
        val lista = mutableListOf<Candidato>()
        fun adicionar(arquivo: File, origem: OrigemLivro, chave: String?) {
            if (arquivo.isFile && arquivo.length() > 0L && ehFormatoSuportado(arquivo.name) &&
                vistos.add(arquivo.absolutePath)
            ) {
                lista += Candidato(arquivo, origem, chave)
            }
        }
        RecentReadingsStore.load(ctx).sortedByDescending { it.ts }
            .forEach { adicionar(File(it.caminho), OrigemLivro.ARQUIVO, null) }
        ctx.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("entrada_") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .forEach { adicionar(it, OrigemLivro.ARQUIVO, null) }
        File(ctx.cacheDir, "livros_baixados").listFiles { f -> f.isDirectory }.orEmpty().forEach { pasta ->
            pasta.listFiles { f -> f.isFile }.orEmpty().sortedByDescending { it.lastModified() }
                .forEach { adicionar(it, origemDaChave(pasta.name), pasta.name) }
        }
        return lista
    }
}
