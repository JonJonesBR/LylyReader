package com.jonjonesbr.audiobookgen.domain

/**
 * Detecta o parágrafo onde a leitura "de verdade" começa, pulando capa/sumário/folha de rosto:
 * prefere o **prefácio**; senão o **capítulo 1** (PT/EN, incluindo numeral romano "I"); senão 0.
 *
 * Extraído do ReaderActivity (Fase 1) para ficar puro e testável.
 */
object ReadingStartDetector {

    /**
     * Parágrafo onde continuar a leitura guiada: o salvo, se ainda existe no texto; senão o início detectado.
     * É a regra do "Continuar lendo" (EXTRA_CONTINUAR_GUIADA) e da fila "Ler tudo" ao abrir o próximo livro.
     */
    fun indiceParaContinuar(salvo: Int, total: Int, capitulos: List<Capitulo>): Int =
        if (salvo in 0 until total) salvo else indiceInicio(capitulos)

    /**
     * Como [indiceParaContinuar], para os livros da fila "Ler tudo". Um livro que já foi até o último parágrafo
     * (a posição final gravada ao concluir) recomeça do início, em vez de tocar só o último parágrafo e passar adiante.
     */
    fun indiceParaFilaSequencia(salvo: Int, total: Int, capitulos: List<Capitulo>): Int =
        indiceParaContinuar(salvo.takeIf { it < total - 1 } ?: -1, total, capitulos)

    fun indiceInicio(capitulos: List<Capitulo>): Int {
        if (capitulos.isEmpty()) return 0

        // 1. Prefácio
        val prefacio = capitulos.firstOrNull { cap ->
            val t = cap.titulo.lowercase().trim()
            t.contains("prefácio") || t.contains("prefacio") || t.contains("preface")
        }
        // 2. Capítulo 1 / primeiro / I (PT/EN) — só procurado se não houver prefácio
        val primeiro = if (prefacio == null) {
            capitulos.firstOrNull { cap ->
                val t = cap.titulo.lowercase().trim()
                t.contains("capítulo 1") || t.contains("capitulo 1") || t.contains("chapter 1") ||
                    t.contains("capítulo primeiro") || t.contains("capitulo primeiro") || t.contains("chapter one") ||
                    t.contains("capítulo i") || t.contains("capitulo i") || t.startsWith("cap. 1") ||
                    t.startsWith("cap.1") || t == "1" || t.startsWith("1 ") || t.startsWith("01 ") ||
                    t == "01" || t.startsWith("i ") || t == "i"
            }
        } else {
            null
        }
        val alvo = prefacio ?: primeiro
        return alvo?.indiceParagrafoInicio?.coerceAtLeast(0) ?: 0
    }
}
