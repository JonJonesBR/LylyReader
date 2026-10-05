package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.ChavesDeLivro
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PREFS = "audiobookgen_prefs"
private const val KEY_DIAS = "stats_dias"
private const val KEY_LIVROS_CONCLUIDOS = "stats_livros_concluidos"
private const val DIAS_RETIDOS = 60

private val formatoData = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/**
 * Estatísticas de leitura/escuta 100% locais (nada é enviado a servidor algum).
 *
 * Acumula minutos ouvidos (audiolivro convertido + leitura guiada) e minutos lidos por dia
 * (chave "yyyy-MM-dd", fuso do aparelho), mais o conjunto de livros já concluídos. Mesmo
 * padrão de storage JSON-em-prefs do [RecentReadingsStore].
 */
object StatsStore {
    private const val JANELA_GRAFICO_DIAS = 7

    data class DiaStats(val data: String, val minutosOuvidos: Int, val minutosLidos: Int)
    data class Totais(
        val totalMinutosOuvidos: Int,
        val totalMinutosLidos: Int,
        val livrosConcluidos: Int,
        val streakDias: Int
    )

    fun registrarMinutosOuvidos(ctx: Context, minutos: Int) = acumularMinutos(ctx, ouvidos = minutos, lidos = 0)

    fun registrarMinutosLidos(ctx: Context, minutos: Int) = acumularMinutos(ctx, ouvidos = 0, lidos = minutos)

    private fun acumularMinutos(ctx: Context, ouvidos: Int, lidos: Int) {
        if (ouvidos <= 0 && lidos <= 0) return
        val dias = carregarDias(ctx).toMutableList()
        val hoje = formatoData.format(Date())
        val idx = dias.indexOfFirst { it.data == hoje }
        if (idx >= 0) {
            val atual = dias[idx]
            dias[idx] = atual.copy(
                minutosOuvidos = atual.minutosOuvidos + ouvidos,
                minutosLidos = atual.minutosLidos + lidos
            )
        } else {
            dias.add(DiaStats(hoje, ouvidos, lidos))
        }
        salvarDias(ctx, dias.takeLast(DIAS_RETIDOS))
    }

    /** Migração de caminho (livro foi para a biblioteca): o livro concluído passa a ser identificado por [novo]. */
    fun renomearLivroConcluido(ctx: Context, antigo: String, novo: String) {
        val atuais = carregarLivrosConcluidos(ctx)
        val migrados = ChavesDeLivro.migrarCaminhos(atuais, antigo, novo)
        if (migrados != atuais) salvarLivrosConcluidos(ctx, migrados)
    }

    /** Idempotente — um livro já registrado não é contado de novo. */
    fun registrarLivroConcluido(ctx: Context, caminho: String) {
        if (caminho.isBlank()) return
        val atuais = carregarLivrosConcluidos(ctx)
        if (caminho in atuais) return
        salvarLivrosConcluidos(ctx, atuais + caminho)
    }

    fun totais(ctx: Context): Totais {
        val dias = carregarDias(ctx)
        return Totais(
            totalMinutosOuvidos = dias.sumOf { it.minutosOuvidos },
            totalMinutosLidos = dias.sumOf { it.minutosLidos },
            livrosConcluidos = carregarLivrosConcluidos(ctx).size,
            streakDias = calcularStreak(dias)
        )
    }

    /** Últimos 7 dias, mais antigo primeiro, zero-preenchidos onde não houve atividade. */
    fun ultimosSeteDias(ctx: Context): List<DiaStats> {
        val porData = carregarDias(ctx).associateBy { it.data }
        val cal = Calendar.getInstance()
        return (JANELA_GRAFICO_DIAS - 1 downTo 0).map { offset ->
            val dia = cal.clone() as Calendar
            dia.add(Calendar.DAY_OF_YEAR, -offset)
            val data = formatoData.format(dia.time)
            porData[data] ?: DiaStats(data, 0, 0)
        }
    }

