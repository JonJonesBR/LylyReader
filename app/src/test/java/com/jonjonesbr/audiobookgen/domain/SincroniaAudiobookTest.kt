package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SincroniaAudiobookTest {

    /** Dois capítulos: o 1º com 2 parágrafos curtos, o 2º com 4 parágrafos de 100 caracteres. */
    private fun texto(): TextoExtraido {
        val textos = listOf("a".repeat(10), "b".repeat(10), "c".repeat(100), "d".repeat(100), "e".repeat(100), "f".repeat(100))
        var offset = 0
        val paragrafos = textos.mapIndexed { i, t ->
            Paragrafo(t, offset, if (i < 2) 0 else 1).also { offset += t.length + 2 }
        }
        return TextoExtraido(
            capitulos = listOf(Capitulo("Um", 0, 1), Capitulo("Dois", 2, 5)),
            paragrafos = paragrafos,
            totalChars = offset,
        )
    }

    @Test
    fun usaAMarcaDoCapituloEAProporcaoSoDentroDele() {
        // O capítulo 1 é curto no texto mas longo no áudio (ex.: abertura lenta): sem as marcas a proporção erraria.
        val audio = listOf(CapituloAudio("Um", 0, 60_000), CapituloAudio("Dois", 60_000, 100_000))
        assertEquals(0, paragrafoNoAudiobook(10_000, 100_000, audio, texto()))
        assertEquals(2, paragrafoNoAudiobook(61_000, 100_000, audio, texto()))
        assertEquals(4, paragrafoNoAudiobook(82_000, 100_000, audio, texto()))
        assertEquals(5, paragrafoNoAudiobook(99_999, 100_000, audio, texto()))
    }

    @Test
    fun semMarcasCompativeisUsaAProporcaoDoLivro() {
        val t = texto()
        assertEquals(0, paragrafoNoAudiobook(0, 100_000, emptyList(), t))
        assertEquals(5, paragrafoNoAudiobook(100_000, 100_000, emptyList(), t))
        // Quantidade de capítulos diferente: ignora as marcas.
        assertEquals(0, paragrafoNoAudiobook(0, 100_000, listOf(CapituloAudio("x", 0, 1), CapituloAudio("y", 1, 2), CapituloAudio("z", 2, 3)), t))
    }

    @Test
    fun semTextoOuSemDuracaoDevolveMenosUm() {
        assertEquals(-1, paragrafoNoAudiobook(0, 0, emptyList(), texto()))
        assertEquals(-1, paragrafoNoAudiobook(0, 100, emptyList(), TextoExtraido(emptyList(), emptyList(), 0)))
    }

    private fun livro(vararg paragrafos: String): TextoExtraido {
        var offset = 0
        val lista = paragrafos.map { t -> Paragrafo(t, offset, 0).also { offset += t.length + 2 } }
        return TextoExtraido(listOf(Capitulo("Único", 0, lista.lastIndex)), lista, offset)
    }

    /** Caractere global onde [trecho] começa no parágrafo [indice] (o começo dele, se [trecho] for vazio). */
    private fun caractereEm(texto: TextoExtraido, indice: Int, trecho: String = ""): Int =
        texto.paragrafos[indice].charOffset + texto.paragrafos[indice].texto.indexOf(trecho)

    @Test
    fun ancorasAchamCadaTrechoPeloComecoDoTextoSempreParaFrente() {
        val t = livro(
            "Capítulo um.",
            "Era uma vez, numa terra distante, um rei. Ele tinha três filhos!",
            "O mais novo — Pedro — partiu cedo.",
        )
        val trechos = listOf(
            // Trecho curto (título de duas palavras) também é ancorado.
            TrechoAudio(0, "Capítulo um."),
            TrechoAudio(2_000, "Era uma vez, numa terra distante, um rei."),
            TrechoAudio(5_000, "Ele tinha três filhos!"),
            TrechoAudio(7_000, "O mais novo - Pedro - partiu cedo."),
        )
        val ancoras = ancorasDeSincronia(trechos, t)
        assertEquals(
            listOf(
                0L to caractereEm(t, 0),
                2_000L to caractereEm(t, 1),
                5_000L to caractereEm(t, 1, "Ele"),
                7_000L to caractereEm(t, 2),
            ),
            ancoras,
        )
        assertEquals(1, paragrafoNoAudiobook(6_000, 10_000, emptyList(), t, ancoras))
        assertEquals(2, paragrafoNoAudiobook(9_999, 10_000, emptyList(), t, ancoras))
    }

    @Test
    fun trechoComVariosParagrafosAvancaPelaProporcaoAteOProximo() {
        // Um trecho de áudio só (Edge e outros motores juntam vários parágrafos por trecho): antes o destaque
        // ficava parado no primeiro parágrafo até o fim do trecho.
        val t = livro("Um Apólogo Machado de Assis", "a".repeat(98), "b".repeat(98), "c".repeat(98))
        val ancoras = listOf(0L to caractereEm(t, 0), 8_000L to caractereEm(t, 3))
        assertEquals(0, paragrafoNoAudiobook(500, 10_000, emptyList(), t, ancoras))
        assertEquals(1, paragrafoNoAudiobook(3_000, 10_000, emptyList(), t, ancoras))
        assertEquals(2, paragrafoNoAudiobook(6_500, 10_000, emptyList(), t, ancoras))
        assertEquals(3, paragrafoNoAudiobook(8_000, 10_000, emptyList(), t, ancoras))
        // Depois da última âncora, segue até o fim do texto junto com o fim do áudio.
        val soUma = listOf(0L to caractereEm(t, 0))
        assertEquals(3, paragrafoNoAudiobook(9_900, 10_000, emptyList(), t, soUma))
    }

    @Test
    fun trechoQueNaoEstaNoTextoFicaSemAncoraESemVoltarAtras() {
        val t = livro("Primeiro parágrafo aqui.", "Segundo parágrafo aqui.", "Primeiro parágrafo aqui de novo.")
        val trechos = listOf(
            TrechoAudio(0, "Segundo parágrafo aqui fica."),
            TrechoAudio(1_000, "Texto que a conversão normalizou diferente."),
            TrechoAudio(2_000, "Primeiro parágrafo aqui de novo."),
        )
        // O 3º trecho casa com o parágrafo 2 (para frente do cursor), não com o 0.
        assertEquals(
            listOf(0L to caractereEm(t, 1), 2_000L to caractereEm(t, 2)),
            ancorasDeSincronia(trechos, t),
        )
    }

    @Test
    fun trechoQueComecaNoMeioDoParagrafoAvancaNoParagrafoAntesDoProximoTrecho() {
        // O 2º trecho começa no meio do 2º parágrafo. Com âncoras só no começo de parágrafo, o destaque ficava no
        // 1º parágrafo até 5 s e pulava para o 2º de uma vez; agora ele já está no 2º antes do trecho seguinte.
        val t = livro(
            "Era uma vez um rei.",
            "O reino inteiro esperava havia muitos anos pelo herdeiro que nunca chegava ao castelo real.",
            "Certa manhã um mensageiro chegou trazendo notícias do mar distante.",
        )
        val trechos = listOf(
            TrechoAudio(0, "Era uma vez um rei. O reino inteiro esperava havia muitos anos"),
            TrechoAudio(5_000, "pelo herdeiro que nunca chegava ao castelo real."),
            TrechoAudio(10_000, "Certa manhã um mensageiro chegou trazendo notícias"),
        )
        val ancoras = ancorasDeSincronia(trechos, t)
        assertEquals(
            listOf(0L to caractereEm(t, 0), 5_000L to caractereEm(t, 1, "pelo"), 10_000L to caractereEm(t, 2)),
            ancoras,
        )
        assertEquals(1, paragrafoNoAudiobook(2_500, 15_000, emptyList(), t, ancoras))
        assertEquals(1, paragrafoNoAudiobook(4_999, 15_000, emptyList(), t, ancoras))
        assertEquals(1, paragrafoNoAudiobook(7_000, 15_000, emptyList(), t, ancoras))
        assertEquals(2, paragrafoNoAudiobook(10_000, 15_000, emptyList(), t, ancoras))
        assertEquals(2, paragrafoNoAudiobook(14_000, 15_000, emptyList(), t, ancoras))
    }

    @Test
    fun chaveQueAtravessaParagrafoContinuaAncorada() {
        // Trecho começa com um título de 2 palavras (parágrafo próprio) seguido do texto: a chave cruza a fronteira.
        val t = livro("Capítulo Dois", "Naquele dia a chuva não parou de cair sobre a cidade.")
        assertEquals(
            listOf(0L to 0),
            ancorasDeSincronia(listOf(TrechoAudio(0, "Capítulo Dois Naquele dia a chuva não parou")), t),
        )
        assertEquals(
            listOf(0L to 0),
            ancorasDeSincronia(listOf(TrechoAudio(0, "Capítulo Dois Naquele dia")), t),
        )
    }

    @Test
    fun acentoECaixaNaoImpedemOCasamento() {
        val t = livro("Era uma vez um Coração valente que nunca desistia.")
        assertEquals(
            listOf(0L to 0),
            ancorasDeSincronia(listOf(TrechoAudio(0, "ERA UMA VEZ UM CORAÇÃO VALENTE QUE NUNCA")), t),
        )
        assertEquals(
            listOf(0L to 0),
            ancorasDeSincronia(listOf(TrechoAudio(0, "era uma vez um coracao valente que nunca")), t),
        )
    }

    @Test
    fun ultimaPalavraCortadaNaoEntraNaChave() {
        // O texto gravado do trecho é cortado em 80 caracteres: a palavra cortada no fim não pode impedir o casamento.
        val paragrafo = "Bia saiu cedo, Pedro ficou dormindo e o resto da casa só acordou bem tarde, " +
            "já perto do almoço."
        val t = livro(paragrafo)
        val gravado = paragrafo.take(80)
        assertEquals("p", gravado.substringAfterLast(' '))
        // Trecho curto (Bia saiu): a chave usa as palavras que há, sem descartar nenhuma.
        assertEquals(listOf(4_000L to 0), ancorasDeSincronia(listOf(TrechoAudio(4_000, gravado)), t))
        assertEquals(listOf(1_000L to 0), ancorasDeSincronia(listOf(TrechoAudio(1_000, "Bia saiu")), t))
    }

    @Test
    fun livroComAcentoSeparadoOuLigaduraTambemCasa() {
        // Alguns EPUB/PDF guardam "ç" como "c" + cedilha (NFD) ou "fi" como uma letra só ("ﬁ").
        val nfd = java.text.Normalizer.normalize("O coração da ação não para.", java.text.Normalizer.Form.NFD)
        val t = livro(nfd, "Um ﬁel amigo chegou ontem à noite.")
        assertEquals(
            listOf(0L to 0, 3_000L to caractereEm(t, 1)),
            ancorasDeSincronia(
                listOf(TrechoAudio(0, "O coração da ação não para."), TrechoAudio(3_000, "Um fiel amigo chegou ontem.")),
                t,
            ),
        )
    }

    @Test
    fun trechosDeJsonIgnoraLixo() {
        assertEquals(
            listOf(TrechoAudio(10, "Olá mundo")),
            trechosDeJson("""[{"inicio_ms":10,"texto":"Olá mundo"},{"inicio_ms":-1,"texto":"x"},{"texto":"sem início"}]"""),
        )
        assertEquals(emptyList<TrechoAudio>(), trechosDeJson("não é json"))
        assertEquals(emptyList<TrechoAudio>(), trechosDeJson(null))
    }
}
