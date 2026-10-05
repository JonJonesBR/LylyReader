package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BibliotecaTest {

    private fun livro(
        id: String,
        titulo: String,
        autor: String? = null,
        origem: OrigemLivro = OrigemLivro.ARQUIVO,
        adicionadoEm: Long = 0L,
        abertoEm: Long? = null
    ) = LivroBiblioteca(id, titulo, autor, origem, null, "epub", "$titulo.epub", 100L, adicionadoEm, abertoEm, false)

    private val semProgresso: (LivroBiblioteca) -> Float? = { null }
    private val semAudio: (LivroBiblioteca) -> Boolean = { false }

    @Test
    fun `json ida e volta preserva todos os campos`() {
        val original = LivroBiblioteca(
            "abc123", "Dom Casmurro", "Machado de Assis", OrigemLivro.GUTENBERG, "gutenberg-55752",
            "epub", "Dom Casmurro.epub", 230_000L, 10L, 20L, true
        )
        assertEquals(original, LivroBiblioteca.fromJson(original.toJson()))
    }

    @Test
    fun `json sem campos opcionais usa os padroes`() {
        val l = LivroBiblioteca.fromJson("""{"id":"x1","nomeArquivo":"entrada_99_O_Alienista.epub"}""")!!
        assertEquals("O Alienista", l.titulo)
        assertNull(l.autor)
        assertEquals(OrigemLivro.ARQUIVO, l.origem)
        assertEquals("epub", l.formato)
        assertNull(l.abertoEm)
        assertEquals(false, l.temCapa)
    }

    @Test
    fun `origem desconhecida vira arquivo`() {
        val l = LivroBiblioteca.fromJson("""{"id":"x1","nomeArquivo":"a.txt","origem":"NAO_EXISTE"}""")!!
        assertEquals(OrigemLivro.ARQUIVO, l.origem)
    }

    @Test
    fun `json invalido ou sem id ou sem arquivo devolve nulo`() {
        assertNull(LivroBiblioteca.fromJson("isto nao e json"))
        assertNull(LivroBiblioteca.fromJson("""{"nomeArquivo":"a.txt"}"""))
        assertNull(LivroBiblioteca.fromJson("""{"id":"x1"}"""))
    }

    @Test
    fun `id do conteudo usa os 16 primeiros hex em minusculas`() {
        assertEquals("0123456789abcdef", idDoConteudo("0123456789ABCDEF0123456789ABCDEF"))
    }

    @Test
    fun `nome de exibicao tira prefixo extensao e underscores`() {
        assertEquals("Dom Casmurro", nomeExibicaoDoArquivo("entrada_123_Dom_Casmurro.epub"))
        assertEquals("Os Lusiadas", nomeExibicaoDoArquivo("Os_Lusiadas.pdf"))
        assertEquals("guarda-chuva", nomeExibicaoDoArquivo("guarda-chuva.txt"))
        assertEquals("sem extensao", nomeExibicaoDoArquivo("sem_extensao"))
    }

    @Test
    fun `aba baixados mostra so o que nao veio de arquivo`() {
        val livros = listOf(livro("1", "A"), livro("2", "B", origem = OrigemLivro.WIKISOURCE))
        assertEquals(listOf("2"), filtrar(livros, AbaBiblioteca.BAIXADOS, "", semProgresso, semAudio).map { it.id })
    }

    @Test
    fun `aba lendo exige progresso entre zero e concluido`() {
        val livros = listOf(livro("1", "A"), livro("2", "B"), livro("3", "C"), livro("4", "D"))
        val progresso: (LivroBiblioteca) -> Float? = { mapOf("1" to 0.4f, "2" to 0f, "3" to 0.99f)[it.id] }
        assertEquals(listOf("1"), filtrar(livros, AbaBiblioteca.LENDO, "", progresso, semAudio).map { it.id })
    }

    @Test
    fun `aba com audio usa o predicado`() {
        val livros = listOf(livro("1", "A"), livro("2", "B"))
        val r = filtrar(livros, AbaBiblioteca.COM_AUDIO, "", semProgresso) { it.id == "2" }
        assertEquals(listOf("2"), r.map { it.id })
    }

    @Test
    fun `busca ignora acentos e maiusculas em titulo e autor`() {
        val livros = listOf(livro("1", "O Alienista", "Machado de Assis"), livro("2", "São Bernardo", "Graciliano Ramos"))
        assertEquals(listOf("1"), filtrar(livros, AbaBiblioteca.TODOS, "ALIENISTA", semProgresso, semAudio).map { it.id })
        assertEquals(listOf("2"), filtrar(livros, AbaBiblioteca.TODOS, "sao", semProgresso, semAudio).map { it.id })
        assertEquals(listOf("2"), filtrar(livros, AbaBiblioteca.TODOS, "graciliano", semProgresso, semAudio).map { it.id })
        assertTrue(filtrar(livros, AbaBiblioteca.TODOS, "zzz", semProgresso, semAudio).isEmpty())
    }

    @Test
    fun `ordem recentes usa aberto em e cai para adicionado em`() {
        val livros = listOf(
            livro("a", "A", adicionadoEm = 10, abertoEm = 50),
            livro("b", "B", adicionadoEm = 60),
            livro("c", "C", adicionadoEm = 5)
        )
        assertEquals(listOf("b", "a", "c"), ordenar(livros, OrdemBiblioteca.RECENTES).map { it.id })
    }

    @Test
    fun `ordem por titulo ignora acentos`() {
        val livros = listOf(livro("1", "Éramos"), livro("2", "Dom"), livro("3", "Zé"))
        assertEquals(listOf("2", "1", "3"), ordenar(livros, OrdemBiblioteca.TITULO).map { it.id })
    }

    @Test
    fun `ordem por autor deixa autor nulo no fim`() {
        val livros = listOf(livro("1", "X"), livro("2", "Y", "Ávila"), livro("3", "Z", "Borges"))
        assertEquals(listOf("2", "3", "1"), ordenar(livros, OrdemBiblioteca.AUTOR).map { it.id })
    }

    @Test
    fun `continuar escolhe o aberto mais recente nao concluido`() {
        val livros = listOf(
            livro("1", "A", abertoEm = 10),
            livro("2", "B", abertoEm = 30),
            livro("3", "C", abertoEm = 20),
            livro("4", "D")
        )
        val progresso: (LivroBiblioteca) -> Float? = { mapOf("1" to 0.1f, "2" to 0.99f, "3" to 0.5f, "4" to 0.2f)[it.id] }
        assertEquals("3", continuar(livros, progresso)?.id)
    }

    @Test
    fun `nome na pasta nao colide com arquivos de controle nem fica oculto`() {
        assertEquals("livro_livro.json", nomeSeguroNaPasta("livro.json"))
        assertEquals("livro_Capa.JPG", nomeSeguroNaPasta("Capa.JPG"))
        assertEquals("livro_oculto.txt", nomeSeguroNaPasta(".oculto.txt"))
        assertEquals("Dom_Casmurro.epub", nomeSeguroNaPasta("Dom_Casmurro.epub"))
        assertEquals("livro", nomeSeguroNaPasta("  "))
    }

    @Test
    fun `formato suportado ignora maiusculas e rejeita o resto`() {
        assertEquals(true, ehFormatoSuportado("a.EPUB"))
        assertEquals(true, ehFormatoSuportado("b.mobi"))
        assertEquals(false, ehFormatoSuportado("c.part"))
        assertEquals(false, ehFormatoSuportado("semextensao"))
    }

    @Test
    fun `nome sem prefixo de entrada preserva a extensao`() {
        assertEquals("Dom_Casmurro.epub", nomeSemPrefixoDeEntrada("entrada_1790000000000_Dom_Casmurro.epub"))
        assertEquals("Livro.pdf", nomeSemPrefixoDeEntrada("Livro.pdf"))
    }

    @Test
    fun `versoes do mesmo texto sao a mesma obra`() {
        assertEquals(true, mesmaObra("entrada_1_O_CAIR_DA_NOITE.txt", 679_268, "entrada_9_o_cair_da_noite.txt", 682_033))
        assertEquals(true, mesmaObra("Livro (1).epub", 1000, "livro.epub", 1000))
    }

    @Test
    fun `nome igual com tamanho muito diferente ou nome diferente nao e a mesma obra`() {
        assertEquals(false, mesmaObra("capitulo1.txt", 1_000, "capitulo1.txt", 5_000))
        assertEquals(false, mesmaObra("a.txt", 1_000, "b.txt", 1_000))
        assertEquals(false, mesmaObra("a.txt", 1_000, "a.pdf", 1_000))
    }

    @Test
    fun `origem vem do prefixo da chave da busca`() {
        assertEquals(OrigemLivro.GUTENBERG, origemDaChave("gutenberg-55752"))
        assertEquals(OrigemLivro.WIKISOURCE, origemDaChave("wikisource-pt-1a2b"))
        assertEquals(OrigemLivro.ARCHIVE, origemDaChave("archive-dom-casmurro_202503"))
        assertEquals(OrigemLivro.ARQUIVO, origemDaChave("qualquer"))
        assertEquals(OrigemLivro.ARQUIVO, origemDaChave(null))
    }

    @Test
    fun `continuar ignora livro sem progresso conhecido e devolve nulo se nao houver`() {
        val livros = listOf(livro("1", "A", abertoEm = 10))
        assertNull(continuar(livros, semProgresso))
        assertNull(continuar(emptyList(), semProgresso))
    }
}
