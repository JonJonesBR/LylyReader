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

    @Test
    fun ancorasAchamCadaTrechoPeloComecoDoTextoSempreParaFrente() {
        val t = livro(
            "Capítulo um.",
            "Era uma vez, numa terra distante, um rei. Ele tinha três filhos!",
            "O mais novo — Pedro — partiu cedo.",
        )
        val trechos = listOf(
            TrechoAudio(0, "Capítulo um."),
            TrechoAudio(2_000, "Era uma vez, numa terra distante, um rei."),
            TrechoAudio(5_000, "Ele tinha três filhos!"),
            TrechoAudio(7_000, "O mais novo - Pedro - partiu cedo."),
        )
        val ancoras = ancorasDeSincronia(trechos, t)
        assertEquals(listOf(0L to 0, 2_000L to 1, 5_000L to 1, 7_000L to 2), ancoras)
        assertEquals(1, paragrafoNoAudiobook(6_000, 10_000, emptyList(), t, ancoras))
        assertEquals(2, paragrafoNoAudiobook(9_999, 10_000, emptyList(), t, ancoras))
    }

    @Test
    fun trechoComVariosParagrafosAvancaPelaProporcaoAteOProximo() {
        // Um trecho de áudio só (Edge e outros motores juntam vários parágrafos por trecho): antes o destaque
        // ficava parado no primeiro parágrafo até o fim do trecho.
        val t = livro("Um Apólogo Machado de Assis", "a".repeat(98), "b".repeat(98), "c".repeat(98))
        val ancoras = listOf(0L to 0, 8_000L to 3)
        assertEquals(0, paragrafoNoAudiobook(500, 10_000, emptyList(), t, ancoras))
        assertEquals(1, paragrafoNoAudiobook(3_000, 10_000, emptyList(), t, ancoras))
        assertEquals(2, paragrafoNoAudiobook(6_500, 10_000, emptyList(), t, ancoras))
        assertEquals(3, paragrafoNoAudiobook(8_000, 10_000, emptyList(), t, ancoras))
        // Depois da última âncora, segue até o fim do texto junto com o fim do áudio.
        val soUma = listOf(0L to 0)
        assertEquals(3, paragrafoNoAudiobook(9_900, 10_000, emptyList(), t, soUma))
    }

    @Test
    fun trechoQueNaoEstaNoTextoFicaSemAncoraESemVoltarAtras() {
        val t = livro("Primeiro parágrafo aqui.", "Segundo parágrafo aqui.", "Primeiro parágrafo aqui de novo.")
        val trechos = listOf(
            TrechoAudio(0, "Segundo parágrafo aqui."),
            TrechoAudio(1_000, "Texto que a conversão normalizou diferente."),
            TrechoAudio(2_000, "Primeiro parágrafo aqui de novo."),
        )
        // O 3º trecho casa com o parágrafo 2 (para frente do cursor), não com o 0.
        assertEquals(listOf(0L to 1, 2_000L to 2), ancorasDeSincronia(trechos, t))
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
