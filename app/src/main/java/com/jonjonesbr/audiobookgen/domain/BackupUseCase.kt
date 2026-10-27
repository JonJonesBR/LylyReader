package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.AppPrefsBackup
import com.jonjonesbr.audiobookgen.data.BookmarkStore
import com.jonjonesbr.audiobookgen.data.HighlightStore
import com.jonjonesbr.audiobookgen.data.LinkedBookStore
import com.jonjonesbr.audiobookgen.data.StatsStore
import com.jonjonesbr.audiobookgen.service.ChapterMarksStore
import com.jonjonesbr.audiobookgen.service.GuidedBookPrefsStore
import com.jonjonesbr.audiobookgen.service.PlaybackProgressStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "BackupUseCase"
private const val ENTRADA_CONFIG = "config.json"
private const val ENTRADA_BOOKMARKS = "bookmarks.json"
private const val ENTRADA_HIGHLIGHTS = "highlights.json"
private const val ENTRADA_PROGRESSO = "progresso.json"
private const val ENTRADA_ESTATISTICAS = "estatisticas.json"
private const val ENTRADA_CAPITULOS = "capitulos.json"
private const val ENTRADA_VINCULOS = "vinculos.json"
private const val ENTRADA_PERFIS_VOZ = "perfis_voz.json"
private const val ENTRADA_VERSAO = "versao.json"

/** Versão do formato de backup que este app grava e sabe ler. Backup sem `versao.json` é antigo (aceito). */
internal const val VERSAO_BACKUP = 1

private val ENTRADAS_CONTEUDO = setOf(
    ENTRADA_CONFIG, ENTRADA_BOOKMARKS, ENTRADA_HIGHLIGHTS, ENTRADA_PROGRESSO,
    ENTRADA_ESTATISTICAS, ENTRADA_CAPITULOS, ENTRADA_VINCULOS, ENTRADA_PERFIS_VOZ
)

/** Só estas entradas são lidas do ZIP; as outras são puladas sem ocupar memória. */
private val ENTRADAS_LIDAS = ENTRADAS_CONTEUDO + ENTRADA_VERSAO

/**
 * Tetos de leitura de um backup. São folgados: um backup grande de uso real fica bem abaixo deles.
 * Existem só para um ZIP enorme ou adulterado não estourar a memória do app durante a restauração.
 */
internal const val LIMITE_ENTRADA_BYTES = 32L * 1024 * 1024
internal const val LIMITE_TOTAL_BYTES = 64L * 1024 * 1024

/**
 * Backup/restauração completa (Fase 5, item 1): um ZIP com configurações, progresso de
 * audiolivros, marcadores, destaques e estatísticas. NUNCA inclui `SecurePreferences`
 * (chaves de API) — cada entrada é lida do store correspondente, que já sabe seu próprio
 * formato JSON (fonte única de verdade, sem duplicar forma de dado aqui).
 *
 * Restauração usa merge conservador em cada store (ver `importarMerge` de cada um): nunca
 * perde ou regride dado local já existente, só complementa com o que falta.
 */
class BackupUseCase(private val context: Context) {

    data class ImportSummary(
        val configImportado: Boolean,
        val bookmarks: Int,
        val highlights: Int,
        val progresso: Int
    )

    fun exportar(destino: Uri): Result<Unit> = runCatching {
        val appPrefs = AppPrefs(context)
        val out = context.contentResolver.openOutputStream(destino) ?: error(context.getString(R.string.backup_destino_indisponivel))
        out.use {
            ZipOutputStream(it).use { zip ->
                escreverEntrada(zip, ENTRADA_VERSAO, VERSAO_BACKUP.toString())
                escreverEntrada(zip, ENTRADA_CONFIG, AppPrefsBackup.paraJson(appPrefs).toString())
                escreverEntrada(zip, ENTRADA_BOOKMARKS, BookmarkStore.paraJson(context))
                escreverEntrada(zip, ENTRADA_HIGHLIGHTS, HighlightStore.paraJson(context))
                escreverEntrada(zip, ENTRADA_PROGRESSO, progressoParaJson())
                escreverEntrada(zip, ENTRADA_ESTATISTICAS, StatsStore.paraJson(context))
                escreverEntrada(zip, ENTRADA_CAPITULOS, mapaParaJson(ChapterMarksStore.exportarTudo(context)))
                escreverEntrada(zip, ENTRADA_VINCULOS, mapaParaJson(LinkedBookStore.exportarTudo(context)))
                escreverEntrada(zip, ENTRADA_PERFIS_VOZ, mapaParaJson(GuidedBookPrefsStore.exportarTudo(context)))
            }
        }
    }

