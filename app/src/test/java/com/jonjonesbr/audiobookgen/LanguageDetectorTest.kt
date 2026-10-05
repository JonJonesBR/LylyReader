package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.util.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test fun detectaPortugues() {
        val texto = """
            O Brasil é um país de dimensões continentais. A cultura brasileira é muito rica
            e diversa, com influências de diversos povos. Este é um exemplo de texto em
            português para testar a detecção de idioma. Como podemos ver, as palavras comuns
            da língua portuguesa são facilmente identificáveis pelo detector.
        """.trimIndent()
        assertEquals("pt-BR", LanguageDetector.detectLanguage(texto))
    }

    @Test fun detectaIngles() {
        val texto = """
            The quick brown fox jumps over the lazy dog. This is an example of English text
            that should be detected by the language detector. It contains common English words
            like the, is, and, in, to, of, for, and that. The algorithm scores based on
            frequent words from each language.
        """.trimIndent()
        assertEquals("en-US", LanguageDetector.detectLanguage(texto))
    }

    @Test fun detectaEspanhol() {
        val texto = """
            El español es una lengua romance muy extendida por todo el mundo. La cultura
            hispana tiene una gran influencia en el arte y la literatura. Este es un ejemplo
            de texto en español para probar el detector de idiomas del sistema.
        """.trimIndent()
        assertEquals("es-ES", LanguageDetector.detectLanguage(texto))
    }

    @Test fun textoVazioRetornaPadrao() {
        assertEquals("pt-BR", LanguageDetector.detectLanguage(""))
    }

    @Test fun textoCurtoRetornaPadrao() {
        assertEquals("pt-BR", LanguageDetector.detectLanguage("ab cd"))
    }

    @Test fun detectaInglesEmTextoDominadoPorPalavrasCurtas() {
        // Regressao: o filtro antigo exigia length >= 3, descartando "a", "is", "in", "to", "of"
        // (todas com 1-2 letras, mas presentes em EN_WORDS) — com esse filtro, essas palavras
        // nunca contavam ponto nenhum e o texto caia no fallback padrao "pt-BR", errado pra um
        // texto em ingles.
        val texto = "a is in to of a is in to of"
        assertEquals("en-US", LanguageDetector.detectLanguage(texto))
    }

    @Test fun detectaEspanholEmTextoDominadoPorPalavrasCurtas() {
        val texto = "el la y en un el la y en un el la y en un"
        assertEquals("es-ES", LanguageDetector.detectLanguage(texto))
    }

    @Test fun detectaPortuguesQuandoSinalDependeDePalavraAcentuadaIsolada() {
        // Regressao: \W em Java/Kotlin regex e ASCII-only por padrao (sem a flag (?U)), entao
        // "é" (só a letra acentuada, sem nenhum caractere ASCII) virava um token vazio na
        // quebra por regex e sumia da contagem inteiro — zerando o placar de pt-BR aqui e
        // fazendo "the" (en) vencer por 2 a 0 mesmo com "é" aparecendo 4 vezes no texto.
        val texto = "é é é é the the"
        assertEquals("pt-BR", LanguageDetector.detectLanguage(texto))
    }
}
