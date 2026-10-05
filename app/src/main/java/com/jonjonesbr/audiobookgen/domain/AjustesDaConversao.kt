package com.jonjonesbr.audiobookgen.domain

import kotlin.math.roundToInt

/** Ajustes efetivos de uma conversão (voz, motor, ritmo em %, e tom/agudos só quando o livro tem perfil próprio). */
data class AjustesDaConversao(
    val voz: String,
    val motor: String,
    val ritmo: Int,
    val tomMeiosTons: Int? = null,
    val agudosDb: Int? = null
)

/**
 * Monta os ajustes de uma conversão **iniciada a partir de um livro**: o que foi escolhido na hora (painel Gerar audiobook)
 * vale mais que o perfil do livro (voz/velocidade da leitura guiada), que vale mais que as preferências globais.
 * Antes disso, gerar a partir do leitor ignorava a voz, a velocidade e o tom do livro e usava só os globais.
 */
object AjustesDaConversaoRegras {
    fun montar(
        escolhaVoz: String?,
        escolhaMotor: String?,
        escolhaRitmo: Int?,
        livroVoz: String?,
        livroMotor: String?,
        livroVelocidade: Float?,
        perfilAudio: PerfilAudioLivro?,
        globalVoz: String?,
        globalMotor: String,
        globalRitmo: Int,
        vozPadraoDoMotor: (String) -> String
    ): AjustesDaConversao {
        val motor = escolhaMotor ?: livroMotor ?: globalMotor
        // Voz do livro só vale junto com o motor dele; se a escolha manual trocou o motor, não misturar.
        val voz = escolhaVoz
            ?: livroVoz?.takeIf { livroMotor == null || motor == livroMotor }
            ?: globalVoz?.takeIf { motor == globalMotor }
            ?: vozPadraoDoMotor(motor)
        val ritmo = (escolhaRitmo ?: livroVelocidade?.let { (it * PCT_POR_MULTIPLICADOR).roundToInt() } ?: globalRitmo)
            .coerceIn(RITMO_CONVERSAO_MIN, RITMO_CONVERSAO_MAX)
        return AjustesDaConversao(voz, motor, ritmo, perfilAudio?.tomMeiosTons, perfilAudio?.agudosDb)
    }

    private const val PCT_POR_MULTIPLICADOR = 100f
}
