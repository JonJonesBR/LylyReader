package com.jonjonesbr.audiobookgen.ui.apoio

import androidx.test.platform.app.InstrumentationRegistry
import com.jonjonesbr.audiobookgen.data.BlacklistStore
import com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Livro de teste para os testes de interface no emulador (rb-46). Os mesmos textos do teste de fumaça do Robolectric
 * (duplicados de propósito: um teste instrumentado não depende de uma classe da JVM). O livro vai para o cache de
 * texto, com as chaves que o app usa, então o Leitor não chama o Python.
 */
object LivroNoAparelho {

    /** Nome do arquivo (e, por isso, o título que o Leitor mostra, já que o livro não está na biblioteca). */
    const val NOME = "livro_ui"

    val PARAGRAFOS: List<String> = listOf(
        "Capítulo um, parágrafo 1. A chuva começou cedo naquela manhã e ninguém na vila " +
            "parecia ter pressa de sair de casa. Os pescadores ficaram olhando o céu cinzento, " +
            "à espera de uma trégua que só chegou perto do meio-dia.",
        "Capítulo um, parágrafo 2. Dona Irene abriu a janela da cozinha e deixou o cheiro de café " +
            "invadir o corredor. Na mesa, o menino desenhava barcos num caderno velho, enquanto o pai " +
            "conferia as redes na varanda.",
        "Capítulo um, parágrafo 3. O mercado da praça abriu com atraso, e os feirantes reclamavam " +
            "do barro espalhado pelas bancas. Ainda assim, as frutas chegaram frescas, e a fila de " +
            "compradores dobrou a esquina.",
        "Capítulo um, parágrafo 4. Ao fim da tarde, o padre passou pela escola para buscar as " +
            "cadeiras emprestadas. Ele contou que o rio tinha subido três palmos e que a ponte velha " +
            "ainda aguentava o peso dos caminhões.",
        "Capítulo um, parágrafo 5. A noite caiu sem estrelas, e a luz do farol piscava devagar " +
            "sobre a baía. Quem tinha lamparina acendeu, e as conversas na calçada seguiram até a " +
            "madrugada, sempre voltando ao mar.",
        "Capítulo dois, parágrafo 6. Na manhã seguinte, o mar estava calmo como vidro e os barcos " +
            "voltaram todos ao porto. A notícia de que a cidade vizinha tinha recebido uma carga de sal " +
            "correu de boca em boca até o almoço.",
        "Capítulo dois, parágrafo 7. A professora levou a turma até o antigo armazém, onde ainda " +
            "havia caixas de madeira com cartas antigas. Cada aluno escolheu uma carta e tentou " +
            "adivinhar quem a tinha escrito e por quê.",
        "Capítulo dois, parágrafo 8. Um estrangeiro desceu do ônibus das cinco com uma mala de couro " +
            "e um mapa dobrado. Perguntou pelo hotel da praça, mas o único hotel da vila tinha fechado " +
            "havia mais de dez anos.",
        "Capítulo dois, parágrafo 9. O estrangeiro acabou dormindo na casa da viúva Lúcia, que cobrou " +
            "pouco e serviu um prato de peixe com farinha. Pela manhã, ele já tinha uma lista de nomes " +
            "escrita a lápis.",
        "Capítulo dois, parágrafo 10. Quando o sol apareceu de novo, a vila inteira tinha uma história " +
            "nova para contar, e o livro de visitas da prefeitura ganhou a primeira página escrita em " +
            "letra miúda e apressada."
    )

    /** Dois capítulos: a primeira metade dos parágrafos no primeiro, o resto no segundo. */
    fun livroDeTeste(textos: List<String> = PARAGRAFOS): TextoExtraido {
        val metade = textos.size / 2
        var offset = 0
        val paragrafos = textos.mapIndexed { indice, texto ->
            Paragrafo(texto = texto, charOffset = offset, indiceCapitulo = if (indice < metade) 0 else 1)
                .also { offset += texto.length + 2 }
        }
        return TextoExtraido(
            capitulos = listOf(
                Capitulo("Capítulo 1", 0, metade - 1),
                Capitulo("Capítulo 2", metade, textos.size - 1),
            ),
            paragrafos = paragrafos,
            totalChars = offset
        )
    }

    /**
     * Grava o arquivo em filesDir/ui/<UUID>/ e põe o texto nos dois caches (bruto e limpo), com as chaves do app.
     * Cada teste grava numa pasta própria: o caminho entra na chave do cache. [removidas] é o número de linhas que a
     * limpeza tirou (a pílula do Leitor mostra esse número).
     */
    fun gravarLivroNoCache(textos: List<String> = PARAGRAFOS, removidas: Int = 0): File {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        // O arquivo precisa existir antes da chave: a chave leva tamanho e data de modificação.
        val arquivo = File(app.filesDir, "ui/${UUID.randomUUID()}/$NOME.txt")
        arquivo.parentFile?.mkdirs()
        arquivo.writeText(textos.joinToString("\n\n"))
        val bruto = requireNotNull(CacheDeTextoDoLivro.chaveBruto(app, arquivo.absolutePath)) {
            "Arquivo do teste ausente"
        }
        val limpo = CacheDeTextoDoLivro.chaveLimpo(
            bruto,
            "txt",
            Locale.getDefault().language,
            BlacklistStore(app).load()
        )
        val texto = livroDeTeste(textos)
        CacheDeTextoDoLivro.gravar(app, bruto, texto)
        CacheDeTextoDoLivro.gravar(app, limpo, texto, removidas = removidas)
        check(CacheDeTextoDoLivro.ler(app, bruto) == texto) { "Cache de texto do teste não gravou" }
        return arquivo
    }
}