    fun importar(origem: Uri): Result<ImportSummary> = runCatching {
        val entradas = lerEntradas(origem)
        motivoRejeicaoBackup(entradas)?.let { throw BackupException(it) }
        val appPrefs = AppPrefs(context)

        val temConfig = entradas[ENTRADA_CONFIG]?.also { AppPrefsBackup.aplicar(appPrefs, JSONObject(it)) } != null

        val bookmarksAntes = BookmarkStore.loadAll(context).size
        entradas[ENTRADA_BOOKMARKS]?.let { BookmarkStore.importarMerge(context, it) }
        val bookmarksNovos = BookmarkStore.loadAll(context).size - bookmarksAntes

        val highlightsAntes = HighlightStore.loadAll(context).size
        entradas[ENTRADA_HIGHLIGHTS]?.let { HighlightStore.importarMerge(context, it) }
        val highlightsNovos = HighlightStore.loadAll(context).size - highlightsAntes

        val progressoAntes = PlaybackProgressStore.exportarTudo(context).size
        entradas[ENTRADA_PROGRESSO]?.let { progressoImportarJson(it) }
        val progressoNovos = PlaybackProgressStore.exportarTudo(context).size - progressoAntes

        entradas[ENTRADA_ESTATISTICAS]?.let { StatsStore.importarMerge(context, it) }

        entradas[ENTRADA_CAPITULOS]?.let { ChapterMarksStore.importarMerge(context, mapaImportarJson(it, ENTRADA_CAPITULOS)) }
        entradas[ENTRADA_VINCULOS]?.let { LinkedBookStore.importarMerge(context, mapaImportarJson(it, ENTRADA_VINCULOS)) }
        entradas[ENTRADA_PERFIS_VOZ]?.let { GuidedBookPrefsStore.importarMerge(context, mapaImportarJson(it, ENTRADA_PERFIS_VOZ)) }

        ImportSummary(temConfig, bookmarksNovos, highlightsNovos, progressoNovos)
    }

    // ── ZIP I/O ───────────────────────────────────────────────────────────

