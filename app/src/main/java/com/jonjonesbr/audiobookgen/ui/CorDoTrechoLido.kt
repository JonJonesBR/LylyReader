package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.domain.TemaLeitura

// Cor do trecho já lido pela leitura guiada (rb-56). Fica num arquivo próprio, sem Android, de propósito: as funções
// de topo de ParagrafoAdapter.kt rodam o inicializador da classe ParagrafoAdapterKt, que chama Color.parseColor, e
// isso quebra o teste na JVM ("not mocked"). Aqui só há literais Int.

// Trecho já lido em alfa: com o span opaco, a seleção nativa sumia nesse trecho. Com alfa, ela aparece por baixo e o
// trecho lido continua distinto do que falta ler.
private const val ALFA_TRECHO_LIDO = 0xB3

// Máscara do RGB (sem o alfa) e posição do byte de alfa num Int ARGB.
private const val MASCARA_RGB = 0x00FFFFFF
private const val ALFA_MAXIMO = 255
private const val DESLOCAMENTO_ALFA = 24

// Cores do trecho já lido, sem alfa (RGB), na mesma cor de antes de rb-56.
private val COR_TRECHO_LIDO = mapOf(
    TemaLeitura.CLARO  to 0xFFBBA6FF.toInt(),
    TemaLeitura.ESCURO to 0xFF6A4CA0.toInt(),
    TemaLeitura.SEPIA  to 0xFFD8B880.toInt(),
    TemaLeitura.OLED   to 0xFF6A4CA0.toInt(),
)

/** Troca só o alfa de [cor] (0 a 255); o RGB fica igual. Função de topo e pura, para teste na JVM. */
internal fun comAlfa(cor: Int, alfa: Int): Int =
    (cor and MASCARA_RGB) or (alfa.coerceIn(0, ALFA_MAXIMO) shl DESLOCAMENTO_ALFA)

/** Cor do trecho já lido pela guiada no [tema]: a cor do tema com alfa de [ALFA_TRECHO_LIDO]. */
internal fun corDoTrechoLido(tema: TemaLeitura): Int =
    comAlfa(COR_TRECHO_LIDO.getValue(tema), ALFA_TRECHO_LIDO)
