package com.jonjonesbr.audiobookgen.domain

import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Tipo de um registro de erro (lyly_error_*.txt), para a tela Relatar um problema. */
enum class TipoRegistroErro {
    /** Saída normal do sistema (pouca memória, limpeza, atualização). Não é defeito do app. */
    ENCERRAMENTO_NORMAL,

    /** Crash Java ou nativo do app. */
    TRAVAMENTO,

    /** ANR: o app parou de responder. */
    SEM_RESPOSTA,

    /** Qualquer outro registro técnico. */
    OUTRO
}

/**
 * Resumo de um registro. [excecao] é o nome curto da exceção (ex.: IllegalStateException), quando o
 * stack trace traz uma. [poucaMemoria] marca o encerramento por REASON_LOW_MEMORY.
 */
data class ResumoRegistroErro(
    val tipo: TipoRegistroErro,
    val excecao: String? = null,
    val poucaMemoria: Boolean = false
)

/** Quantas linhas do início do arquivo bastam: cabeçalho, motivo e a primeira linha do stack trace. */
const val LINHAS_PARA_RESUMO = 40

private const val FORMATO_DO_NOME = "yyyy-MM-dd'T'HH-mm-ss-SSS'Z'"

private val PALAVRAS_ENCERRAMENTO_NORMAL = listOf(
    "swipeupclean", "clean_up_mem", "installpackageli", "force-stop", "stop by user", "kill_background"
)

// Linha "  reason: REASON_…" ou "  description: …", como o NativeCrashDetector grava.
private val CAMPO_INFO = Regex("""\s*(reason|description):\s?(.*)""")

// Primeira linha do stack trace: nome qualificado da exceção, com ou sem mensagem depois de ": ".
private val NOME_DE_EXCECAO = Regex("""((?:[a-z_][\w$]*\.)*[A-Z][\w$]*)(?::.*)?""")

/**
 * Classifica um registro a partir das primeiras linhas do arquivo. Função pura: não lê arquivo,
 * então pode ser testada sem Android.
 */
fun classificarRegistroErro(linhas: List<String>): ResumoRegistroErro {
    var reason = ""
    var description = ""
    var temStackTrace = false
    var procurandoExcecao = false
    var excecao: String? = null
    for (linha in linhas) {
        if (procurandoExcecao && linha.isNotBlank()) {
            procurandoExcecao = false
            excecao = NOME_DE_EXCECAO.matchEntire(linha)?.groupValues?.get(1)
                ?.substringAfterLast('.')
                ?.substringAfterLast('$')
        }
        if (linha.trim() == "STACK TRACE:") {
            temStackTrace = true
            procurandoExcecao = true
            continue
        }
        // Só o primeiro valor vale: o trace_begin vem depois e pode repetir o nome do campo.
        val campo = CAMPO_INFO.matchEntire(linha)?.destructured ?: continue
        val (nome, valor) = campo
        if (nome == "reason" && reason.isEmpty()) reason = valor.trim()
        if (nome == "description" && description.isEmpty()) description = valor.trim()
    }

    val poucaMemoria = reason == "REASON_LOW_MEMORY"
    val descricao = description.lowercase(Locale.ROOT)
    val tipo = when {
        poucaMemoria || PALAVRAS_ENCERRAMENTO_NORMAL.any { descricao.contains(it) } ->
            TipoRegistroErro.ENCERRAMENTO_NORMAL
        reason == "REASON_ANR" -> TipoRegistroErro.SEM_RESPOSTA
        reason == "REASON_CRASH" || reason == "REASON_CRASH_NATIVE" || temStackTrace -> TipoRegistroErro.TRAVAMENTO
        else -> TipoRegistroErro.OUTRO
    }
    return ResumoRegistroErro(
        tipo = tipo,
        excecao = if (tipo == TipoRegistroErro.TRAVAMENTO) excecao else null,
        poucaMemoria = poucaMemoria
    )
}

/**
 * Lê só as primeiras [LINHAS_PARA_RESUMO] linhas do arquivo e classifica. Chamar fora da thread
 * principal. Arquivo ilegível vira registro técnico comum.
 */
fun resumirArquivoDeErro(arquivo: File): ResumoRegistroErro {
    val linhas = runCatching {
        arquivo.bufferedReader(Charsets.UTF_8).useLines { it.take(LINHAS_PARA_RESUMO).toList() }
    }.getOrDefault(emptyList())
    return classificarRegistroErro(linhas)
}

/**
 * Instante gravado no nome do arquivo (lyly_error_<data>T<hora>Z.txt), na hora local em que o
 * CrashLogWriter escreveu. Null se o nome não segue o padrão.
 */
fun instanteDoNomeDoLog(nomeDoArquivo: String, zona: TimeZone = TimeZone.getDefault()): Long? {
    val miolo = nomeDoArquivo.removePrefix("lyly_error_").removeSuffix(".txt")
    return runCatching {
        SimpleDateFormat(FORMATO_DO_NOME, Locale.US).apply {
            timeZone = zona
            isLenient = false
        }.parse(miolo)?.time
    }.getOrNull()
}

/** Em que dia do calendário cai um registro, em relação ao momento atual. */
enum class DiaRelativo { HOJE, ONTEM, OUTRO }

fun diaRelativo(instante: Long, agora: Long, zona: TimeZone = TimeZone.getDefault()): DiaRelativo {
    val dia = diaDe(Calendar.getInstance(zona).apply { timeInMillis = instante })
    val referencia = Calendar.getInstance(zona).apply { timeInMillis = agora }
    if (dia == diaDe(referencia)) return DiaRelativo.HOJE
    referencia.add(Calendar.DAY_OF_YEAR, -1)
    return if (dia == diaDe(referencia)) DiaRelativo.ONTEM else DiaRelativo.OUTRO
}

private fun diaDe(calendario: Calendar): Pair<Int, Int> =
    calendario.get(Calendar.YEAR) to calendario.get(Calendar.DAY_OF_YEAR)
