package com.jonjonesbr.audiobookgen

import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.ChapterExportPolicy
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterExportPolicyTest {

    @Test
    fun sanitizarNomeArquivoCapituloRemoveCaracteresInvalidos() {
        val titulo = "A\\B/C:D*E?F\"G<H>I|J"

        assertEquals("ABCDEFGHIJ", ChapterExportPolicy.sanitizarNomeArquivoCapitulo(titulo))
    }

    @Test
    fun sanitizarNomeArquivoCapituloColapsaEspacosMultiplosEFazTrim() {
        val titulo = "  Meu   Capitulo   Um  "

        assertEquals("Meu Capitulo Um", ChapterExportPolicy.sanitizarNomeArquivoCapitulo(titulo))
    }

    @Test
    fun sanitizarNomeArquivoCapituloTruncaTitulosMaioresQue70Caracteres() {
        val titulo = "C".repeat(80)

        assertEquals("C".repeat(70), ChapterExportPolicy.sanitizarNomeArquivoCapitulo(titulo))
    }

    @Test
    fun sanitizarNomeArquivoCapituloRetornaSemTituloQuandoVazioAposLimpeza() {
        val tituloComCaracteresInvalidos = "///\\:::***???"
        val tituloVazio = ""
        val tituloEspacos = "   "

        assertEquals("sem_titulo", ChapterExportPolicy.sanitizarNomeArquivoCapitulo(tituloComCaracteresInvalidos))
        assertEquals("sem_titulo", ChapterExportPolicy.sanitizarNomeArquivoCapitulo(tituloVazio))
        assertEquals("sem_titulo", ChapterExportPolicy.sanitizarNomeArquivoCapitulo(tituloEspacos))
    }

    @Test
    fun montarArquivosCapitulosGeraNomesNumeradosComPrefixoDeDoisDigitosNaOrdemDosCapitulos() {
        val capitulos = listOf(
            Capitulo("Introducao", 0, 0),
            Capitulo("Capitulo Um", 1, 1)
        )
        val paragrafos = listOf(
            Paragrafo("p1", 0, 0),
            Paragrafo("p2", 1, 1)
        )

        val arquivos = ChapterExportPolicy.montarArquivosCapitulos(capitulos, paragrafos)

        assertEquals(2, arquivos.size)
        assertEquals("01 - Introducao.txt", arquivos[0].first)
        assertEquals("02 - Capitulo Um.txt", arquivos[1].first)
    }

    @Test
    fun montarArquivosCapitulosJuntaParagrafosComLinhaEmBranco() {
        val capitulos = listOf(Capitulo("Capitulo", 0, 2))
        val paragrafos = listOf(
            Paragrafo("p1", 0, 0),
            Paragrafo("p2", 1, 0),
            Paragrafo("p3", 2, 0)
        )

        val conteudo = ChapterExportPolicy.montarArquivosCapitulos(capitulos, paragrafos)[0].second

        assertEquals("p1\n\np2\n\np3", conteudo)
    }

    @Test
    fun montarArquivosCapitulosNaoLancaExcecaoComIndicesInconsistentes() {
        // Capitulo com indiceParagrafoInicio > indiceParagrafoFim (dado malformado, ex.: vindo
        // de uma extracao Python com erro) nao deve derrubar a exportacao inteira.
        val capitulos = listOf(Capitulo("Capitulo Estranho", 3, 1))
        val paragrafos = listOf(Paragrafo("p1", 0, 0), Paragrafo("p2", 1, 0))

        val conteudo = ChapterExportPolicy.montarArquivosCapitulos(capitulos, paragrafos)[0].second

        assertEquals("", conteudo)
    }

    @Test
    fun montarArquivosCapitulosComDoisCapitulosUsaParagrafosCorretosSemMisturar() {
        val capitulos = listOf(
            Capitulo("Capitulo Um", 0, 1),
            Capitulo("Capitulo Dois", 2, 3)
        )
        val paragrafos = listOf(
            Paragrafo("p1", 0, 0),
            Paragrafo("p2", 1, 0),
            Paragrafo("p3", 2, 1),
            Paragrafo("p4", 3, 1)
        )

        val arquivos = ChapterExportPolicy.montarArquivosCapitulos(capitulos, paragrafos)

        assertEquals("p1\n\np2", arquivos[0].second)
        assertEquals("p3\n\np4", arquivos[1].second)
    }
}
