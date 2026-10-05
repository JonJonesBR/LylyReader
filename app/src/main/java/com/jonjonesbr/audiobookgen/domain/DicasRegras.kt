package com.jonjonesbr.audiobookgen.domain

/** Telas que mostram dicas no momento certo (Fase J). */
enum class TelaDica { BIBLIOTECA, LIVRO, DOWNLOADS, LEITOR }

/** Cada dica aparece uma vez. A ordem de declaração é a ordem em que aparecem dentro da mesma tela. */
enum class Dica(val id: String, val tela: TelaDica) {
    BIB_ADICIONAR("bib_adicionar", TelaDica.BIBLIOTECA),
    BIB_TEMA("bib_tema", TelaDica.BIBLIOTECA),
    LIVRO_GERAR("livro_gerar", TelaDica.LIVRO),
    LIVRO_VOZ("livro_voz", TelaDica.LIVRO),
    DOWNLOADS_VOZES("downloads_vozes", TelaDica.DOWNLOADS),
    LEITOR_GUIADA("leitor_guiada", TelaDica.LEITOR),
    LEITOR_VOZ_E_MOTOR("leitor_voz_e_motor", TelaDica.LEITOR)
}

object DicasRegras {

    /** Nunca durante o tour, antes da escolha de voz ou sobre o diálogo de novidades; nunca repetir uma dica já vista. */
    fun podeMostrar(
        dica: Dica,
        vistas: Set<String>,
        tourConcluido: Boolean,
        vozConcluida: Boolean,
        novidadesPendentes: Boolean
    ): Boolean = tourConcluido && vozConcluida && !novidadesPendentes && dica.id !in vistas

    /** A próxima dica da [tela] (no máximo uma), ou null se não há o que mostrar agora. */
    fun proxima(
        tela: TelaDica,
        vistas: Set<String>,
        tourConcluido: Boolean,
        vozConcluida: Boolean,
        novidadesPendentes: Boolean
    ): Dica? = Dica.values().firstOrNull {
        it.tela == tela && podeMostrar(it, vistas, tourConcluido, vozConcluida, novidadesPendentes)
    }
}
