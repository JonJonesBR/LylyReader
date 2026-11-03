package com.jonjonesbr.audiobookgen.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.BlacklistStore
import com.jonjonesbr.audiobookgen.data.CacheDeTextoDoLivro
import com.jonjonesbr.audiobookgen.domain.Capitulo
import com.jonjonesbr.audiobookgen.domain.NavegacaoCapitulos
import com.jonjonesbr.audiobookgen.domain.Paragrafo
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.jonjonesbr.audiobookgen.domain.TextoExtraido
import com.jonjonesbr.audiobookgen.service.AudioPlayerService
import com.jonjonesbr.audiobookgen.util.PythonInicio
import com.jonjonesbr.audiobookgen.util.dpToPx
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.lang.reflect.Modifier
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * rb-1: rede de proteção da divisão da ReaderActivity (rb-3 a rb-9). Abre o Leitor no modo "Ler sem áudio" com um
 * livro de 2 capítulos posto no cache de texto (sem Python), rola, gira a tela e fecha, sem crash. Rodar em todo lote
 * que mexer no Leitor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = LeitorTesteApplication::class)
class ReaderActivitySmokeTest {

    private companion object {
        const val NOME = "livro_fumaca"
        const val TEMPO_LIMITE_MS = 10_000L
        /** Índices dos parágrafos 8 e 9 do livro: os dois únicos com "estrangeiro". */
        val INDICES_ESTRANGEIRO = listOf(7, 8)

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
    }

    private lateinit var app: Application
    private lateinit var arquivo: File
    private lateinit var iniciarPythonOriginal: (Context) -> Unit
    private var handlerOriginal: Thread.UncaughtExceptionHandler? = null
    private val falhas = CopyOnWriteArrayList<Throwable>()
    // Estado do livro gravado no @Before, para comparar com o que o Leitor procura quando a falha acontece.
    private lateinit var chaveBrutaInicial: String
    private lateinit var chaveLimpaInicial: String
    private var tamanhoInicial = 0L
    private var modificadoInicial = 0L

