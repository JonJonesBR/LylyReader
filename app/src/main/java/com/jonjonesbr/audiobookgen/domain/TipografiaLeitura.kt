package com.jonjonesbr.audiobookgen.domain

/**
 * Fonte do texto no leitor. Persistida pelo nome (ver AppPrefs.readerFonteTipo): não renomear os valores.
 * [DISLEXIA] é a OpenDyslexic, pensada para quem tem dislexia; a serifada é a Literata.
 */
enum class FonteLeitura {
    PADRAO, SERIFADA, DISLEXIA;

    companion object {
        /** Lê o nome gravado; valor ausente ou desconhecido volta à padrão, [PADRAO]. */
        fun porNome(nome: String?): FonteLeitura = entries.firstOrNull { it.name == nome } ?: PADRAO

        /**
         * Sem a preferência nova gravada, vale a antiga (fonte serifada ligada ou não): quem já escolheu a serifada
         * continua com ela. Quem nunca mexeu na Aparência fica com a padrão.
         */
        fun derivar(nomeGravado: String?, serifadaAntiga: Boolean): FonteLeitura = when {
            nomeGravado != null -> porNome(nomeGravado)
            serifadaAntiga -> SERIFADA
            else -> PADRAO
        }
    }
}

/** Níveis de espaço entre letras e entre parágrafos: 0 normal, 1 médio, 2 largo. Persistidos como número. */
const val ESPACO_NIVEL_PADRAO = 0
const val ESPACO_NIVEL_MAX = 2

/**
 * Espaço extra entre letras, em em (TextView.letterSpacing), por nível. Fora do intervalo,
 * vale o nível mais próximo.
 */
fun espacoLetrasEm(nivel: Int): Float = when (nivel.coerceIn(ESPACO_NIVEL_PADRAO, ESPACO_NIVEL_MAX)) {
    0 -> 0f
    1 -> 0.05f
    else -> 0.12f
}

/**
 * Padding vertical de cada parágrafo, em dp, por nível. O normal é o padding original do item
 * (item_paragrafo.xml). Fora do intervalo, vale o nível mais próximo.
 */
fun espacoParagrafosDp(nivel: Int): Int = when (nivel.coerceIn(ESPACO_NIVEL_PADRAO, ESPACO_NIVEL_MAX)) {
    0 -> 6
    1 -> 12
    else -> 20
}

/**
 * Padding vertical do campo de edição, em dp: o espaço entre parágrafos do nível mais 6 dp. No normal dá 12 dp,
 * o padding que o campo já tinha (item_paragrafo_edit.xml), e o extra de cada nível vale também na edição.
 */
fun espacoParagrafosEdicaoDp(nivel: Int): Int = espacoParagrafosDp(nivel) + 6

/**
 * Tipografia do texto do leitor, agrupada para o adapter: uma comparação de igualdade diz se a aparência mudou,
 * sem um parâmetro novo a cada opção.
 */
data class TipografiaLeitor(
    val fonte: FonteLeitura = FonteLeitura.PADRAO,
    val espacoLetras: Int = ESPACO_NIVEL_PADRAO,
    val espacoParagrafos: Int = ESPACO_NIVEL_PADRAO,
)
