package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import android.net.Uri
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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val ENTRADA_CONFIG = "config.json"
private const val ENTRADA_BOOKMARKS = "bookmarks.json"
private const val ENTRADA_HIGHLIGHTS = "highlights.json"
private const val ENTRADA_PROGRESSO = "progresso.json"
private const val ENTRADA_ESTATISTICAS = "estatisticas.json"
private const val ENTRADA_CAPITULOS = "capitulos.json"
private const val ENTRADA_VINCULOS = "vinculos.json"
private const val ENTRADA_PERFIS_VOZ = "perfis_voz.json"

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
        val out = context.contentResolver.openOutputStream(destino) ?: error("Não foi possível abrir o destino.")
        out.use {
            ZipOutputStream(it).use { zip ->
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

        entradas[ENTRADA_CAPITULOS]?.let { ChapterMarksStore.importarMerge(context, mapaImportarJson(it)) }
        entradas[ENTRADA_VINCULOS]?.let { LinkedBookStore.importarMerge(context, mapaImportarJson(it)) }
        entradas[ENTRADA_PERFIS_VOZ]?.let { GuidedBookPrefsStore.importarMerge(context, mapaImportarJson(it)) }

        ImportSummary(temConfig, bookmarksNovos, highlightsNovos, progressoNovos)
    }

    // ── ZIP I/O ───────────────────────────────────────────────────────────

    private fun escreverEntrada(zip: ZipOutputStream, nome: String, conteudo: String) {
        zip.putNextEntry(ZipEntry(nome))
        zip.write(conteudo.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun lerEntradas(origem: Uri): Map<String, String> {
        val resultado = mutableMapOf<String, String>()
        val input = context.contentResolver.openInputStream(origem) ?: return resultado
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    resultado[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return resultado
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
        val entradas = try {
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

    private fun mapaImportarJson(json: String): Map<String, String> = try {
        val obj = JSONObject(json)
        obj.keys().asSequence().associateWith { obj.getString(it) }
    } catch (_: Exception) {
        emptyMap()
    }
}
