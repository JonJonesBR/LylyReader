package com.jonjonesbr.audiobookgen.util

/**
 * Presets curados de velocidade da leitura guiada — compartilhados entre o ajuste ao vivo
 * (barra flutuante, [com.jonjonesbr.audiobookgen.ui.GuidedReadingBarController]) e a
 * configuração prévia por livro (antes de começar a ouvir).
 */
@Suppress("MagicNumber") // presets curados de velocidade, constantes nomeadas seriam ruído
val VELOCIDADES_GUIADAS = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

private const val LIMIAR_SNAP_PADRAO = 0.04f

/**
 * "Trava" [valor] no preset mais próximo em [presets] quando a distância for menor ou igual a
 * [limiar] — usado pelo slider de velocidade (home, pré-visualização, leitura guiada) pra dar a
 * sensação de ímã perto dos presets sem impedir valores livres mais longe deles.
 */
fun aplicarSnapVelocidade(
    valor: Float,
    presets: FloatArray = VELOCIDADES_GUIADAS,
    limiar: Float = LIMIAR_SNAP_PADRAO
): Float {
    val maisProximo = presets.minByOrNull { kotlin.math.abs(it - valor) } ?: return valor
    return if (kotlin.math.abs(maisProximo - valor) <= limiar) maisProximo else valor
}