    private fun escreverEntrada(zip: ZipOutputStream, nome: String, conteudo: String) {
        zip.putNextEntry(ZipEntry(nome))
        zip.write(conteudo.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun lerEntradas(origem: Uri): Map<String, String> {
        val input = context.contentResolver.openInputStream(origem) ?: return emptyMap()
        return input.use { lerEntradasZip(it) }
    }

    // ── Progresso: único store cujo (de)serializador fica aqui (Progress tem só 2 campos) ──

    private fun progressoParaJson(): String {
        val arr = JSONArray()
        PlaybackProgressStore.exportarTudo(context).forEach { (caminho, p) ->
            arr.put(
                JSONObject().put("caminho", caminho).put("positionMs", p.positionMs).put("durationMs", p.durationMs)
            )
        }
        return arr.toString()
    }

    private fun progressoImportarJson(json: String) {
        val entradas = progressoDeBackup(json) ?: run {
            Log.w(TAG, "Backup: progresso.json corrompido; progresso ignorado")
            return
        }
        PlaybackProgressStore.importarMerge(context, entradas)
    }

    // ── Serialização genérica de Map<String, String>, usada por Capítulos/Vínculos/Perfis ──

    private fun mapaParaJson(mapa: Map<String, String>): String {
        val obj = JSONObject()
        mapa.forEach { (chave, valor) -> obj.put(chave, valor) }
        return obj.toString()
    }

    private fun mapaImportarJson(json: String, entrada: String): Map<String, String> =
        mapaDeBackup(json) ?: run {
            Log.w(TAG, "Backup: $entrada corrompido; entrada ignorada")
            emptyMap()
        }
}

/** Por que um backup não pode ser importado. A tela traduz o motivo em texto amigável. */
enum class MotivoBackup { ARQUIVO_INVALIDO, VERSAO_MAIS_NOVA, MUITO_GRANDE }

class BackupException(val motivo: MotivoBackup) : Exception(motivo.name)

// ── Funções puras (sem Context), testadas em BackupUseCaseTest ──

/**
 * Diz por que um backup não pode ser importado, ou null se pode. Sem a entrada de versão é backup
 * antigo, aceito. Versão ilegível (ou menor que 1) ou nenhuma entrada conhecida → arquivo inválido
 * (ZIP inválido, vazio ou de outro app). Versão acima de [VERSAO_BACKUP] → versão mais nova; isso
 * é checado antes das entradas, porque um formato futuro pode trocar os nomes delas.
 */
internal fun motivoRejeicaoBackup(entradas: Map<String, String>): MotivoBackup? {
    if (ENTRADA_VERSAO in entradas) {
        val versao = entradas.getValue(ENTRADA_VERSAO).trim().toIntOrNull()?.takeIf { it >= 1 }
            ?: return MotivoBackup.ARQUIVO_INVALIDO
        if (versao > VERSAO_BACKUP) return MotivoBackup.VERSAO_MAIS_NOVA
    }
    val temConteudo = entradas.keys.any { it in ENTRADAS_CONTEUDO }
    return if (temConteudo) null else MotivoBackup.ARQUIVO_INVALIDO
}

/**
 * Lê as entradas conhecidas de um ZIP de backup como texto UTF-8. ZIP inválido ou vazio → mapa vazio.
 * ZIP cortado ou corrompido → [BackupException] com `ARQUIVO_INVALIDO`. Entrada grande demais (por
 * arquivo ou no total) → [BackupException] com `MUITO_GRANDE`.
 */
internal fun lerEntradasZip(
    stream: InputStream,
    limiteEntrada: Long = LIMITE_ENTRADA_BYTES,
    limiteTotal: Long = LIMITE_TOTAL_BYTES,
): Map<String, String> {
    val resultado = mutableMapOf<String, String>()
    var lidoTotal = 0L
    try {
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name in ENTRADAS_LIDAS) {
                    val bytes = lerAteLimite(zip, limiteEntrada)
                    lidoTotal += bytes.size
                    if (lidoTotal > limiteTotal) throw BackupException(MotivoBackup.MUITO_GRANDE)
                    resultado[entry.name] = bytes.toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } catch (e: ZipException) {
        throw BackupException(MotivoBackup.ARQUIVO_INVALIDO).also { it.initCause(e) }
    } catch (e: EOFException) {
        throw BackupException(MotivoBackup.ARQUIVO_INVALIDO).also { it.initCause(e) }
    }
    return resultado
}

/** Lê a entrada atual do ZIP até o fim, parando assim que passar do [limite] (sem carregar o resto). */
private fun lerAteLimite(zip: ZipInputStream, limite: Long): ByteArray {
    val saida = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = zip.read(buffer)
        if (n < 0) break
        if (saida.size() + n > limite) throw BackupException(MotivoBackup.MUITO_GRANDE)
        saida.write(buffer, 0, n)
    }
    return saida.toByteArray()
}

/** Decodifica progresso.json. Entradas inválidas (caminho vazio, posição < 0, duração <= 0) são descartadas. JSON corrompido → null. */
internal fun progressoDeBackup(json: String): Map<String, PlaybackProgressStore.Progress>? = try {
    val arr = JSONArray(json)
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val caminho = o.optString("caminho", "")
        val pos = o.optInt("positionMs", -1)
        val dur = o.optInt("durationMs", -1)
        if (caminho.isBlank() || pos < 0 || dur <= 0) null
        else caminho to PlaybackProgressStore.Progress(pos, dur)
    }.toMap()
} catch (_: Exception) {
    null
}

/** Decodifica um mapa String→String de backup. JSON corrompido ou valor não-texto → null. */
internal fun mapaDeBackup(json: String): Map<String, String>? = try {
    val obj = JSONObject(json)
    obj.keys().asSequence().associateWith { obj.getString(it) }
} catch (_: Exception) {
    null
}
