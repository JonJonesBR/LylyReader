package com.jonjonesbr.audiobookgen.tts

import java.text.Normalizer

/** Peça do texto: os ids de um trecho ou de uma pontuação. `fimDeFrase` vale para `. ! ? …`. */
internal data class Peca(val ids: List<Long>, val fimDeFrase: Boolean)

/** Bloco pronto para o modelo: `ids` já com os pads 0 nas pontas; `pausaDepoisMs` vai para o silêncio entre blocos. */
internal class Bloco(val ids: LongArray, val pausaDepoisMs: Int)

/**
 * Texto → ids do Paradee-8M. Espelho de `planejamento/ferramentas/paradee_bench.py` (`espeak`, `g2p`, `ids`),
 * em Kotlin puro: o fonemizador (espeak nativo) entra por parâmetro.
 */
internal object ParadeeTokens {

    private const val ESPACO = 16L
    private const val SEPARADORES = ".,!?;:—…"
    private const val PONTOS_FIM = ".!?…"
    private const val LIMITE_PADRAO = 510
    private const val PAUSA_FIM_DE_FRASE_MS = 300
    private const val PAUSA_MEIO_DE_FRASE_MS = 100
    private const val MS_POR_SEGUNDO = 1000

    private val MARCA_IDIOMA = Regex("\\([a-z]{2,3}(?:-[a-z0-9]+)*\\)")
    private val ESPACOS = Regex("\\s+")

    // Mesma ordem do bench: chaves mais longas primeiro (todas têm 3 caracteres).
    private val E2M: List<Pair<String, String>> = listOf(
        "a^ɪ" to "I",
        "a^ʊ" to "W",
        "d^z" to "ʣ",
        "d^ʒ" to "ʤ",
        "e^ɪ" to "A",
        "o^ʊ" to "O",
        "ə^ʊ" to "Q",
        "s^s" to "S",
        "t^s" to "ʦ",
        "t^ʃ" to "ʧ",
        "ɔ^ɪ" to "Y",
    ).sortedByDescending { it.first.length }

    /**
     * `re.split(r'([.,!?;:—…])', texto)` sem os itens vazios ou só de espaço;
     * separadores ficam como itens próprios.
     */
    fun partes(texto: String): List<String> {
        val saida = mutableListOf<String>()
        val atual = StringBuilder()
        for (c in texto) {
            if (c in SEPARADORES) {
                saida.add(atual.toString())
                atual.setLength(0)
                saida.add(c.toString())
            } else {
                atual.append(c)
            }
        }
        saida.add(atual.toString())
        return saida.filter { it.isNotBlank() }
    }

    /** Tira marcas de idioma como `(en)` e junta os espaços (`" ".join(s.split())`). */
    fun limparSaidaEspeak(s: String): String =
        MARCA_IDIOMA.replace(s, "").split(ESPACOS).filter { it.isNotEmpty() }.joinToString(" ")

    /** Tabela E2M do misaki, depois remove todo `^`. */
    fun aplicarE2M(s: String): String {
        var r = s
        for ((de, para) in E2M) r = r.replace(de, para)
        return r.replace("^", "")
    }

    /**
     * Uma peça por item de `partes`. Pontuação entra como está; trecho passa por `fonemizar`, depois por
     * `limparSaidaEspeak`, com um espaço antes se já há saída (como o `g2p` do bench).
     */
    fun pecas(texto: String, fonemizar: (String) -> String): List<Peca> {
        val saida = mutableListOf<Peca>()
        var temSaida = false
        for (item in partes(texto)) {
            val ehPontuacao = item.length == 1 && item[0] in SEPARADORES
            if (ehPontuacao) {
                saida.add(Peca(idsDe(item), fimDeFrase = item[0] in PONTOS_FIM))
            } else {
                val fonemas = (if (temSaida) " " else "") + limparSaidaEspeak(fonemizar(item))
                saida.add(Peca(idsDe(fonemas), fimDeFrase = false))
            }
            temSaida = true
        }
        return saida
    }

    /** Blocos de até `limite` tokens de conteúdo (mais os pads, no máximo `limite + 2`). */
    fun blocos(texto: String, fonemizar: (String) -> String, limite: Int = LIMITE_PADRAO): List<Bloco> {
        // Texto sem letra nem número (só pontuação) não tem o que falar: nenhum bloco.
        if (texto.none { it.isLetterOrDigit() }) return emptyList()

        val unidades = frases(pecas(texto, fonemizar)).flatMap { unidadesDaFrase(it, limite) }
        return empacotar(unidades, limite)
    }

