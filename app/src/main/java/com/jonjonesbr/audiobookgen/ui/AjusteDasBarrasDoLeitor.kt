package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.ModoDaBarra

/**
 * Liga os dois grupos de barras da folha Aparência (rb-66, passo 6): as barras de cima e o painel de baixo da guiada.
 * Cada escolha grava o nome do modo em [AppPrefs] na hora e troca a explicação embaixo do grupo. Quem aplica no
 * Leitor é o coordenador, quando a janela recupera o foco ao fechar a folha; por isso não há callback aqui.
 */
internal fun configurarBarrasDoLeitor(view: View, prefs: AppPrefs) {
    val cima = GrupoDeBarras(
        grupo = view.findViewById(R.id.grupoBarrasCima),
        dica = view.findViewById(R.id.tvBarrasCimaDica),
        botoes = mapOf(
            ModoDaBarra.SEMPRE to R.id.btnBarrasCimaSempre,
            ModoDaBarra.TEMPO to R.id.btnBarrasCimaTempo,
            ModoDaBarra.MANUAL to R.id.btnBarrasCimaMao,
        ),
        dicas = mapOf(
            ModoDaBarra.SEMPRE to R.string.barras_cima_dica_sempre,
            ModoDaBarra.TEMPO to R.string.barras_cima_dica_tempo,
            ModoDaBarra.MANUAL to R.string.barras_cima_dica_mao,
        ),
    )
    configurarGrupoDeBarras(cima, ModoDaBarra.de(prefs.readerBarraDeCima)) { prefs.readerBarraDeCima = it.name }

    val baixo = GrupoDeBarras(
        grupo = view.findViewById(R.id.grupoBarrasBaixo),
        dica = view.findViewById(R.id.tvBarrasBaixoDica),
        botoes = mapOf(
            ModoDaBarra.SEMPRE to R.id.btnBarrasBaixoSempre,
            ModoDaBarra.TEMPO to R.id.btnBarrasBaixoTempo,
            ModoDaBarra.MANUAL to R.id.btnBarrasBaixoMao,
        ),
        dicas = mapOf(
            ModoDaBarra.SEMPRE to R.string.barras_baixo_dica_sempre,
            ModoDaBarra.TEMPO to R.string.barras_baixo_dica_tempo,
            ModoDaBarra.MANUAL to R.string.barras_baixo_dica_mao,
        ),
    )
    configurarGrupoDeBarras(baixo, ModoDaBarra.de(prefs.readerBarraDeBaixo)) { prefs.readerBarraDeBaixo = it.name }
}

/** Um grupo de três botões (Sempre, Recolher, À mão) com a explicação que muda com a escolha. */
private class GrupoDeBarras(
    val grupo: MaterialButtonToggleGroup,
    val dica: TextView,
    val botoes: Map<ModoDaBarra, Int>,
    val dicas: Map<ModoDaBarra, Int>,
)

/** Marca o modo salvo, mostra a explicação dele e, a cada escolha, grava e troca a explicação. */
private fun configurarGrupoDeBarras(grupoDeBarras: GrupoDeBarras, salvo: ModoDaBarra, gravar: (ModoDaBarra) -> Unit) {
    fun mostrarDica(modo: ModoDaBarra) = grupoDeBarras.dica.setText(grupoDeBarras.dicas.getValue(modo))

    // Marca antes de ligar o ouvinte, como nos outros grupos da folha, para não gravar nada ao abrir.
    grupoDeBarras.grupo.check(grupoDeBarras.botoes.getValue(salvo))
    mostrarDica(salvo)
    grupoDeBarras.grupo.addOnButtonCheckedListener { _, id, marcado ->
        if (marcado) {
            grupoDeBarras.botoes.entries.firstOrNull { it.value == id }?.key?.let {
                gravar(it)
                mostrarDica(it)
            }
        }
    }
}