    @Before
    fun setUp() {
        // Antes de qualquer Activity: a fábrica de ViewModels guarda o Application do teste anterior.
        zerarFabricaDeViewModel()
        app = ApplicationProvider.getApplicationContext()
        // Sem Python na JVM: a costura faz o motor "indisponível", e todo caminho do Python desiste sozinho.
        iniciarPythonOriginal = PythonInicio.iniciar
        PythonInicio.iniciar = { throw IllegalStateException("Sem Python na JVM (teste de fumaça)") }
        // O BasePlayerActivity faz bindService no onStart; o serviço de áudio não deve ser vinculado aqui.
        shadowOf(app).declareComponentUnbindable(ComponentName(app, AudioPlayerService::class.java))
        // Exceções de corrotina que não chegam ao teste vão para o handler da thread.
        handlerOriginal = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> falhas.add(e) }
        arquivo = gravarLivroNoCache(livroDeTeste())
    }

    @After
    fun tearDown() {
        PythonInicio.iniciar = iniciarPythonOriginal
        Thread.setDefaultUncaughtExceptionHandler(handlerOriginal)
        zerarFabricaDeViewModel()
    }

    @Test
    fun abreRolaGiraEFechaSemCrash() {
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            // (a) Abre: os parágrafos aparecem e o título é o nome do arquivo (o livro não está na biblioteca).
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { a ->
                val rv = lista(a)
                assertEquals(PARAGRAFOS[0], textoEm(rv, 0))
                assertEquals(View.VISIBLE, rv.visibility)
                assertEquals(NOME, a.findViewById<TextView>(R.id.tvReaderTitulo).text.toString())
            }

            // (b) Rola até o último parágrafo.
            scenario.onActivity { lista(it).scrollToPosition(PARAGRAFOS.size - 1) }
            esperarAte(scenario, "rolagem até o último parágrafo") { a ->
                textoEm(lista(a), PARAGRAFOS.size - 1) == PARAGRAFOS.last()
            }

            // (c) Gira: a Activity é recriada e o texto volta. A posição pode ser a salva, não a zero.
            var antes = 0
            scenario.onActivity { antes = System.identityHashCode(it) }
            scenario.recreate()
            esperarAte(scenario, "tela recriada com os parágrafos") { a ->
                val p = primeiroVisivel(a)
                System.identityHashCode(a) != antes &&
                    lista(a).adapter?.itemCount == PARAGRAFOS.size &&
                    p >= 0 && textoEm(lista(a), p) != null
            }
            var p = -1
            var textoP: String? = null
            scenario.onActivity { a ->
                p = primeiroVisivel(a)
                textoP = textoEm(lista(a), p)
            }
            assertTrue("Nenhum parágrafo visível depois de girar", p >= 0)
            assertEquals(PARAGRAFOS[p], textoP)

            // (d) Fecha.
            scenario.moveToState(Lifecycle.State.DESTROYED)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun abreSemCaminhoFechaSemCrash() {
        // Sem EXTRA_CAMINHO, o onCreate chama finish() logo depois do setContentView: a tela tem de fechar sozinha.
        val intent = Intent(app, ReaderActivity::class.java)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            scenario.moveToState(Lifecycle.State.DESTROYED)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun buscaAchaContaENavega() {
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }

            // (a) Abre a busca: a barra aparece.
            scenario.onActivity { it.findViewById<View>(R.id.btnBuscarReader).performClick() }
            scenario.onActivity { a ->
                assertEquals(View.VISIBLE, a.findViewById<View>(R.id.layoutBuscaReader).visibility)
            }

            // (b) "estrangeiro" acha dois parágrafos: o contador mostra 1 de 2 e o primeiro achado fica na tela.
            scenario.onActivity { it.findViewById<EditText>(R.id.etBuscaReader).setText("estrangeiro") }
            esperarAte(scenario, "contador 1 de 2 e primeiro achado na tela") { a ->
                contadorEm(a) == a.getString(R.string.busca_contador, 1, 2) &&
                    estaVisivel(a, INDICES_ESTRANGEIRO[0])
            }

            // (c) Próximo: 2 de 2, com o segundo achado na tela.
            scenario.onActivity { it.findViewById<View>(R.id.btnBuscaProximo).performClick() }
            esperarAte(scenario, "contador 2 de 2 e segundo achado na tela") { a ->
                contadorEm(a) == a.getString(R.string.busca_contador, 2, 2) &&
                    estaVisivel(a, INDICES_ESTRANGEIRO[1])
            }

            // (d) Fecha: a barra some e a consulta sai do campo.
            scenario.onActivity { it.findViewById<View>(R.id.btnBuscaFechar).performClick() }
            scenario.onActivity { a ->
                assertEquals(View.GONE, a.findViewById<View>(R.id.layoutBuscaReader).visibility)
                assertEquals("", a.findViewById<EditText>(R.id.etBuscaReader).text.toString())
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun barraDeCapituloNavegaEAbrePainel() {
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }

            // (a) Começa no capítulo 1 de 2: a seta anterior fica apagada e desligada.
            esperarAte(scenario, "rótulo do capítulo 1 na barra") { a ->
                rotuloDaBarra(a) == rotuloDoCapitulo(a, 1, "Capítulo 1")
            }
            scenario.onActivity { a ->
                val anterior = a.findViewById<View>(R.id.btnCapituloAnterior)
                assertEquals(0.3f, anterior.alpha, 0.001f)
                assertFalse(anterior.isEnabled)
            }

            // (b) Próximo: vai ao capítulo 2 (a partir do parágrafo 5). A barra mostra 2 de 2 e o progresso aparece.
            // "Pelo menos o 5" e não "igual a 5": a lista pode acomodar um pouco diferente conforme o tamanho da tela.
            scenario.onActivity { it.findViewById<View>(R.id.btnCapituloProximo).performClick() }
            esperarAte(scenario, "primeiro visível no capítulo 2, rótulo 2 de 2 e progresso visível") { a ->
                primeiroVisivel(a) >= 5 &&
                    rotuloDaBarra(a) == rotuloDoCapitulo(a, 2, "Capítulo 2") &&
                    a.findViewById<View>(R.id.tvProgressoLeitura).visibility == View.VISIBLE
            }

            // (c) Toque na área do capítulo: o painel de capítulos abre num BottomSheetDialog.
            scenario.onActivity { it.findViewById<View>(R.id.areaCapituloAtual).performClick() }
            val painel = ShadowDialog.getLatestDialog()
            assertTrue("Painel de capítulos não abriu", painel is BottomSheetDialog && painel.isShowing)
            painel?.dismiss()
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun pageDownRolaUmaTela() {
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            esperarAte(scenario, "lista que ainda pode rolar para baixo") { a -> lista(a).canScrollVertically(1) }

            // PAGE_DOWN rola uma tela pelo teclado (rb-6: o controller de rolagem trata a tecla).
            scenario.onActivity {
                assertTrue(it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)))
            }
            esperarAte(scenario, "uma tela abaixo") { a -> primeiroVisivel(a) > 0 }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun fimDoLivroGravaUltimoParagrafo() {
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            // Ainda na tela, nada foi gravado: quem grava a posição é o onStop.
            assertEquals(-1, AppPrefs(app).posicaoLeitura(arquivo.absolutePath, -1))

            scenario.onActivity { lista(it).scrollToPosition(PARAGRAFOS.lastIndex) }
            esperarAte(scenario, "fim do livro na tela") { a ->
                !lista(a).canScrollVertically(1) && textoEm(lista(a), PARAGRAFOS.lastIndex) != null
            }

            // Sai da tela: o fim do livro grava o último parágrafo, para a biblioteca mostrar o livro como lido.
            scenario.moveToState(Lifecycle.State.CREATED)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(PARAGRAFOS.lastIndex, AppPrefs(app).posicaoLeitura(arquivo.absolutePath, -1))
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun telaDoLerSemAudio() {
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            // A limpeza pode ainda mostrar o "Preparando" quando o adapter já enche com o texto bruto: espera a tela
            // assentar antes de conferir o carregando e a pílula (um assert direto pegaria essa janela).
            esperarAte(scenario, "carregando e pílula fora") { a ->
                a.findViewById<View>(R.id.layoutCarregandoReader).visibility == View.GONE &&
                    a.findViewById<View>(R.id.bannerTextoOtimizado).visibility == View.GONE
            }
            scenario.onActivity { a ->
                assertEquals(View.GONE, a.findViewById<View>(R.id.layoutReaderErro).visibility)
                assertEquals(View.GONE, a.findViewById<View>(R.id.layoutBarraModo).visibility)
                assertEquals(View.GONE, a.findViewById<View>(R.id.fabConverterPrincipal).visibility)
                assertEquals(View.GONE, a.findViewById<View>(R.id.fabVoltarNarracao).visibility)
                assertEquals(View.GONE, a.findViewById<View>(R.id.layoutGuidedPlayer).visibility)
                // O XML deixa o "Converter assim mesmo" VISIBLE: só o configurar() da tela o esconde.
                assertEquals(View.GONE, a.findViewById<View>(R.id.btnConverterAssim).visibility)
                assertEquals(View.VISIBLE, lista(a).visibility)
                // Tema claro e margem média (nível padrão, 20dp).
                val fundo = a.findViewById<View>(R.id.readerRoot).background as ColorDrawable
                assertEquals(paletaDoLeitor(TemaLeitura.CLARO).fundo, fundo.color)
                assertEquals(a.dpToPx(20), lista(a).paddingLeft)
                assertEquals(a.dpToPx(20), lista(a).paddingRight)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    // ── Livro de teste (cache, sem Python) ────────────────────────────────────

    /** Dois capítulos: parágrafos 0 a 4 no primeiro, 5 a 9 no segundo. */
    private fun livroDeTeste(): TextoExtraido {
        var offset = 0
        val paragrafos = PARAGRAFOS.mapIndexed { indice, texto ->
            Paragrafo(texto = texto, charOffset = offset, indiceCapitulo = if (indice < 5) 0 else 1)
                .also { offset += texto.length + 2 }
        }
        return TextoExtraido(
            capitulos = listOf(Capitulo("Capítulo 1", 0, 4), Capitulo("Capítulo 2", 5, 9)),
            paragrafos = paragrafos,
            totalChars = offset
        )
    }

    /**
     * Grava o arquivo e põe o texto nos dois caches (bruto e limpo, iguais), com as mesmas chaves que o app usa. Assim
     * ExtractTextUseCase e TextoNarravelDoLivro.limpar acham o texto e não chamam o Python.
     *
     * Cada teste grava o livro numa pasta própria (UUID no caminho). O caminho entra na chave do cache e na trava do
     * ExtractTextUseCase, que são estáticas: se dois testes usassem o mesmo caminho, um herdaria o estado do outro.
     */
    private fun gravarLivroNoCache(texto: TextoExtraido): File {
        // O arquivo precisa existir antes da chave: a chave leva tamanho e data de modificação.
        val f = File(app.filesDir, "fumaca/${UUID.randomUUID()}/$NOME.txt")
        f.parentFile?.mkdirs()
        f.writeText(PARAGRAFOS.joinToString("\n\n"))
        val bruto = requireNotNull(CacheDeTextoDoLivro.chaveBruto(app, f.absolutePath)) { "Arquivo do teste ausente" }
        val limpo = CacheDeTextoDoLivro.chaveLimpo(
            bruto,
            "txt",
            Locale.getDefault().language,
            BlacklistStore(app).load()
        )
        chaveBrutaInicial = bruto
        chaveLimpaInicial = limpo
        tamanhoInicial = f.length()
        modificadoInicial = f.lastModified()
        CacheDeTextoDoLivro.gravar(app, bruto, texto)
        CacheDeTextoDoLivro.gravar(app, limpo, texto, removidas = 0)
        assertEquals(texto, CacheDeTextoDoLivro.ler(app, bruto))
        return f
    }

    // ── Ajudantes ─────────────────────────────────────────────────────────────

    /**
     * Zera o singleton estático de `ViewModelProvider.AndroidViewModelFactory`. Ele guarda o Application do primeiro
     * uso e o devolve depois: o ReaderViewModel (só com construtor de Application) passa por essa fábrica, então o
     * 2º teste do sandbox extrairia com o cacheDir do 1º, já apagado. O campo acha-se pelo tipo, não pelo nome
     * (sInstance ou outro, conforme a versão do lifecycle).
     */
    private fun zerarFabricaDeViewModel() {
        val tipo = ViewModelProvider.AndroidViewModelFactory::class.java
        tipo.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == tipo }
            .forEach { campo ->
                campo.isAccessible = true
                campo.set(null, null)
            }
    }

    private fun lista(a: ReaderActivity): RecyclerView = a.findViewById(R.id.recyclerViewParagrafos)

    /** Texto da barra de capítulo (vazio enquanto ela não tem rótulo). */
    private fun rotuloDaBarra(a: ReaderActivity): String =
        a.findViewById<TextView>(R.id.tvCapituloAtual).text.toString()

    /** Rótulo que a barra monta para o capítulo [numero] de 2 (a mesma conta de atualizarBarra). */
    private fun rotuloDoCapitulo(a: ReaderActivity, numero: Int, titulo: String): String =
        a.getString(R.string.capitulo_barra, numero, 2, NavegacaoCapitulos.tituloLimpo(titulo))

    private fun primeiroVisivel(a: ReaderActivity): Int =
        (lista(a).layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()

    /** Texto do contador da busca (vazio enquanto a barra não mostra nada). */
    private fun contadorEm(a: ReaderActivity): String =
        a.findViewById<TextView>(R.id.tvBuscaContador).text.toString()

    /** Se o parágrafo [pos] está entre os visíveis agora (a busca rola até o achado). */
    private fun estaVisivel(a: ReaderActivity, pos: Int): Boolean {
        val lm = lista(a).layoutManager as LinearLayoutManager
        return pos in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()
    }

    /** No ParagrafoAdapter o item é o próprio TextView (VHLeitura). */
    private fun textoEm(rv: RecyclerView, pos: Int): String? =
        (rv.findViewHolderForAdapterPosition(pos)?.itemView as? TextView)?.text?.toString()

    /**
     * Espera a tela chegar à condição, deixando a main looper andar (a extração e o cache rodam em IO e voltam pela
     * looper). Falha logo se o Leitor mostrar erro, e ao estourar o tempo limite.
     */
    private fun esperarAte(
        scenario: ActivityScenario<ReaderActivity>,
        descricao: String,
        condicao: (ReaderActivity) -> Boolean
    ) {
        val fim = System.nanoTime() + TEMPO_LIMITE_MS * 1_000_000L
        // Application da tela (o que a Activity recebeu), para o diagnóstico comparar com o do teste.
        var appDaTela: Application? = null
        while (System.nanoTime() < fim) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            var pronto = false
            scenario.onActivity { a ->
                appDaTela = a.application
                if (a.findViewById<View>(R.id.layoutReaderErro).visibility == View.VISIBLE) {
                    val erro = a.findViewById<TextView>(R.id.tvReaderErro).text
                    fail("Leitor mostrou erro: $erro. ${diagnosticoDoCache(appDaTela)}")
                }
                pronto = condicao(a)
            }
            if (pronto) return
            Thread.sleep(10)
        }
        fail("Tempo esgotado: $descricao. ${diagnosticoDoCache(appDaTela)}")
    }

    /**
     * Estado do cache e do arquivo agora, comparado com o que o @Before gravou, e os cacheDir dos dois Applications (o
     * do teste e o da tela). Vai na mensagem da falha. A contagem dos .json vem antes das leituras, porque a leitura
     * atualiza a data do cache e apaga um JSON ilegível. Nunca lança: a mensagem não pode esconder o motivo real.
     */
    private fun diagnosticoDoCache(appDaTela: Application?): String = runCatching {
        val jsons = File(app.cacheDir, "texto_livros").listFiles { f -> f.name.endsWith(".json") }?.size ?: -1
        val chaveAgora = CacheDeTextoDoLivro.chaveBruto(app, arquivo.absolutePath)
        val naTela = appDaTela?.let { CacheDeTextoDoLivro.ler(it, chaveBrutaInicial) != null }
        "[diagnóstico] chave bruta do @Before=$chaveBrutaInicial; " +
            "chave bruta agora=$chaveAgora; " +
            "cache bruto agora=${CacheDeTextoDoLivro.ler(app, chaveAgora) != null}; " +
            "cache limpo do @Before=${CacheDeTextoDoLivro.ler(app, chaveLimpaInicial) != null}; " +
            "arquivo existe=${arquivo.exists()}, tamanho=${arquivo.length()} (@Before: $tamanhoInicial), " +
            "modificado=${arquivo.lastModified()} (@Before: $modificadoInicial); " +
            "jsons em texto_livros=$jsons; " +
            "cacheDir do teste=${app.cacheDir}; " +
            "cacheDir da tela=${appDaTela?.cacheDir}; " +
            "cache bruto do @Before na tela=$naTela"
    }.getOrElse { "diagnóstico indisponível: $it" }
}
