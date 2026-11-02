package com.jonjonesbr.audiobookgen.domain

/**
 * Progresso que o Python manda numa linha "@progresso pct=18 eta_s=168 falhas=2" (ver ProgressTracker.mensagem_simples).
 * Só os números: a frase no idioma do app é montada pelo Android (service.TextoDeProgresso).
 */
data class ProgressoEstruturado(
    val pct: Int,
    /** Segundos até o fim. Null enquanto o Python ainda não tem base para estimar (eta_s=-1). */
    val etaSegundos: Int?,
    /** Blocos com falha ainda não recuperados. */
    val falhas: Int,
)

/** Faixa do tempo restante: a decisão de qual frase usar fica aqui; o texto fica no Android. */
sealed interface TempoRestante {
    object Calculando : TempoRestante
    object MenosDeUmMinuto : TempoRestante
    data class Minutos(val minutos: Int) : TempoRestante
    data class HorasEMinutos(val horas: Int, val minutos: Int) : TempoRestante
}

/** Pedaço de uma mensagem de progresso: linha estruturada ou texto comum (etapa, "Capítulo X de N"). */
sealed interface ParteDaMensagem {
    data class Texto(val texto: String) : ParteDaMensagem
    data class Progresso(val progresso: ProgressoEstruturado) : ParteDaMensagem
}

object ProgressoDaConversao {

    /** Primeira palavra da linha estruturada. */
    const val PREFIXO = "@progresso"

    /**
     * Separa a mensagem em linhas. A linha "@progresso" vira [ParteDaMensagem.Progresso]; as outras, texto como estão.
     * O capítulo do EPUB/TXT vem na linha de cima da linha estruturada, por isso não basta olhar a mensagem inteira.
     */
    fun partes(mensagem: String): List<ParteDaMensagem> = mensagem.split("\n").map { linha ->
        ler(linha)?.let { ParteDaMensagem.Progresso(it) } ?: ParteDaMensagem.Texto(linha)
    }

    /** Lê uma linha "@progresso ..."; null se não for essa linha ou se faltar o percentual. */
    fun ler(linha: String): ProgressoEstruturado? {
        val palavras = linha.trim().split(' ').filter { it.isNotEmpty() }
        val campos = palavras.drop(1).mapNotNull { palavra ->
            val igual = palavra.indexOf('=')
            if (igual <= 0) null else palavra.substring(0, igual) to palavra.substring(igual + 1)
        }.toMap()
        val pct = campos["pct"]?.toIntOrNull()
        return if (palavras.firstOrNull() == PREFIXO && pct != null) {
            ProgressoEstruturado(
                pct = pct.coerceIn(0, PCT_MAXIMO),
                etaSegundos = campos["eta_s"]?.toIntOrNull()?.takeIf { it >= 0 },
                falhas = campos["falhas"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            )
        } else {
            null
        }
    }

    /** Faixa do tempo restante. Abaixo de um minuto não mostra segundos: a estimativa não é tão precisa. */
    fun tempoRestante(etaSegundos: Int?): TempoRestante = when {
        etaSegundos == null -> TempoRestante.Calculando
        etaSegundos < SEGUNDOS_POR_MINUTO -> TempoRestante.MenosDeUmMinuto
        etaSegundos < SEGUNDOS_POR_HORA -> TempoRestante.Minutos(etaSegundos / SEGUNDOS_POR_MINUTO)
        else -> TempoRestante.HorasEMinutos(
            etaSegundos / SEGUNDOS_POR_HORA,
            (etaSegundos % SEGUNDOS_POR_HORA) / SEGUNDOS_POR_MINUTO,
        )
    }

    private const val PCT_MAXIMO = 100
    private const val SEGUNDOS_POR_MINUTO = 60
    private const val SEGUNDOS_POR_HORA = 3600
}