    /** Agrupa as peças em frases, cada uma terminando numa peça `fimDeFrase` (a última pode não terminar). */
    private fun frases(pecas: List<Peca>): List<List<Peca>> {
        val frases = mutableListOf<List<Peca>>()
        var frase = mutableListOf<Peca>()
        for (p in pecas) {
            frase.add(p)
            if (p.fimDeFrase) {
                frases.add(frase)
                frase = mutableListOf()
            }
        }
        if (frase.isNotEmpty()) frases.add(frase)
        return frases
    }

    /** Unidade de empacotamento: a frase inteira se couber; senão, peça a peça (peça longa cortada). */
    private fun unidadesDaFrase(f: List<Peca>, limite: Int): List<Unidade> {
        val terminaFrase = f.last().fimDeFrase
        if (f.sumOf { it.ids.size } <= limite) return listOf(Unidade(f.flatMap { it.ids }, terminaFrase))
        // Corta uma peça maior que `limite` no último espaço antes do limite (o espaço fica no fim do pedaço);
        // sem espaço, corte seco. Função local: não conta no limite de funções do objeto (detekt).
        fun cortar(ids: List<Long>, limite: Int): List<List<Long>> {
            val saida = mutableListOf<List<Long>>()
            var inicio = 0
            while (ids.size - inicio > limite) {
                val janela = ids.subList(inicio, inicio + limite)
                val corte = janela.lastIndexOf(ESPACO)
                val fim = if (corte >= 0) inicio + corte + 1 else inicio + limite
                saida.add(ids.subList(inicio, fim))
                inicio = fim
            }
            saida.add(ids.subList(inicio, ids.size))
            return saida
        }
        val unidades = mutableListOf<Unidade>()
        for ((i, p) in f.withIndex()) {
            val cortes = cortar(p.ids, limite)
            for ((j, trecho) in cortes.withIndex()) {
                val ultimo = i == f.lastIndex && j == cortes.lastIndex
                unidades.add(Unidade(trecho, ultimo && terminaFrase))
            }
        }
        return unidades
    }

    private fun empacotar(unidades: List<Unidade>, limite: Int): List<Bloco> {
        val saida = mutableListOf<Bloco>()
        var acumulado = mutableListOf<Long>()
        var fimAcumulado = false

        fun fechar() {
            val conteudo = acumulado.dropWhile { it == ESPACO }.dropLastWhile { it == ESPACO }
            if (conteudo.isNotEmpty()) {
                val ids = LongArray(conteudo.size + 2)
                conteudo.forEachIndexed { i, id -> ids[i + 1] = id }
                saida.add(Bloco(ids, if (fimAcumulado) PAUSA_FIM_DE_FRASE_MS else PAUSA_MEIO_DE_FRASE_MS))
            }
            acumulado = mutableListOf()
            fimAcumulado = false
        }

        for (u in unidades) {
            if (acumulado.isNotEmpty() && acumulado.size + u.ids.size > limite) fechar()
            acumulado.addAll(u.ids)
            fimAcumulado = u.terminaFrase
        }
        fechar()
        return saida
    }

    /** Junta os áudios, com `pausasMs[i]` de silêncio depois de cada um, exceto o último. */
    fun juntarComPausa(audios: List<FloatArray>, pausasMs: List<Int>, sampleRate: Int): FloatArray {
        val pausas = audios.indices.map { i ->
            if (i < audios.lastIndex) pausasMs.getOrElse(i) { 0 } * sampleRate / MS_POR_SEGUNDO else 0
        }
        val saida = FloatArray(audios.sumOf { it.size } + pausas.sum())
        var pos = 0
        audios.forEachIndexed { i, audio ->
            audio.copyInto(saida, pos)
            pos += audio.size + pausas[i]
        }
        return saida
    }

    private class Unidade(val ids: List<Long>, val terminaFrase: Boolean)

    private fun idsDe(s: String): List<Long> {
        val normalizado = Normalizer.normalize(aplicarE2M(s), Normalizer.Form.NFD)
        val ids = mutableListOf<Long>()
        for (cp in normalizado.codePoints().toArray()) {
            ParadeeVocab.MAPA[cp]?.let { ids.add(it) }
        }
        return ids
    }

}