    /** JSON com dias + livros concluídos, para backup completo (Fase 5). */
    fun paraJson(ctx: Context): String =
        JSONObject()
            .put("dias", diasParaJsonArray(carregarDias(ctx)))
            .put("livrosConcluidos", JSONArray(carregarLivrosConcluidos(ctx)))
            .toString()

    /**
     * Importa de um backup, mesclando de forma conservadora: por dia, mantém o MAIOR valor
     * entre local e importado (nunca perde progresso já registrado); livros concluídos vira
     * união dos dois conjuntos.
     */
    fun importarMerge(ctx: Context, json: String) {
        val obj = try { JSONObject(json) } catch (_: Exception) { return }
        val importados = diasDeJsonArray(obj.optJSONArray("dias") ?: JSONArray())
        val locais = carregarDias(ctx).associateBy { it.data }.toMutableMap()
        importados.forEach { dia ->
            val atual = locais[dia.data]
            locais[dia.data] = if (atual == null) dia else DiaStats(
                dia.data,
                maxOf(atual.minutosOuvidos, dia.minutosOuvidos),
                maxOf(atual.minutosLidos, dia.minutosLidos)
            )
        }
        salvarDias(ctx, locais.values.sortedBy { it.data }.takeLast(DIAS_RETIDOS))

        val livrosArr = obj.optJSONArray("livrosConcluidos") ?: JSONArray()
        val livrosImportados = (0 until livrosArr.length()).mapNotNull {
            livrosArr.optString(it, "").takeIf { s -> s.isNotBlank() }
        }
        salvarLivrosConcluidos(ctx, (carregarLivrosConcluidos(ctx) + livrosImportados).distinct())
    }
}

private fun prefsDe(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** Dias consecutivos (incluindo hoje) com pelo menos 1 minuto ouvido ou lido. */
private fun calcularStreak(dias: List<StatsStore.DiaStats>): Int {
    val comAtividade = dias.filter { it.minutosOuvidos > 0 || it.minutosLidos > 0 }.map { it.data }.toSet()
    val cal = Calendar.getInstance()
    var streak = 0
    while (comAtividade.contains(formatoData.format(cal.time))) {
        streak++
        cal.add(Calendar.DAY_OF_YEAR, -1)
    }
    return streak
}

private fun diasParaJsonArray(dias: List<StatsStore.DiaStats>): JSONArray {
    val arr = JSONArray()
    dias.forEach {
        arr.put(JSONObject().put("data", it.data).put("ouvidos", it.minutosOuvidos).put("lidos", it.minutosLidos))
    }
    return arr
}

private fun diasDeJsonArray(arr: JSONArray): List<StatsStore.DiaStats> =
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val data = o.optString("data", "")
        if (data.isBlank()) null else StatsStore.DiaStats(data, o.optInt("ouvidos", 0), o.optInt("lidos", 0))
    }

private fun carregarDias(ctx: Context): List<StatsStore.DiaStats> {
    val raw = prefsDe(ctx).getString(KEY_DIAS, "[]") ?: "[]"
    return try {
        diasDeJsonArray(JSONArray(raw))
    } catch (_: Exception) {
        emptyList()
    }
}

private fun salvarDias(ctx: Context, dias: List<StatsStore.DiaStats>) {
    prefsDe(ctx).edit().putString(KEY_DIAS, diasParaJsonArray(dias).toString()).apply()
}

private fun carregarLivrosConcluidos(ctx: Context): List<String> {
    val raw = prefsDe(ctx).getString(KEY_LIVROS_CONCLUIDOS, "[]") ?: "[]"
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun salvarLivrosConcluidos(ctx: Context, livros: List<String>) {
    prefsDe(ctx).edit().putString(KEY_LIVROS_CONCLUIDOS, JSONArray(livros).toString()).apply()
}
