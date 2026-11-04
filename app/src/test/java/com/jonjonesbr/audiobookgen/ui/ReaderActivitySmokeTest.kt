package com.jonjonesbr.audiobookgen.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.PopupMenu
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

        /** Parágrafo que não cabe na altura de uma página (rolagem horizontal, rb-26a). */
        val PARAGRAFO_LONGO: String = "Capítulo um, parágrafo 2, longo. " +
            (1..80).joinToString(" ") { "Frase $it do parágrafo que não cabe numa página só." }

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
    fun semLivroNaoApagaAAparenciaGravada() {
        // Sair sem abrir o livro (rb-16) não pode gravar os padrões do ViewModel por cima da Aparência do usuário.
        val prefs = AppPrefs(app)
        prefs.readerFonte = 22f
        prefs.readerMargemNivel = 2
        prefs.readerRolagemHorizontal = true
        ActivityScenario.launch<ReaderActivity>(Intent(app, ReaderActivity::class.java)).use { scenario ->
            scenario.moveToState(Lifecycle.State.DESTROYED)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(22f, prefs.readerFonte, 0f)
        assertEquals(2, prefs.readerMargemNivel)
        assertTrue(prefs.readerRolagemHorizontal)
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
                assertEquals(View.GONE, a.findViewById<View>(R.id.layoutBarraSelecao).visibility)
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun rolagemHorizontalRolaDentroDoParagrafoLongo() {
        // Parágrafo 1 longo; os demais são os de sempre. Só este teste usa o texto longo.
        val textos = PARAGRAFOS.toMutableList().apply { this[1] = PARAGRAFO_LONGO }
        val livro = gravarLivroNoCache(livroDeTeste(textos), textos)
        AppPrefs(app).readerRolagemHorizontal = true
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, livro.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            // (a) Horizontal, com a primeira página na tela. O parágrafo curto não rola por dentro.
            esperarAte(scenario, "rolagem horizontal com a primeira página na tela") { a ->
                val lm = lista(a).layoutManager as? LinearLayoutManager
                lm?.orientation == LinearLayoutManager.HORIZONTAL && paginaEm(lista(a), 0) != null
            }
            scenario.onActivity { a -> assertFalse(paginaEm(lista(a), 0)!!.canScrollVertically(1)) }

            // (b) Vai ao parágrafo longo: a página dele fica inteira na tela.
            scenario.onActivity { lista(it).scrollToPosition(1) }
            esperarAte(scenario, "página do parágrafo longo inteira na tela") { a ->
                val lm = lista(a).layoutManager as LinearLayoutManager
                lm.findFirstCompletelyVisibleItemPosition() == 1 && paginaEm(lista(a), 1) != null
            }

            // (c) A página tem a altura da área da lista, o texto passa dela e dá para rolar por dentro.
            scenario.onActivity { a ->
                val rv = lista(a)
                val pagina = paginaEm(rv, 1)!!
                assertEquals(rv.height - rv.paddingTop - rv.paddingBottom, pagina.height)
                assertTrue("parágrafo longo deveria passar da página", pagina.texto.height > pagina.height)
                assertTrue(pagina.canScrollVertically(1))
                pagina.scrollTo(0, pagina.texto.height)
            }

            // (d) Rolou até o fim: o fim do parágrafo está visível e não dá para rolar mais.
            esperarAte(scenario, "fim do parágrafo longo visível") { a ->
                val p = paginaEm(lista(a), 1)
                p != null && !p.canScrollVertically(1) && p.scrollY + p.height >= p.texto.bottom
            }

            // (e) A barra da leitura guiada aparece: a página encolhe junto com a lista, sem corte residual.
            var alturaAntes = 0
            scenario.onActivity { a ->
                alturaAntes = paginaEm(lista(a), 1)!!.height
                val barra = a.findViewById<View>(R.id.layoutGuidedPlayer)
                barra.minimumHeight = lista(a).height / 3
                barra.visibility = View.VISIBLE
            }
            esperarAte(scenario, "página ajustada à área com a barra da guiada") { a ->
                val rv = lista(a)
                val p = paginaEm(rv, 1)
                p != null && p.height < alturaAntes && p.height == rv.height - rv.paddingTop - rv.paddingBottom
            }

            // (f) A barra some: a página volta à altura de antes.
            scenario.onActivity { it.findViewById<View>(R.id.layoutGuidedPlayer).visibility = View.GONE }
            esperarAte(scenario, "página volta à altura de antes") { a -> paginaEm(lista(a), 1)?.height == alturaAntes }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun barraDeSelecaoApareceECancela() {
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }

            // (a) "Selecionar vários parágrafos" no menu: a seleção começa no parágrafo 1 e a barra aparece.
            scenario.onActivity { modeloDe(it).onParagrafoLongPress(1) }
            esperarAte(scenario, "barra com 1 parágrafo") { a ->
                barraDeSelecaoVisivel(a) && textoDaBarra(a) == plural(a, 1)
            }

            // (b) Toque em outro parágrafo amplia a seleção: a barra passa a contar 3.
            scenario.onActivity { modeloDe(it).onParagrafoToque(3) }
            esperarAte(scenario, "barra com 3 parágrafos") { a -> textoDaBarra(a) == plural(a, 3) }

            // (c) Cancelar: a barra some e a seleção sai do ViewModel.
            scenario.onActivity { it.findViewById<View>(R.id.btnSelecaoCancelar).performClick() }
            esperarAte(scenario, "barra escondida e sem seleção") { a ->
                !barraDeSelecaoVisivel(a) && modeloDe(a).uiState.value.selecaoInicio == null
            }

            // (d) Voltar com a seleção ativa só limpa a seleção: o leitor continua aberto.
            scenario.onActivity { modeloDe(it).onParagrafoLongPress(1) }
            esperarAte(scenario, "barra de novo") { a -> barraDeSelecaoVisivel(a) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario.onActivity { a ->
                assertNull(modeloDe(a).uiState.value.selecaoInicio)
                assertFalse(a.isFinishing)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun paragrafoReaproveitadoAceitaSelecao() {
        // Sessenta parágrafos: a lista desce e volta, e as views são reaproveitadas e religadas a outros textos.
        val textos = List(6) { PARAGRAFOS }.flatten()
        val livro = gravarLivroNoCache(livroDeTeste(textos), textos)

        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(livro)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == textos.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { lista(it).scrollToPosition(textos.lastIndex) }
            esperarAte(scenario, "último parágrafo na tela") { a -> textoEm(lista(a), textos.lastIndex) != null }
            scenario.onActivity { lista(it).scrollToPosition(0) }
            esperarAte(scenario, "primeiro parágrafo de volta") { a -> textoEm(lista(a), 0) != null }

            // Cada parágrafo visível aceita seleção: o rearme acontece quando a view volta à janela (rb-38).
            scenario.onActivity { a ->
                val lm = lista(a).layoutManager as LinearLayoutManager
                for (pos in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) {
                    val tv = lista(a).findViewHolderForAdapterPosition(pos)?.itemView as? TextView ?: continue
                    assertTrue("parágrafo $pos não aceita seleção", podeSelecionar(tv))
                }
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun toqueNaListaSeguraRepinturaDaSincronia() {
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }

            // Dedo sobre o parágrafo 2 e a sincronia chega a ele: a repintura espera o dedo (rb-38).
            val t0 = 10_000L
            evento(scenario, 2, MotionEvent.ACTION_DOWN, t0, t0)
            scenario.onActivity { modeloDe(it).setParagrafoSyncAtual(2) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> assertFalse(fundoDoParagrafo(lista(a), 2) is LayerDrawable) }

            // Soltou (CANCEL, como quando a lista rola): a repintura segurada aplica e o parágrafo ganha a barra.
            evento(scenario, 2, MotionEvent.ACTION_CANCEL, t0, t0 + 50L)
            esperarAte(scenario, "barra de sincronia no parágrafo 2") { a ->
                fundoDoParagrafo(lista(a), 2) is LayerDrawable
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun barraDaSelecaoDeTextoNoLerSemAudio() {
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }

            // "Capítulo" selecionado no parágrafo 0, com o menu da seleção aberto (como o toque longo faria).
            scenario.onActivity { a ->
                val tv = lista(a).findViewHolderForAdapterPosition(0)!!.itemView as TextView
                // TextView com textIsSelectable guarda o texto como Spannable (setTextIsSelectable usa BufferType.SPANNABLE).
                Selection.setSelection(tv.text as Spannable, 0, 5)
                val cb = tv.customSelectionActionModeCallback
                val menu = PopupMenu(a, tv).menu
                cb.onCreateActionMode(ModoFalso(cb, menu), menu)
                // rb-49: sem áudio, o ⋮ só tem Apagar frase; Destacar e Definir já estão na barra.
                assertEquals(1, menu.size())
                assertEquals(a.getString(R.string.btn_excluir_ocorrencias), menu.getItem(0).title.toString())
            }
            esperarAte(scenario, "barra própria da seleção aparece") { a -> barraDaSelecaoTextoVisivel(a) }
            // rb-48: o parágrafo 0 começa a 12 dp do topo, sob a barra; a lista desce por translação para a linha sair dela.
            esperarAte(scenario, "lista desce para a seleção sair de baixo da barra") { a -> lista(a).translationY > 0f }
            scenario.onActivity { a ->
                // Só ler: sem o que toca o texto (áudio, pronúncia, vários parágrafos); Destacar e Definir ficam.
                assertTrue(botaoVisivel(a, R.id.btnSelecaoTextoDestacar))
                assertTrue(botaoVisivel(a, R.id.btnSelecaoTextoDefinir))
                assertFalse(botaoVisivel(a, R.id.btnSelecaoTextoGerar))
                assertFalse(botaoVisivel(a, R.id.btnSelecaoTextoPronuncia))
                assertFalse(botaoVisivel(a, R.id.btnSelecaoTextoVarios))
            }

            // Destacar: o menu fecha, a barra some e o diálogo de destaque abre.
            scenario.onActivity { it.findViewById<View>(R.id.btnSelecaoTextoDestacar).performClick() }
            esperarAte(scenario, "barra escondida depois de destacar") { a -> !barraDaSelecaoTextoVisivel(a) }
            esperarAte(scenario, "lista volta ao lugar") { a -> lista(a).translationY == 0f }
            scenario.onActivity { assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true) }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun repinturaQueChegaComODedoNoParagrafoNaoReescreveOTexto() {
        // rb-53: a sincronia repinta o parágrafo que está sendo lido. Se a repintura chega com o dedo já sobre ele,
        // o texto fica como está (o toque longo precisa da posição do texto) e a repintura sai quando o toque acaba.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { a ->
                modeloDe(a).setParagrafoSyncAtual(2)
                modeloDe(a).setProgressPct(0.5f)
            }
            // A barra de sincronia (LayerDrawable no fundo) mostra que a repintura já chegou ao parágrafo 2.
            esperarAte(scenario, "parágrafo 2 com a sincronia e o progresso") { a ->
                fundoDoParagrafo(lista(a), 2) is LayerDrawable && tvEm(lista(a), 2)?.text is Spannable
            }

            // Tudo num único onActivity: em chamadas separadas, o Robolectric roda o layout pendente antes do DOWN,
            // e a repintura chega antes do toque (o caso que este teste não quer).
            var antes: CharSequence? = null
            val t0 = 10_000L
            scenario.onActivity { a ->
                val rv = lista(a)
                antes = requireNotNull(tvEm(rv, 2)).text
                // Repintura emitida antes do DOWN: fica pendente até o layout, que roda depois do toque.
                rv.adapter?.notifyItemChanged(2)
                assertTrue("A repintura deveria estar pendente antes do toque", rv.hasPendingAdapterUpdates())
                despacharToque(rv, 2, MotionEvent.ACTION_DOWN, t0, t0)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> assertSame(antes, tvEm(lista(a), 2)?.text) }

            // Dedo solto (CANCEL, como no outro teste de sincronia): a repintura adiada aplica o texto de novo.
            evento(scenario, 2, MotionEvent.ACTION_CANCEL, t0, t0 + 50L)
            esperarAte(scenario, "repintura adiada aplicada ao soltar o dedo") { a ->
                val agora = tvEm(lista(a), 2)?.text
                agora != null && agora !== antes
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun toqueLongoNaoAmpliaSelecaoDeParagrafos() {
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { modeloDe(it).onParagrafoLongPress(1) }
            esperarAte(scenario, "barra com 1 parágrafo") { a ->
                barraDeSelecaoVisivel(a) && textoDaBarra(a) == plural(a, 1)
            }

            // Toque longo sobre o parágrafo 3: não vale como "ampliar" (o toque que falhou em selecionar não é clique).
            val limite = ViewConfiguration.getLongPressTimeout().toLong()
            val inicioLongo = 10_000L
            evento(scenario, 3, MotionEvent.ACTION_DOWN, inicioLongo, inicioLongo)
            evento(scenario, 3, MotionEvent.ACTION_UP, inicioLongo, inicioLongo + 2 * limite)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { assertEquals(plural(it, 1), textoDaBarra(it)) }

            // Toque curto, cinco segundos depois: amplia a seleção até o parágrafo 3.
            val inicioCurto = inicioLongo + 5_000L
            evento(scenario, 3, MotionEvent.ACTION_DOWN, inicioCurto, inicioCurto)
            evento(scenario, 3, MotionEvent.ACTION_UP, inicioCurto, inicioCurto + 50L)
            esperarAte(scenario, "toque curto amplia a seleção até o parágrafo 3") { a ->
                textoDaBarra(a) == plural(a, 3)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun toqueCurtoUnicoAgeNoParagrafoSemFoco() {
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { modeloDe(it).onParagrafoLongPress(1) }
            esperarAte(scenario, "barra com 1 parágrafo") { a ->
                barraDeSelecaoVisivel(a) && textoDaBarra(a) == plural(a, 1)
            }

            // O parágrafo 3 começa sem foco. Antes, o 1º toque só pegava o foco
            // e o clique vinha no 2º (rb-42).
            scenario.onActivity { a ->
                val tv3 = (lista(a).findViewHolderForAdapterPosition(3) as ParagrafoAdapter.VHLeitura).tv
                assertFalse(tv3.isFocused)
            }

            // Um toque só, curto: já amplia a seleção até o parágrafo 3.
            val t0 = 10_000L
            evento(scenario, 3, MotionEvent.ACTION_DOWN, t0, t0)
            evento(scenario, 3, MotionEvent.ACTION_UP, t0, t0 + 50L)
            esperarAte(scenario, "um toque curto já amplia até o parágrafo 3") { a ->
                textoDaBarra(a) == plural(a, 3)
            }

            // Segundo toque, agora com foco: também gera o performClick, que não pode disparar de novo.
            // O 3 era o fim, então tocar nele encolhe a seleção para o início. Se o clique duplicasse, ficaria em 3.
            val t1 = t0 + 5_000L
            evento(scenario, 3, MotionEvent.ACTION_DOWN, t1, t1)
            evento(scenario, 3, MotionEvent.ACTION_UP, t1, t1 + 50L)
            esperarAte(scenario, "segundo toque encolhe a seleção para 1") { a ->
                textoDaBarra(a) == plural(a, 1)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            scenario.onActivity { assertEquals(plural(it, 1), textoDaBarra(it)) }

            // Arraste curto no parágrafo 4, acima do slop: não é toque, então a seleção não muda.
            val t2 = t1 + 5_000L
            val slop = ViewConfiguration.get(app).scaledTouchSlop.toFloat()
            evento(scenario, 4, MotionEvent.ACTION_DOWN, t2, t2)
            evento(scenario, 4, MotionEvent.ACTION_MOVE, t2, t2 + 20L, dx = 3 * slop)
            evento(scenario, 4, MotionEvent.ACTION_UP, t2, t2 + 50L, dx = 3 * slop)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { assertEquals(plural(it, 1), textoDaBarra(it)) }

            // Fora de qualquer toque, o clique de acessibilidade (TalkBack, Switch) lê dali, sem toque.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            scenario.onActivity { a ->
                val tv4 = (lista(a).findViewHolderForAdapterPosition(4) as ParagrafoAdapter.VHLeitura).tv
                tv4.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)
            }
            esperarAte(scenario, "clique de acessibilidade amplia até o parágrafo 4") { a ->
                textoDaBarra(a) == plural(a, 4)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun paragrafoComFocoNaoPuxaAListaNaRolagemManual() {
        // rb-55: com o dedo, o parágrafo tocado tem foco. O setText da guiada não pode puxar a lista de volta a ele.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            // O modo de toque é do ViewRootImpl: sem ele, o pedido não é ignorado. Se o Robolectric não entrar nele
            // mesmo forçando, o teste é pulado (Assume), e a regra fica coberta pelos testes da JVM.
            var noModoDeToque = false
            scenario.onActivity { a -> noModoDeToque = lista(a).isInTouchMode }
            if (!noModoDeToque) {
                scenario.onActivity { a -> entrarNoModoDeToque(a) }
                scenario.onActivity { a -> noModoDeToque = lista(a).isInTouchMode }
            }
            assumeTrue("Robolectric não entrou no modo de toque", noModoDeToque)

            // O foco antes de rolar: é ele que faz o LayoutManager mostrar o parágrafo 3 inteiro.
            scenario.onActivity { a ->
                (lista(a).layoutManager as LinearLayoutManager).scrollToPositionWithOffset(3, 0)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> tvEm(lista(a), 3)!!.requestFocus() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            // Meia altura do parágrafo para cima: a 1ª linha dele sai por cima, com o foco ainda nele.
            scenario.onActivity { a -> lista(a).scrollBy(0, tvEm(lista(a), 3)!!.height / 2) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            var topoAntes = 0
            scenario.onActivity { a ->
                val tv3 = tvEm(lista(a), 3)!!
                topoAntes = tv3.top
                assertTrue("o parágrafo 3 deveria estar cortado por cima", topoAntes < 0)
                assertTrue(tv3.isFocused)
            }

            // (a) Pedido direto de mostrar o topo do parágrafo, sem seleção: a lista não rola. É a prova deste teste.
            scenario.onActivity { a -> tvEm(lista(a), 3)!!.requestRectangleOnScreen(Rect(0, 0, 1, 1), true) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> assertEquals(topoAntes, tvEm(lista(a), 3)!!.top) }

            // (b) Ponta a ponta: a sincronia repinta o parágrafo 3 com setText. Depende de o Robolectric despachar o
            // onPreDraw; por isso a prova certa é a (a).
            scenario.onActivity { a ->
                modeloDe(a).setParagrafoSyncAtual(3)
                modeloDe(a).setProgressPct(0.5f)
            }
            esperarAte(scenario, "sincronia no parágrafo 3") { a -> fundoDoParagrafo(lista(a), 3) is LayerDrawable }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            scenario.onActivity { a -> assertEquals(topoAntes, tvEm(lista(a), 3)!!.top) }

            // (c) Com seleção, o pedido das alças vale: a lista rola para mostrar o topo do trecho.
            scenario.onActivity { a ->
                val tv3 = tvEm(lista(a), 3)!!
                Selection.setSelection(tv3.text as Spannable, 0, 5)
                tv3.requestRectangleOnScreen(Rect(0, 0, 1, 1), true)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> assertTrue(tvEm(lista(a), 3)!!.top > topoAntes) }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun selecaoSemDonoSomeERepinturaSai() {
        // rb-54: seleção sem foco é órfã e some; nada fica preso (repintura de sincronia, textoEmUso).
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }

            // R1: o foco que sai do parágrafo 1 desfaz a seleção dele, sem menu.
            scenario.onActivity { a -> selecionarComFoco(lista(a), 1, 0, 5) }
            scenario.onActivity { a -> tvEm(lista(a), 3)!!.requestFocus() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> assertFalse(tvEm(lista(a), 1)!!.hasSelection()) }

            // R2: seleção sem foco no parágrafo 1 (órfã) não sobrevive ao rebind.
            scenario.onActivity { a ->
                Selection.setSelection(tvEm(lista(a), 1)!!.text as Spannable, 0, 5)
                lista(a).adapter?.notifyItemChanged(1)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> assertFalse(tvEm(lista(a), 1)!!.hasSelection()) }

            // R4: a seleção com foco segura a sincronia; quando o foco sai, a sincronia é aplicada e nada fica preso.
            scenario.onActivity { a -> selecionarComFoco(lista(a), 2, 0, 5) }
            scenario.onActivity { a ->
                modeloDe(a).setParagrafoSyncAtual(2)
                modeloDe(a).setProgressPct(0.5f)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a ->
                assertFalse(fundoDoParagrafo(lista(a), 2) is LayerDrawable)
                assertTrue(listaDoLeitor(a).textoEmUso)
            }
            scenario.onActivity { a -> tvEm(lista(a), 0)!!.requestFocus() }
            esperarAte(scenario, "sincronia aplicada e nada preso depois de soltar a seleção") { a ->
                val rv = listaDoLeitor(a)
                fundoDoParagrafo(rv, 2) is LayerDrawable && !rv.textoEmUso && tvEm(rv, 2)?.hasSelection() != true
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun barraVoltaComASelecaoVivaSemOModo() {
        // rb-54: o Editor para o modo do sistema preservando a seleção, e a barra segue a seleção viva.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            val esperaDoDuploToque = Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout().toLong() + 200L)

            // R3: o modo fecha com a seleção no parágrafo 2: a barra continua, sem o menu, e a seleção fica.
            var modo: ModoFalso? = null
            scenario.onActivity { a -> modo = abrirModoFalso(a, selecionarComFoco(lista(a), 2, 0, 5)) }
            esperarAte(scenario, "barra própria com o modo aberto") { a -> barraDaSelecaoTextoVisivel(a) }
            scenario.onActivity { modo!!.finish() }
            shadowOf(Looper.getMainLooper()).idleFor(esperaDoDuploToque)
            scenario.onActivity { a ->
                assertTrue(barraDaSelecaoTextoVisivel(a))
                assertEquals(0, listaDoLeitor(a).selecoesAbertas)
                assertTrue(tvEm(lista(a), 2)!!.hasSelection())
            }

            // Destacar pela barra, sem o modo: abre o diálogo, desfaz a seleção e esconde a barra.
            scenario.onActivity { it.findViewById<View>(R.id.btnSelecaoTextoDestacar).performClick() }
            esperarAte(scenario, "destacar sem o modo esconde a barra e desfaz a seleção") { a ->
                !barraDaSelecaoTextoVisivel(a) && tvEm(lista(a), 2)?.hasSelection() != true &&
                    lista(a).translationY == 0f
            }
            scenario.onActivity { assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true) }

            // Voltar com a seleção viva e sem o modo: desfaz a seleção, e o Leitor continua aberto.
            scenario.onActivity { a ->
                ShadowDialog.getLatestDialog()?.dismiss()
                modo = abrirModoFalso(a, selecionarComFoco(lista(a), 1, 0, 5))
                modo!!.finish()
            }
            shadowOf(Looper.getMainLooper()).idleFor(esperaDoDuploToque)
            scenario.onActivity { a -> assertTrue(barraDaSelecaoTextoVisivel(a)) }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a ->
                assertFalse(a.isFinishing)
                assertFalse(tvEm(lista(a), 1)!!.hasSelection())
                assertFalse(barraDaSelecaoTextoVisivel(a))
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun trechoGuardadoFicaComABarraQuandoOParagrafoSaiDaTela() {
        // rb-58 (R1, R2): o parágrafo selecionado sai da tela e o trecho fica guardado, com a barra. Voltando, pinta.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { a ->
                val guardada = listaDoLeitor(a).guardada
                assertEquals(1, guardada.posicao)
                assertEquals(0, guardada.inicio)
                assertEquals(5, guardada.fim)
                assertTrue(barraDaSelecaoTextoVisivel(a))
            }
            // Nenhum TextView da tela fica com seleção nativa: a seleção saiu junto com o parágrafo.
            esperarAte(scenario, "sem seleção nativa na tela") { a ->
                (0 until lista(a).childCount).none { (lista(a).getChildAt(it) as? TextView)?.hasSelection() == true }
            }

            // R2: rolar de volta mostra o trecho pintado, e ele sobrevive a uma repintura do item.
            scenario.onActivity { lista(it).scrollToPosition(0) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            scenario.onActivity { a ->
                val tv = tvEm(lista(a), 1)!!
                val span = spansDoTrecho(tv).single()
                assertEquals(0, (tv.text as Spannable).getSpanStart(span))
                assertEquals(5, (tv.text as Spannable).getSpanEnd(span))
                assertTrue(barraDaSelecaoTextoVisivel(a))
            }
            scenario.onActivity { lista(it).adapter?.notifyItemChanged(1) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> assertEquals(1, spansDoTrecho(tvEm(lista(a), 1)!!).size) }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun destacarPelaBarraUsaOTrechoGuardadoEEsconde() {
        // rb-58 (R3): a ação da barra age sobre o trecho guardado; depois dela, a barra some e nada fica pintado.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)

            scenario.onActivity { it.findViewById<View>(R.id.btnSelecaoTextoDestacar).performClick() }
            esperarAte(scenario, "destacar esconde a barra e limpa o trecho") { a ->
                !barraDaSelecaoTextoVisivel(a) && !listaDoLeitor(a).guardada.ativa && lista(a).translationY == 0f
            }
            scenario.onActivity { a ->
                assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
                val lista = lista(a)
                assertTrue(
                    (0 until lista.childCount).none { spansDoTrecho(lista.getChildAt(it) as TextView).isNotEmpty() },
                )
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun focoNoBotaoDaBarraNaoDesfazASelecao() {
        // rb-57: com teclado ou D-pad, o foco que vai do parágrafo selecionado para um botão da barra não desfaz a
        // seleção: o clique ainda acha o trecho.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { a ->
                selecionarComFoco(lista(a), 1, 0, 5)
                listaDoLeitor(a).liberarDepois()
            }
            esperarAte(scenario, "barra sem menu com a seleção viva") { a -> barraDaSelecaoTextoVisivel(a) }

            // D-pad: sai do modo de toque, e o foco vai para o botão Destacar da barra.
            scenario.onActivity { a ->
                definirModoDeToque(a, toque = false)
                val botao = a.findViewById<View>(R.id.btnSelecaoTextoDestacar)
                botao.requestFocus()
                // Sem o foco no botão, o teste não prova nada (o modo de toque é por reflexão).
                assumeTrue("botão não pegou foco (modo de toque)", botao.isFocused)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a ->
                assertTrue(tvEm(lista(a), 1)!!.hasSelection())
                assertTrue(barraDaSelecaoTextoVisivel(a))
            }
            // Com o foco no botão, o clique ainda acha o trecho: abre o Destacar e a seleção sai.
            scenario.onActivity { a -> a.findViewById<View>(R.id.btnSelecaoTextoDestacar).performClick() }
            esperarAte(scenario, "destacar abre e a seleção sai") { a ->
                ShadowDialog.getLatestDialog()?.isShowing == true && tvEm(lista(a), 1)?.hasSelection() == false
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun focoNoBotaoDaBarraVoltaDesfazASelecaoSemSairDoLeitor() {
        // rb-57: com o foco num botão da barra, o Voltar desfaz a seleção viva e não sai do Leitor.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { a ->
                selecionarComFoco(lista(a), 1, 0, 5)
                listaDoLeitor(a).liberarDepois()
            }
            esperarAte(scenario, "barra sem menu com a seleção viva") { a -> barraDaSelecaoTextoVisivel(a) }

            scenario.onActivity { a ->
                definirModoDeToque(a, toque = false)
                val botao = a.findViewById<View>(R.id.btnSelecaoTextoDestacar)
                botao.requestFocus()
                assumeTrue("botão não pegou foco (modo de toque)", botao.isFocused)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            esperarAte(scenario, "voltar desfaz a seleção e esconde a barra") { a ->
                tvEm(lista(a), 1)?.hasSelection() == false && !barraDaSelecaoTextoVisivel(a)
            }
            scenario.onActivity { a -> assertFalse(a.isFinishing) }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun voltarLimpaOTrechoGuardadoEOLeitorFica() {
        // rb-58 (R4): Voltar com o trecho guardado limpa o trecho e não fecha o Leitor.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            esperarAte(scenario, "voltar limpa o trecho e esconde a barra") { a ->
                !listaDoLeitor(a).guardada.ativa && !barraDaSelecaoTextoVisivel(a)
            }
            scenario.onActivity { a -> assertFalse(a.isFinishing) }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun toqueCurtoLimpaOTrechoGuardadoSemLerOParagrafo() {
        // rb-58 (R5): um toque curto limpa o trecho guardado, sem ler dali nem mexer na seleção da ViewModel.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            // Seleção de vários parágrafos aberta num parágrafo inteiro na tela (k). Um toque que lesse ampliaria a
            // seleção até o alvo, que é outro parágrafo inteiro na tela: é isso que o teste confere.
            var k = -1
            var alvo = -1
            scenario.onActivity { a ->
                val inteiros = paragrafosInteirosNaTela(lista(a))
                assertTrue("dois parágrafos inteiros na tela", inteiros.size >= 2)
                k = inteiros[0]
                alvo = inteiros[1]
                modeloDe(a).onParagrafoLongPress(k)
            }
            var inicioAntes: Int? = null
            var fimAntes: Int? = null
            var sincAntes = -1
            scenario.onActivity { a ->
                inicioAntes = modeloDe(a).uiState.value.selecaoInicio
                fimAntes = modeloDe(a).uiState.value.selecaoFim
                sincAntes = modeloDe(a).uiState.value.paragrafoSyncAtual
            }
            assertEquals(k, inicioAntes)
            assertEquals(k, fimAntes)

            evento(scenario, alvo, MotionEvent.ACTION_DOWN, 1_000L, 1_000L)
            evento(scenario, alvo, MotionEvent.ACTION_UP, 1_000L, 1_050L)
            esperarAte(scenario, "toque curto limpa o trecho e esconde a barra") { a ->
                !listaDoLeitor(a).guardada.ativa && !barraDaSelecaoTextoVisivel(a)
            }
            scenario.onActivity { a ->
                assertEquals(inicioAntes, modeloDe(a).uiState.value.selecaoInicio)
                assertEquals(fimAntes, modeloDe(a).uiState.value.selecaoFim)
                assertEquals(sincAntes, modeloDe(a).uiState.value.paragrafoSyncAtual)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun listaNovaLimpaOTrechoGuardadoEEscondeABarra() {
        // rb-58 (R6): trocar a lista (aqui, o modo de edição) limpa o trecho guardado e esconde a barra.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)

            scenario.onActivity { modeloDe(it).ativarModoEdicao() }
            esperarAte(scenario, "modo de edição limpa o trecho e esconde a barra") { a ->
                !listaDoLeitor(a).guardada.ativa && !barraDaSelecaoTextoVisivel(a)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    // ── Livro de teste (cache, sem Python) ────────────────────────────────────

    /** Dois capítulos: a primeira metade dos parágrafos no primeiro (0 a 4 nos 10 de sempre), o resto no segundo. */
    private fun livroDeTeste(textos: List<String> = PARAGRAFOS): TextoExtraido {
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
     * Grava o arquivo e põe o texto nos dois caches (bruto e limpo, iguais), com as mesmas chaves que o app usa. Assim
     * ExtractTextUseCase e TextoNarravelDoLivro.limpar acham o texto e não chamam o Python.
     *
     * Cada teste grava o livro numa pasta própria (UUID no caminho). O caminho entra na chave do cache e na trava do
     * ExtractTextUseCase, que são estáticas: se dois testes usassem o mesmo caminho, um herdaria o estado do outro.
     */
    private fun gravarLivroNoCache(texto: TextoExtraido, textos: List<String> = PARAGRAFOS): File {
        // O arquivo precisa existir antes da chave: a chave leva tamanho e data de modificação.
        val f = File(app.filesDir, "fumaca/${UUID.randomUUID()}/$NOME.txt")
        f.parentFile?.mkdirs()
        f.writeText(textos.joinToString("\n\n"))
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

    // A lista tipada: textoEmUso e selecoesAbertas só existem em RecyclerViewParagrafos (rb-54).
    private fun listaDoLeitor(a: ReaderActivity): RecyclerViewParagrafos = a.findViewById(R.id.recyclerViewParagrafos)

    /** O mesmo ViewModel da tela (o da Activity, pelo dono), para simular o menu e os toques. */
    private fun modeloDe(a: ReaderActivity): ReaderViewModel = ViewModelProvider(a)[ReaderViewModel::class.java]

    /**
     * Põe a janela do Leitor no modo de toque (rb-55). O Robolectric não entra nele sozinho, e o
     * InstrumentationRegistry não está no classpath da JVM: o pedido vai à ViewRootImpl por reflexão (a API é oculta).
     * Sem a API, não faz nada.
     */
    private fun entrarNoModoDeToque(a: ReaderActivity) = definirModoDeToque(a, toque = true)

    /** Liga ou desliga o modo de toque da janela (rb-57 sai dele, para o foco ir pelo D-pad). Por reflexão. */
    private fun definirModoDeToque(a: ReaderActivity, toque: Boolean) {
        runCatching {
            val raiz = View::class.java.getDeclaredMethod("getViewRootImpl")
                .apply { isAccessible = true }
                .invoke(a.window.decorView)
            raiz.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(raiz, toque)
        }
    }

    /**
     * Seleciona [pos] (0 a 5) com foco e rola a lista até ele sair da tela (rb-58): o trecho fica guardado, e a barra
     * segue acesa sem o menu do sistema.
     */
    private fun guardarTrechoDoParagrafo(scenario: ActivityScenario<ReaderActivity>, pos: Int) {
        // O UP do toque longo dispara a conferência (liberarDepois); aqui ela é disparada à mão.
        scenario.onActivity { a ->
            selecionarComFoco(lista(a), pos, 0, 5)
            listaDoLeitor(a).liberarDepois()
        }
        esperarAte(scenario, "barra sem menu com a seleção viva") { a -> barraDaSelecaoTextoVisivel(a) }
        scenario.onActivity { a -> rolarAteSair(lista(a), pos) }
        esperarAte(scenario, "trecho guardado com a barra acesa") { a ->
            barraDaSelecaoTextoVisivel(a) && listaDoLeitor(a).guardada.ativa
        }
    }

    /** Rola a lista para baixo até nenhum filho ligado mostrar a posição [pos] (o parágrafo saiu da tela). */
    private fun rolarAteSair(rv: RecyclerView, pos: Int) {
        var vezes = 0
        while (estaNaTela(rv, pos) && vezes++ < 50) rv.scrollBy(0, 300)
    }

    private fun estaNaTela(rv: RecyclerView, pos: Int): Boolean =
        (0 until rv.childCount).any { rv.getChildViewHolder(rv.getChildAt(it))?.bindingAdapterPosition == pos }

    /** Posições dos parágrafos inteiros na lista (topo e base dentro da área da lista), em ordem. */
    private fun paragrafosInteirosNaTela(rv: RecyclerView): List<Int> =
        (0 until rv.childCount).mapNotNull { i ->
            val filho = rv.getChildAt(i)
            val pos = rv.getChildViewHolder(filho)?.bindingAdapterPosition ?: return@mapNotNull null
            pos.takeIf { filho.top >= 0 && filho.bottom <= rv.height }
        }.sorted()

    /** Spans do trecho guardado (rb-58) no TextView. Texto que não é Spannable não tem nenhum. */
    private fun spansDoTrecho(tv: TextView): Array<SpanDoTrechoGuardado> {
        val texto = tv.text as? Spannable ?: return emptyArray()
        return texto.getSpans(0, texto.length, SpanDoTrechoGuardado::class.java)
    }

    /** Barra "N parágrafos selecionados" (rb-30), visível ou não. */
    private fun barraDeSelecaoVisivel(a: ReaderActivity): Boolean =
        a.findViewById<View>(R.id.layoutBarraSelecao).visibility == View.VISIBLE

    private fun textoDaBarra(a: ReaderActivity): String =
        a.findViewById<TextView>(R.id.tvSelecaoParagrafos).text.toString()

    private fun plural(a: ReaderActivity, quantos: Int): String =
        a.resources.getQuantityString(R.plurals.barra_selecao_paragrafos, quantos, quantos)

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

    /** Texto do parágrafo na rolagem vertical, em que o item é o próprio TextView (na horizontal, ver paginaEm). */
    private fun textoEm(rv: RecyclerView, pos: Int): String? =
        (rv.findViewHolderForAdapterPosition(pos)?.itemView as? TextView)?.text?.toString()

    /** TextView do parágrafo na rolagem vertical (o item é o próprio TextView); nulo se ele não está na tela. */
    private fun tvEm(rv: RecyclerView, pos: Int): TextView? =
        rv.findViewHolderForAdapterPosition(pos)?.itemView as? TextView

    /** Página do parágrafo na rolagem horizontal (rb-26a); nulo na vertical. */
    private fun paginaEm(rv: RecyclerView, pos: Int): PaginaDoParagrafo? =
        rv.findViewHolderForAdapterPosition(pos)?.itemView as? PaginaDoParagrafo

    private fun intencaoSomenteLeitura(livro: File): Intent =
        Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, livro.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

    /** Manda um toque à lista sobre o item [pos]: [acao] (DOWN, UP ou CANCEL), com os tempos dados e o x + [dx]. */
    private fun evento(
        scenario: ActivityScenario<ReaderActivity>,
        pos: Int,
        acao: Int,
        downTime: Long,
        eventTime: Long,
        dx: Float = 0f,
    ) {
        scenario.onActivity { a -> despacharToque(lista(a), pos, acao, downTime, eventTime, dx) }
    }

    /**
     * Despacha só o evento na [rv] sobre o item [pos], sem abrir onActivity: quem chama já está dentro de um (o teste
     * que precisa de repintura e toque na mesma volta, rb-53). O x é o centro do item + [dx].
     */
    private fun despacharToque(
        rv: RecyclerView,
        pos: Int,
        acao: Int,
        downTime: Long,
        eventTime: Long,
        dx: Float = 0f,
    ) {
        val item = requireNotNull(rv.findViewHolderForAdapterPosition(pos)).itemView
        val x = item.left + item.width / 2f + dx
        val y = item.top + item.height / 2f
        val ev = MotionEvent.obtain(downTime, eventTime, acao, x, y, 0)
        rv.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Fundo do parágrafo na rolagem vertical: com a barra de sincronia, é um LayerDrawable (pintarFundoParagrafo). */
    private fun fundoDoParagrafo(rv: RecyclerView, pos: Int): Drawable? =
        rv.findViewHolderForAdapterPosition(pos)?.itemView?.background

    /** Se o TextView aceita seleção agora (canSelectText, método oculto do SDK: por reflexão). */
    private fun podeSelecionar(tv: TextView): Boolean =
        TextView::class.java.getDeclaredMethod("canSelectText").apply { isAccessible = true }.invoke(tv) == true

    /** Barra própria das ações da seleção de texto (rb-38), visível ou não. */
    private fun barraDaSelecaoTextoVisivel(a: ReaderActivity): Boolean =
        a.findViewById<View>(R.id.barraSelecaoTexto).visibility == View.VISIBLE

    private fun botaoVisivel(a: ReaderActivity, id: Int): Boolean = a.findViewById<View>(id).visibility == View.VISIBLE

    /** Põe o foco no parágrafo [pos] e a seleção [ini, fim) nele, como o toque longo (rb-54). Devolve o TextView. */
    private fun selecionarComFoco(rv: RecyclerView, pos: Int, ini: Int, fim: Int): TextView {
        val tv = requireNotNull(tvEm(rv, pos))
        tv.requestFocus()
        Selection.setSelection(tv.text as Spannable, ini, fim)
        return tv
    }

    /** Abre o menu da seleção do [tv] com um ActionMode de mentira (rb-54): o callback dele é o que abre a barra. */
    private fun abrirModoFalso(a: ReaderActivity, tv: TextView): ModoFalso {
        val cb = tv.customSelectionActionModeCallback
        val menu = PopupMenu(a, tv).menu
        return ModoFalso(cb, menu).also { cb.onCreateActionMode(it, menu) }
    }

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

    /**
     * ActionMode de mentira: o Robolectric não monta o da seleção nativa. Só o que o menu da seleção usa (rb-38);
     * finish() avisa o callback, como o sistema faz ao fechar a seleção.
     */
    private class ModoFalso(private val cb: ActionMode.Callback, private val menu: Menu) : ActionMode() {
        override fun setTitle(title: CharSequence?) {}
        override fun setTitle(resId: Int) {}
        override fun setSubtitle(subtitle: CharSequence?) {}
        override fun setSubtitle(resId: Int) {}
        override fun invalidate() {}
        override fun finish() = cb.onDestroyActionMode(this)
        override fun getMenu(): Menu = menu
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun setCustomView(view: View?) {}
        override fun getMenuInflater(): MenuInflater = throw UnsupportedOperationException("sem inflater no teste")
    }
}
