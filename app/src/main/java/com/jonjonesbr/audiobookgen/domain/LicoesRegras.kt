package com.jonjonesbr.audiobookgen.domain

/** Fatos reais que provam que a pessoa fez algo no app (Fase J). Gravados por [com.jonjonesbr.audiobookgen.data.AppPrefs]. */
enum class EventoAprendizado(val id: String) {
    LIVRO_IMPORTADO("livro_importado"),
    LEITURA_GUIADA_INICIADA("leitura_guiada_iniciada"),
    AUDIOBOOK_GERADO("audiobook_gerado"),
    VOZ_DO_LIVRO_TROCADA("voz_do_livro_trocada"),
    DOWNLOADS_ABERTO("downloads_aberto")
}

/** Lições da central "Aprender o app", na ordem em que são mostradas; cada uma termina com um evento. */
enum class Licao(val evento: EventoAprendizado) {
    ADICIONAR(EventoAprendizado.LIVRO_IMPORTADO),
    GUIADA(EventoAprendizado.LEITURA_GUIADA_INICIADA),
    GERAR(EventoAprendizado.AUDIOBOOK_GERADO),
    VOZ(EventoAprendizado.VOZ_DO_LIVRO_TROCADA),
    DOWNLOADS(EventoAprendizado.DOWNLOADS_ABERTO)
}

data class EstadoLicao(val licao: Licao, val concluida: Boolean)

object LicoesRegras {
    /** Estado de todas as lições a partir dos ids de evento gravados (ids desconhecidos são ignorados). */
    fun estado(eventos: Set<String>): List<EstadoLicao> =
        Licao.values().map { EstadoLicao(it, it.evento.id in eventos) }

    fun concluidas(eventos: Set<String>): Int = estado(eventos).count { it.concluida }

    fun proximaPendente(eventos: Set<String>): Licao? = estado(eventos).firstOrNull { !it.concluida }?.licao
}
