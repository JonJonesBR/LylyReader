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
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState
import com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
import com.jonjonesbr.audiobookgen.util.PythonInicio
import com.jonjonesbr.audiobookgen.util.dpToPx
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
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
        // A costura e a ponte da guiada são estáticas: não podem vazar para o próximo teste (rb-15).
        TtsSynthesizer.substitutoParaTestes = null
        GuidedPlaybackBridge.manager?.let { it.release(); GuidedPlaybackBridge.deactivate(it) }
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
    fun painelDeCapitulosNaoVazaAoGirar() {
        // rb-18: o painel aberto fecha junto com a Activity; a folha antiga não fica como janela vazada.
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { it.findViewById<View>(R.id.areaCapituloAtual).performClick() }
            val painel = requireNotNull(ShadowDialog.getLatestDialog() as? BottomSheetDialog) {
                "Painel de capítulos não abriu"
            }
            assertTrue("Painel de capítulos não abriu", painel.isShowing)

            scenario.recreate()
            esperarAte(scenario, "tela recriada com os parágrafos") { a ->
                val p = primeiroVisivel(a)
                p >= 0 && textoEm(lista(a), p) != null
            }
            assertFalse("Painel antigo ficou aberto depois de girar (janela vazada)", painel.isShowing)
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun painelDeCapitulosReabreAoGirarComOsMarcados() {
        // rb-18: girar a tela com o painel aberto reabre o painel, com os capítulos marcados ainda marcados.
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { modeloDe(it).capitulosSelecionados.add(1) }
            // Vai ao capítulo 2 antes de girar: o painel reaberto precisa manter o capítulo atual, não o 1.
            scenario.onActivity { it.findViewById<View>(R.id.btnCapituloProximo).performClick() }
            esperarAte(scenario, "barra no capítulo 2") { a ->
                primeiroVisivel(a) >= 5 && rotuloDaBarra(a) == rotuloDoCapitulo(a, 2, "Capítulo 2")
            }
            scenario.onActivity { it.findViewById<View>(R.id.areaCapituloAtual).performClick() }
            val antes = requireNotNull(ShadowDialog.getLatestDialog() as? BottomSheetDialog) {
                "Painel de capítulos não abriu"
            }
            assertTrue("Painel de capítulos não abriu", antes.isShowing)

            scenario.recreate()
            esperarAte(scenario, "painel reaberto depois de girar") { _ ->
                val painel = ShadowDialog.getLatestDialog()
                painel !== antes && painel is BottomSheetDialog && painel.isShowing
            }
            val painel = requireNotNull(ShadowDialog.getLatestDialog() as? BottomSheetDialog)
            val rvCapitulos = painel.findViewById<RecyclerView>(R.id.rvCapitulosToc)
            assertEquals(2, rvCapitulos?.adapter?.itemCount)
            assertEquals(
                "Capítulo atual do painel reaberto não é o 2",
                1,
                (rvCapitulos?.adapter as? CapituloTocAdapter)?.posicaoDoAtual()
            )
            assertEquals(
                View.VISIBLE,
                requireNotNull(painel.findViewById<View>(R.id.linhaCapitulosConverter)).visibility
            )
            scenario.onActivity { a -> assertEquals(setOf(1), modeloDe(a).capitulosSelecionados) }
            painel.dismiss()
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun painelFechadoPeloUsuarioNaoReabreAoGirar() {
        // rb-18: o painel que o usuário fechou não volta depois de girar a tela.
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { it.findViewById<View>(R.id.areaCapituloAtual).performClick() }
            val painel = requireNotNull(ShadowDialog.getLatestDialog() as? BottomSheetDialog) {
                "Painel de capítulos não abriu"
            }
            painel.dismiss()

            scenario.recreate()
            esperarAte(scenario, "tela recriada com os parágrafos") { a ->
                val p = primeiroVisivel(a)
                p >= 0 && textoEm(lista(a), p) != null
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            val ultimo = ShadowDialog.getLatestDialog()
            assertFalse(
                "Painel fechado pelo usuário reabriu depois de girar",
                ultimo is BottomSheetDialog && ultimo.isShowing
            )
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun pilulaDaLimpezaNaoVoltaAoGirar() {
        // rb-18: a pílula aparece uma vez por abertura; girar a tela não a traz de volta.
        arquivo = gravarLivroNoCache(livroDeTeste(), removidas = 3)
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "pílula da limpeza visível") { a -> pilulaVisivel(a) }

            scenario.recreate()
            esperarAte(scenario, "tela recriada com os parágrafos") { a ->
                val p = primeiroVisivel(a)
                p >= 0 && textoEm(lista(a), p) != null
            }
            scenario.onActivity { a -> assertFalse("A pílula voltou depois de girar", pilulaVisivel(a)) }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun capitulosMarcadosFicamAoGirar() {
        // rb-18: a seleção de capítulos vive no ViewModel; o FAB de conversão depende dela.
        // Fora do "Ler sem áudio", porque lá não há marcação de capítulos nem FAB de conversão.
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, arquivo.absolutePath)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            scenario.onActivity { modeloDe(it).capitulosSelecionados.add(1) }

            scenario.recreate()
            esperarAte(scenario, "tela recriada com os parágrafos") { a ->
                val p = primeiroVisivel(a)
                p >= 0 && textoEm(lista(a), p) != null
            }
            scenario.onActivity { a ->
                assertEquals(setOf(1), modeloDe(a).capitulosSelecionados)
                assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fabConverterPrincipal).visibility)
            }
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
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun notifyNaPaginaMantemARolagemETextoNovoVoltaAoTopo() {
        // rb-26c: um notify no mesmo parágrafo mantém a rolagem de quem lê; texto novo no mesmo lugar volta ao topo.
        val textos = PARAGRAFOS.toMutableList().apply { this[1] = PARAGRAFO_LONGO }
        val livro = gravarLivroNoCache(livroDeTeste(textos), textos)
        AppPrefs(app).readerRolagemHorizontal = true
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, livro.absolutePath)
            .putExtra(ReaderActivity.EXTRA_SOMENTE_LEITURA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "página do parágrafo longo com o texto") { a ->
                (lista(a).layoutManager as? LinearLayoutManager)?.orientation == LinearLayoutManager.HORIZONTAL &&
                    paginaEm(lista(a), 1)?.texto?.text?.toString() == PARAGRAFO_LONGO
            }
            // Vai ao parágrafo longo de verdade, e não só pela vizinha na margem: a página 1 fica inteira na tela.
            scenario.onActivity { lista(it).scrollToPosition(1) }
            esperarAte(scenario, "página do parágrafo longo inteira na tela") { a ->
                val lm = lista(a).layoutManager as LinearLayoutManager
                lm.findFirstCompletelyVisibleItemPosition() == 1 && paginaEm(lista(a), 1) != null
            }

            // Rolagem de quem lê e um notify no mesmo parágrafo (Destacar, busca): a página continua onde estava.
            scenario.onActivity { a ->
                paginaEm(lista(a), 1)!!.scrollTo(0, 40)
                (lista(a).adapter as ParagrafoAdapter).notifyItemChanged(1)
                lista(a).requestLayout()
            }
            esperarAte(scenario, "notify repintado") { a -> !lista(a).isLayoutRequested }
            scenario.onActivity { a -> assertEquals(40, paginaEm(lista(a), 1)!!.scrollY) }

            // Texto novo no mesmo lugar (limpeza, Retirar, fim da edição): a página volta ao topo.
            scenario.onActivity { a ->
                val novos = textos.mapIndexed { i, t ->
                    Paragrafo(if (i == 1) "$t Texto trocado." else t, charOffset = 0, indiceCapitulo = 0)
                }
                (lista(a).adapter as ParagrafoAdapter).atualizarEstado(
                    paragrafos = novos,
                    selecaoInicio = null,
                    selecaoFim = null,
                    syncAtual = -1,
                    progressPct = 0f,
                    tamanhoFonte = 16f,
                    espacamento = 1.5f,
                    tema = TemaLeitura.CLARO,
                )
            }
            esperarAte(scenario, "texto novo na página") { a ->
                paginaEm(lista(a), 1)?.texto?.text?.toString()?.endsWith("Texto trocado.") == true
            }
            scenario.onActivity { a -> assertEquals(0, paginaEm(lista(a), 1)!!.scrollY) }
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
    fun voltarComFocoNaBarraELTrechoGuardadoLimpaTudoDeUmaVez() {
        // rb-57 com rb-58: a seleção antiga (no TextView desanexado) e o trecho guardado saem juntos num Voltar, e a
        // barra some. Antes, o Voltar só desfazia a seleção e o trecho ficava até um segundo Voltar.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)

            scenario.onActivity { a ->
                definirModoDeToque(a, toque = false)
                val botao = a.findViewById<View>(R.id.btnSelecaoTextoDestacar)
                botao.requestFocus()
                assumeTrue("botão não pegou foco (modo de toque)", botao.isFocused)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            esperarAte(scenario, "um voltar limpa a seleção e o trecho e esconde a barra") { a ->
                !listaDoLeitor(a).guardada.ativa && !barraDaSelecaoTextoVisivel(a) &&
                    listaDoLeitor(a).textoComSelecaoViva == null
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
    fun toqueCurtoNoTrechoGuardadoReabreASelecao() {
        // rb-60: um toque curto dentro do trecho guardado traz a seleção de volta, no mesmo trecho, com foco e com a
        // barra. O guardado sai (ele virou seleção viva).
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { lista(it).scrollToPosition(0) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            var ponto = 0f to 0f
            scenario.onActivity { a -> ponto = pontoNoCaractere(tvEm(lista(a), 1)!!, 1) }
            scenario.onActivity { a -> toqueNaLista(lista(a), ponto, MotionEvent.ACTION_DOWN, 1_000L, 1_000L) }
            scenario.onActivity { a -> toqueNaLista(lista(a), ponto, MotionEvent.ACTION_UP, 1_000L, 1_050L) }
            esperarAte(scenario, "toque no trecho tira o guardado e reabre a seleção com a barra") { a ->
                !listaDoLeitor(a).guardada.ativa && barraDaSelecaoTextoVisivel(a)
            }
            scenario.onActivity { a ->
                val tv = tvEm(lista(a), 1)!!
                assertTrue("O parágrafo deveria ter o foco depois do toque", tv.isFocused)
                assertEquals("A seleção reaberta deveria ser o trecho guardado", 0, tv.selectionStart)
                assertEquals("A seleção reaberta deveria ser o trecho guardado", 5, tv.selectionEnd)
                assertEquals(0, spansDoTrecho(tv).size)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun trechoGuardadoInteiroNaTelaVoltaSozinhoQuandoAListaPara() {
        // rb-60b (1): o trecho guardado volta com o parágrafo inteiro na área útil, e a seleção nativa volta sem toque
        // quando a lista para. A rolagem de 1 px que gera o IDLE não pode levar a lista além disso.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { a -> rolarParaParagrafoUmComFolga(a, folgaDp = 64) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            scenario.onActivity { a ->
                val tv = tvEm(lista(a), 1)!!
                val limite = limiteDaAreaNaTela(a)
                assertTrue(
                    "Pré-condição: a linha 0 (${linha0NaTela(tv)}) deveria ficar abaixo da barra ($limite)",
                    linha0NaTela(tv) >= limite,
                )
                // Mede como a produção mede (rb-60b): o trecho inteiro na área útil, antes do 1 px.
                val medidas = checkNotNull(medidasDaReabertura(a)) {
                    "Pré-condição: o trecho guardado não foi medido. ${diagnosticoDaReabertura(a)}"
                }
                assertEquals(
                    "Pré-condição: a barra deveria estar visível. ${diagnosticoDaReabertura(a)}",
                    limite,
                    medidas.limiteDaBarra,
                )
                assertTrue(
                    "Pré-condição: o topo da linha do fim (${medidas.linhaDoFim.topo}) deveria estar abaixo da " +
                        "barra ($limite). ${diagnosticoDaReabertura(a)}",
                    medidas.linhaDoFim.topo >= limite,
                )
                assertTrue(
                    "Pré-condição: o trecho inteiro deveria estar na área útil. ${diagnosticoDaReabertura(a)}",
                    medidas.trechoInteiroNaTela,
                )
                assertTrue(
                    "Pré-condição: o fundo da linha do fim (${medidas.linhaDoFim.fundo}) deveria caber na área " +
                        "útil (fim ${medidas.area.fundo}). ${diagnosticoDaReabertura(a)}",
                    medidas.linhaDoFim.fundo <= medidas.area.fundo,
                )
            }
            scenario.onActivity { a -> focarOutroParagrafoInteiro(a, 1) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> conferirFocoNaLista(a) }
            var antes = 0
            scenario.onActivity { a ->
                antes = lista(a).computeVerticalScrollOffset()
                lista(a).smoothScrollBy(0, 1)
            }
            esperarAte(scenario, "lista parada depois do 1 px") { a ->
                lista(a).scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
            // A conferência roda no IDLE (OnScrollListener do SelecaoSemMenu, por post). No Robolectric a rolagem
            // de 1 px pode não passar por SETTLING até IDLE; então a conferência é disparada aqui, como o fim da
            // rolagem faria.
            scenario.onActivity { listaDoLeitor(it).liberarDepois() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            esperarAte(
                scenario,
                "lista parada e alças de volta sem toque",
                { a -> diagnosticoDaReabertura(a) },
            ) { a ->
                lista(a).scrollState == RecyclerView.SCROLL_STATE_IDLE && !listaDoLeitor(a).guardada.ativa &&
                    tvEm(lista(a), 1)?.hasSelection() == true
            }
            scenario.onActivity { a ->
                val tv = tvEm(lista(a), 1)!!
                assertTrue("O parágrafo deveria ter o foco", tv.isFocused)
                assertEquals("A seleção deveria ser o trecho guardado", 0, tv.selectionStart)
                assertEquals("A seleção deveria ser o trecho guardado", 5, tv.selectionEnd)
                assertEquals(0, spansDoTrecho(tv).size)
                assertTrue(barraDaSelecaoTextoVisivel(a))
                assertTrue(
                    "A lista não deveria rolar além de 1 px (antes ${antes}, depois ${lista(a).computeVerticalScrollOffset()})",
                    lista(a).computeVerticalScrollOffset() - antes <= 1,
                )
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun trechoGuardadoSobABarraNaoVoltaSozinho() {
        // rb-60b (2): com a primeira linha sob a barra, o trecho fica guardado e pintado quando a lista para.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { a -> rolarParaParagrafoUmComFolga(a, folgaDp = -24) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            scenario.onActivity { a ->
                val tv = tvEm(lista(a), 1)!!
                val limite = limiteDaAreaNaTela(a)
                assertTrue(
                    "Pré-condição: a linha 0 (${linha0NaTela(tv)}) deveria ficar sob a barra ($limite)",
                    linha0NaTela(tv) < limite,
                )
            }
            scenario.onActivity { a -> focarOutroParagrafoInteiro(a, 1) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> conferirFocoNaLista(a) }
            scenario.onActivity { lista(it).smoothScrollBy(0, 1) }
            esperarAte(scenario, "lista parada") { lista(it).scrollState == RecyclerView.SCROLL_STATE_IDLE }
            scenario.onActivity { listaDoLeitor(it).liberarDepois() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            scenario.onActivity { a ->
                assertTrue("Sob a barra, o trecho deveria continuar guardado", listaDoLeitor(a).guardada.ativa)
                val tv = tvEm(lista(a), 1)!!
                assertFalse("Sob a barra, a seleção não deveria voltar sozinha", tv.hasSelection())
                assertEquals(1, spansDoTrecho(tv).size)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun comDedoNaTelaOTrechoNaoVoltaSozinho() {
        // rb-60b (3): com o dedo na lista, a conferência não reabre. Ao levantar o dedo (CANCEL), a seleção volta.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { a -> rolarParaParagrafoUmComFolga(a, folgaDp = 64) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            // O foco vai para o outro parágrafo inteiro, e o DOWN e o CANCEL vão para ele: o toque não muda o foco.
            var outro = -1
            scenario.onActivity { a -> outro = focarOutroParagrafoInteiro(a, 1) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> conferirFocoNaLista(a) }
            // DOWN num outro parágrafo da tela, e a conferência rodando com o dedo ainda na lista.
            scenario.onActivity { a ->
                val rv = listaDoLeitor(a)
                despacharToque(rv, outro, MotionEvent.ACTION_DOWN, 1_000L, 1_000L)
                rv.liberarDepois()
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            scenario.onActivity { a ->
                assertTrue("Com o dedo na tela, o trecho deveria continuar guardado", listaDoLeitor(a).guardada.ativa)
                assertFalse(tvEm(lista(a), 1)?.hasSelection() == true)
            }
            scenario.onActivity { a -> despacharToque(listaDoLeitor(a), outro, MotionEvent.ACTION_CANCEL, 1_000L, 1_300L) }
            esperarAte(scenario, "sem o dedo, o trecho volta com as alças") { a ->
                !listaDoLeitor(a).guardada.ativa && tvEm(lista(a), 1)?.hasSelection() == true
            }
            scenario.onActivity { a ->
                val tv = tvEm(lista(a), 1)!!
                assertEquals(0, tv.selectionStart)
                assertEquals(5, tv.selectionEnd)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun trechoGuardadoComFocoForaDaListaNaoVoltaSozinho() {
        // rb-60b (5): a mesma posição do caso 1, mas com o foco num botão fora da lista (busca, barra, teclado).
        // Com o foco fora, a conferência não reabre: o trecho continua guardado e sem seleção, com a lista parada.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { a -> rolarParaParagrafoUmComFolga(a, folgaDp = 64) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            // Com o trecho inteiro na área útil, quem impede a reabertura é só o foco fora da lista.
            scenario.onActivity { a ->
                val medidas = checkNotNull(medidasDaReabertura(a)) {
                    "Pré-condição: o trecho guardado não foi medido. ${diagnosticoDaReabertura(a)}"
                }
                assertTrue(
                    "Pré-condição: o trecho inteiro deveria estar na área útil. ${diagnosticoDaReabertura(a)}",
                    medidas.trechoInteiroNaTela,
                )
            }
            scenario.onActivity { a ->
                // Sem o foco no botão, o teste não prova nada (o modo de toque é por reflexão, como no rb-57).
                definirModoDeToque(a, toque = false)
                val botao = a.findViewById<View>(R.id.btnReaderVoltar)
                botao.requestFocus()
                assumeTrue("botão não pegou foco (modo de toque)", botao.isFocused)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a ->
                assertTrue("Pré-condição: o trecho deveria continuar guardado", listaDoLeitor(a).guardada.ativa)
            }
            scenario.onActivity { lista(it).smoothScrollBy(0, 1) }
            esperarAte(scenario, "lista parada") { lista(it).scrollState == RecyclerView.SCROLL_STATE_IDLE }
            scenario.onActivity { listaDoLeitor(it).liberarDepois() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            scenario.onActivity { a ->
                assertTrue(
                    "Com o foco fora da lista, o trecho deveria continuar guardado",
                    listaDoLeitor(a).guardada.ativa,
                )
                val tv = tvEm(lista(a), 1)!!
                assertFalse("Com o foco fora da lista, a seleção não deveria voltar sozinha", tv.hasSelection())
                assertEquals(1, spansDoTrecho(tv).size)
                assertTrue(
                    "O foco deveria continuar no botão Voltar",
                    a.findViewById<View>(R.id.btnReaderVoltar).isFocused,
                )
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun toqueQueParaOFlingNaoLimpaOTrechoGuardado() {
        // rb-60b (4): o toque que para um fling (lista rolando no DOWN) não limpa o trecho nem lê o parágrafo. Quando a
        // lista para, com o trecho inteiro na área útil, a seleção volta sozinha (rb-60 e rb-60b).
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { a -> rolarParaParagrafoUmComFolga(a, folgaDp = 64) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            scenario.onActivity { a -> focarOutroParagrafoInteiro(a, 1) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            scenario.onActivity { a -> conferirFocoNaLista(a) }
            var ponto = 0f to 0f
            var sincAntes = -1
            scenario.onActivity { a ->
                ponto = pontoNoCaractere(tvEm(lista(a), 1)!!, 1)
                sincAntes = modeloDe(a).uiState.value.paragrafoSyncAtual
            }
            // Fling e DOWN no mesmo despacho: sem deixar a lista andar entre os dois.
            scenario.onActivity { a ->
                lista(a).fling(0, 8_000)
                assertEquals(
                    "O fling deveria deixar a lista em SETTLING antes do toque",
                    RecyclerView.SCROLL_STATE_SETTLING,
                    lista(a).scrollState,
                )
                toqueNaLista(lista(a), ponto, MotionEvent.ACTION_DOWN, 1_000L, 1_000L)
            }
            scenario.onActivity { a -> toqueNaLista(lista(a), ponto, MotionEvent.ACTION_UP, 1_000L, 1_050L) }
            esperarAte(scenario, "o fim do fling devolve as alças do trecho") { a ->
                !listaDoLeitor(a).guardada.ativa && lista(a).scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    tvEm(lista(a), 1)?.hasSelection() == true
            }
            scenario.onActivity { a ->
                val tv = tvEm(lista(a), 1)!!
                assertEquals(0, tv.selectionStart)
                assertEquals(5, tv.selectionEnd)
                assertTrue(barraDaSelecaoTextoVisivel(a))
                assertEquals(
                    "O toque que para o fling não deveria ler o parágrafo",
                    sincAntes,
                    modeloDe(a).uiState.value.paragrafoSyncAtual,
                )
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    fun toqueNoMesmoParagrafoForaDoTrechoLimpaOGuardado() {
        // rb-60: o toque fora do trecho, no mesmo parágrafo, continua limpando (rb-58 R5) e não reabre a seleção.
        ActivityScenario.launch<ReaderActivity>(intencaoSomenteLeitura(arquivo)).use { scenario ->
            esperarAte(scenario, "parágrafos na tela") { a ->
                lista(a).adapter?.itemCount == PARAGRAFOS.size && textoEm(lista(a), 0) != null
            }
            guardarTrechoDoParagrafo(scenario, 1)
            scenario.onActivity { lista(it).scrollToPosition(0) }
            esperarAte(scenario, "parágrafo 1 de volta com o trecho pintado") { a ->
                tvEm(lista(a), 1) != null && spansDoTrecho(tvEm(lista(a), 1)!!).size == 1
            }
            var ponto = 0f to 0f
            scenario.onActivity { a -> ponto = pontoNoCaractere(tvEm(lista(a), 1)!!, 10) }
            scenario.onActivity { a -> toqueNaLista(lista(a), ponto, MotionEvent.ACTION_DOWN, 1_000L, 1_000L) }
            scenario.onActivity { a -> toqueNaLista(lista(a), ponto, MotionEvent.ACTION_UP, 1_000L, 1_050L) }
            esperarAte(scenario, "toque fora do trecho limpa o guardado e esconde a barra") { a ->
                !listaDoLeitor(a).guardada.ativa && !barraDaSelecaoTextoVisivel(a)
            }
            scenario.onActivity { a -> assertFalse(tvEm(lista(a), 1)?.hasSelection() == true) }
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

    @Test
    fun autoInicioComUmCapituloTocaNoTextoLimpo() {
        // rb-15: com 0 ou 1 capítulo a guiada começa sozinha, e tem de usar os parágrafos limpos, não os brutos.
        val textosLimpos = listOf(
            "Primeira frase do livro.",
            "Segunda frase do livro.",
            "Terceira frase da história.",
            "Quarta frase do texto.",
            "Quinta frase do capítulo.",
            "Sexta frase final.",
        )
        // O bruto tem três linhas de lixo antes do texto limpo. O capítulo começa na 6ª linha do bruto.
        val textosDoBruto = listOf("Capa da edição.", "Sumário.", "Ficha técnica.") + textosLimpos
        val limpo = TextoExtraido(
            capitulos = listOf(Capitulo("Capítulo 1", 2, 5)),
            paragrafos = paragrafosSeguidos(textosLimpos),
            totalChars = textosLimpos.sumOf { it.length + 2 },
        )
        val bruto = TextoExtraido(
            capitulos = listOf(Capitulo("Capítulo 1", 5, 8)),
            paragrafos = paragrafosSeguidos(textosDoBruto),
            totalChars = textosDoBruto.sumOf { it.length + 2 },
        )
        val livro = gravarLivroNoCache(bruto, textosDoBruto, textoLimpo = limpo)

        // Voz falsa: guarda o texto pedido e espera para sempre, então ninguém chega ao MediaPlayer.
        val pedidos = CopyOnWriteArrayList<String>()
        val vozQueEspera = object : TtsSynthesizer {
            override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
                pedidos.add(texto)
                awaitCancellation()
            }
        }
        TtsSynthesizer.substitutoParaTestes = { vozQueEspera }
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, livro.absolutePath)
            .putExtra(ReaderActivity.EXTRA_AUTO_START_GUIDED, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "guiada começou") {
                (GuidedPlaybackBridge.manager?.state?.value?.index ?: -1) >= 0
            }
            // O início é o capítulo no texto limpo (índice 2), não o índice do bruto.
            assertEquals(2, GuidedPlaybackBridge.manager!!.state.value.index)
            assertEquals(livro.absolutePath, GuidedPlaybackBridge.caminhoArquivo)
            // O marcador não pode sair do lugar depois de a limpeza entregar o texto.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(2, GuidedPlaybackBridge.manager!!.state.value.index)
            if (pedidos.isNotEmpty()) assertTrue(limpo.paragrafos[2].texto.contains(pedidos.first()))
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun anteriorNaGuiadaMostraOInicioDoParagrafoCortadoPorCima() {
        // rb-62: o ⏮ volta a um parágrafo cuja primeira linha já está acima do topo da lista. O acompanhamento tem de
        // subir até a linha narrada (como a horizontal faz), e não ficar parado porque a linha está fora da tela.
        val textos = PARAGRAFOS.toMutableList().apply { this[1] = PARAGRAFO_LONGO }
        val livro = gravarLivroNoCache(livroDeTeste(textos), textos)
        AppPrefs(app).readerRolagemHorizontal = false
        // Continuar lendo a partir do parágrafo 2 (posição salva).
        AppPrefs(app).salvarPosicaoLeitura(livro.absolutePath, 2, textos.size)
        // Voz falsa que espera para sempre: a guiada fica parada no parágrafo narrado, sem avançar.
        val vozQueEspera = object : TtsSynthesizer {
            override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
                awaitCancellation()
            }
        }
        TtsSynthesizer.substitutoParaTestes = { vozQueEspera }
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, livro.absolutePath)
            .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            // A guiada começou no 2 e a lista assentou (sem rolagem animada em curso).
            esperarAte(scenario, "guiada no parágrafo 2 com a lista parada") { a ->
                (GuidedPlaybackBridge.manager?.state?.value?.index == 2) &&
                    lista(a).scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
            // Mostra o parágrafo 1 (longo) desde o topo, para medir a altura dele.
            scenario.onActivity { lista(it).scrollToPosition(1) }
            esperarAte(scenario, "parágrafo 1 montado") { a -> tvEm(lista(a), 1) != null }
            var altura1 = 0
            scenario.onActivity { altura1 = tvEm(lista(it), 1)!!.height }
            // Pré-condição: o parágrafo 1 começa acima do topo, com só os últimos 100 px na tela. A base da primeira
            // linha dele fica acima de 0, ou seja, já saiu da lista.
            scenario.onActivity {
                (lista(it).layoutManager as LinearLayoutManager).scrollToPositionWithOffset(1, -(altura1 - 100))
            }
            esperarAte(scenario, "parágrafo 1 com a primeira linha cortada por cima") { a ->
                val tv = tvEm(lista(a), 1)
                tv != null && tv.top < 0 && pePrimeiraLinha(tv) < 0
            }
            // O ⏮ da barra da guiada volta para o parágrafo 1.
            scenario.onActivity { it.findViewById<View>(R.id.btnGuidedPrev).performClick() }
            esperarAte(scenario, "guiada voltou para o parágrafo 1") { _ ->
                GuidedPlaybackBridge.manager?.state?.value?.index == 1
            }
            // Depois: a primeira linha do parágrafo 1 volta para dentro da lista (abaixo do topo, no limite da altura).
            esperarAte(scenario, "primeira linha do parágrafo 1 de volta à lista") { a ->
                val rv = lista(a)
                val tv = tvEm(rv, 1)
                tv != null && pePrimeiraLinha(tv) > 0 && pePrimeiraLinha(tv) <= rv.height
            }
            scenario.onActivity { _ ->
                assertEquals(1, GuidedPlaybackBridge.manager!!.state.value.index)
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun progressoQueVoltaNoMesmoParagrafoNaoSobeALista() {
        // rb-65: no mesmo parágrafo a lista só desce. Um progresso que volta (a frase seguinte do modo oração, um
        // getCurrentPosition antigo) fazia o acompanhamento subir e descer a lista; com a histerese, ela só anda para
        // a frente.
        val textos = PARAGRAFOS.toMutableList().apply { this[2] = PARAGRAFO_LONGO }
        val livro = gravarLivroNoCache(livroDeTeste(textos), textos)
        AppPrefs(app).readerRolagemHorizontal = false
        AppPrefs(app).salvarPosicaoLeitura(livro.absolutePath, 2, textos.size)
        // Voz falsa que espera para sempre: a guiada fica parada no parágrafo narrado, e só o progresso se move.
        val vozQueEspera = object : TtsSynthesizer {
            override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
                awaitCancellation()
            }
        }
        TtsSynthesizer.substitutoParaTestes = { vozQueEspera }
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, livro.absolutePath)
            .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "guiada no parágrafo 3 com a lista parada") { a ->
                (GuidedPlaybackBridge.manager?.state?.value?.index == 2) &&
                    lista(a).scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
            // Leva o progresso a 0.6 e deixa a lista assentar: cada tique rola só uma parte da distância.
            val tops = mutableListOf<Int>()
            var anterior = Int.MIN_VALUE
            var parado = 0
            for (tique in 0 until 60) {
                val topo = emitirProgresso(scenario, 0.6f)
                parado = if (topo == anterior) parado + 1 else 0
                anterior = topo
                tops.add(topo)
                if (parado >= 2) break
            }
            // Pré-condição: com 0.3, a primeira linha do parágrafo 3 fica acima do topo (base <= 0). Sem a histerese,
            // o acompanhamento (rb-62) subiria a lista até ela, e este teste pegaria isso.
            scenario.onActivity { a ->
                val tv = checkNotNull(tvEm(lista(a), 2)) { "parágrafo 3 fora da lista" }
                assertTrue("A base da linha em 0.3 deveria ficar acima do topo", baseDaLinhaEm(tv, 0.3f) <= 0)
            }
            // Vai e volta entre 0.3 e 0.6 (quatro voltas). A lista só pode descer: o topo do parágrafo nunca cresce.
            for (pct in listOf(0.3f, 0.6f, 0.3f, 0.6f, 0.3f)) {
                tops.add(emitirProgresso(scenario, pct))
            }
            assertTrue(
                "A lista subiu com o progresso que voltou: topos $tops",
                tops.zipWithNext().all { (antes, depois) -> depois <= antes },
            )
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun trocaDeVozRecomecaOParagrafoESobeALista() {
        // rb-65: a troca de voz (e um trecho que falhou) recomeça o parágrafo que está sendo narrado, no mesmo índice.
        // A histerese não pode segurar a subida: o recomeço (progresso 0) traz a 1ª linha de volta para a lista.
        val textos = PARAGRAFOS.toMutableList().apply { this[2] = PARAGRAFO_LONGO }
        val livro = gravarLivroNoCache(livroDeTeste(textos), textos)
        AppPrefs(app).readerRolagemHorizontal = false
        AppPrefs(app).salvarPosicaoLeitura(livro.absolutePath, 2, textos.size)
        // Voz falsa que espera para sempre: a guiada fica parada no parágrafo narrado, e só o progresso se move.
        val vozQueEspera = object : TtsSynthesizer {
            override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
                awaitCancellation()
            }
        }
        TtsSynthesizer.substitutoParaTestes = { vozQueEspera }
        val intent = Intent(app, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_CAMINHO, livro.absolutePath)
            .putExtra(ReaderActivity.EXTRA_CONTINUAR_GUIADA, true)

        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            esperarAte(scenario, "guiada no parágrafo 3 com a lista parada") { a ->
                (GuidedPlaybackBridge.manager?.state?.value?.index == 2) &&
                    lista(a).scrollState == RecyclerView.SCROLL_STATE_IDLE
            }
            // Narração assentada a 0.6: o acompanhamento desce até a linha em leitura.
            emitirProgresso(scenario, 0.6f)
            // Pré-condição: a 1ª linha do parágrafo 3 já saiu por cima da lista (como depois de uma rolagem).
            scenario.onActivity { a ->
                val altura = checkNotNull(tvEm(lista(a), 2)) { "parágrafo 3 fora da lista" }.height
                (lista(a).layoutManager as LinearLayoutManager).scrollToPositionWithOffset(2, -(altura - 100))
            }
            esperarAte(scenario, "parágrafo 3 com a primeira linha cortada por cima") { a ->
                val tv = tvEm(lista(a), 2)
                tv != null && pePrimeiraLinha(tv) < 0
            }
            // A narração tocava (sem carregamento) antes da troca. Sem esse estado, o recomeço chegaria com
            // isLoading já true: o acompanhamento trataria a emissão como progresso (soProgresso), e o
            // indiceAcompanhado não zeraria (GuidedReadingBarController.atualizarGuidedPlayerUI). No app, a troca
            // sempre vem depois de tocar.
            scenario.onActivity { _ ->
                fluxoDoGuiada().value = GuidedState(index = 2, isPlaying = true, isLoading = false, progressPct = 0.6f)
            }
            // A troca de voz recomeça o parágrafo 3: progresso 0, mesmo índice, isLoading de novo (transição que
            // zera o indiceAcompanhado; a 1ª linha sobe nesse tique).
            scenario.onActivity { _ ->
                fluxoDoGuiada().value = GuidedState(index = 2, isPlaying = false, isLoading = true, progressPct = 0f)
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            // Narração retomada no mesmo índice, com progresso baixo.
            scenario.onActivity { _ ->
                fluxoDoGuiada().value = GuidedState(index = 2, isPlaying = true, isLoading = false, progressPct = 0.01f)
            }
            esperarAte(scenario, "primeira linha do parágrafo 3 de volta à lista") { a ->
                val tv = tvEm(lista(a), 2)
                tv != null && pePrimeiraLinha(tv) > 0
            }
        }
        assertTrue("Exceções não tratadas: $falhas", falhas.isEmpty())
    }

    // ── Livro de teste (cache, sem Python) ────────────────────────────────────

    /** Parágrafos num capítulo só, com offsets seguidos (como o extrator faz). */
    private fun paragrafosSeguidos(textos: List<String>): List<Paragrafo> {
        var offset = 0
        return textos.map { texto ->
            Paragrafo(texto = texto, charOffset = offset, indiceCapitulo = 0).also { offset += texto.length + 2 }
        }
    }

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
    private fun gravarLivroNoCache(
        texto: TextoExtraido,
        textos: List<String> = PARAGRAFOS,
        removidas: Int = 0,
        textoLimpo: TextoExtraido = texto,
    ): File {
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
        CacheDeTextoDoLivro.gravar(app, limpo, textoLimpo, removidas = removidas)
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

    /**
     * Ponto (x, y), em coordenadas da lista, do meio do caractere [offset] do parágrafo, na primeira linha (rb-60). O
     * TextView é o item da rolagem vertical, então o layout dele já vale nas coordenadas da lista.
     */
    private fun pontoNoCaractere(tv: TextView, offset: Int): Pair<Float, Float> {
        val layout = requireNotNull(tv.layout) { "Parágrafo sem layout" }
        val x = tv.left + tv.totalPaddingLeft +
            (layout.getPrimaryHorizontal(offset) + layout.getPrimaryHorizontal(offset + 1)) / 2f
        val y = tv.top + tv.totalPaddingTop + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
        return x to y
    }

    /** Topo da primeira linha do parágrafo [tv] na tela, em px (rb-60b). */
    private fun linha0NaTela(tv: TextView): Int {
        val posicao = IntArray(2)
        tv.getLocationOnScreen(posicao)
        return posicao[1] + tv.totalPaddingTop - tv.scrollY
    }

    /**
     * Limite de cima da área útil da seleção, na tela: a base da barra mais 72 dp, a mesma margem da descida (rb-60b).
     * Os 72 dp são a soma dos dois espaços da BarraDaSelecaoDeTexto (menu flutuante e respiro).
     */
    private fun limiteDaAreaNaTela(a: ReaderActivity): Int {
        val barra = a.findViewById<View>(R.id.barraSelecaoTexto)
        val posicao = IntArray(2)
        barra.getLocationOnScreen(posicao)
        return posicao[1] + barra.height + a.dpToPx(72)
    }

    /**
     * Rola a lista para o parágrafo 1 com o topo do item [folgaDp] dp acima do limite da área útil (rb-60b). Positiva,
     * a primeira linha cai na área útil; negativa, fica sob a barra. Ajusta pelo topo do item, não pela linha.
     */
    private fun rolarParaParagrafoUmComFolga(a: ReaderActivity, folgaDp: Int) {
        val rv = listaDoLeitor(a)
        val posicaoLista = IntArray(2)
        rv.getLocationOnScreen(posicaoLista)
        val topoDoItemNaTela = limiteDaAreaNaTela(a) + (folgaDp * a.resources.displayMetrics.density).toInt()
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(
            1,
            topoDoItemNaTela - posicaoLista[1] - rv.paddingTop,
        )
    }

    /**
     * Dá o foco a outro parágrafo inteiro na tela, que não seja o [pos] (rb-60b). É o pulo do foco que o rb-59 viu
     * no aparelho. No Robolectric, o foco some (fica nulo) quando o parágrafo com foco é reciclado, e o requestFocus
     * pode devolver false no modo de toque: o teste aceita o foco nulo (ver [conferirFocoNaLista]). Inteiro na tela,
     * o pedido de foco não rola a lista. Devolve a posição.
     */
    private fun focarOutroParagrafoInteiro(a: ReaderActivity, pos: Int): Int {
        val rv = lista(a)
        // Dentro da área com padding da lista: o RecyclerView rola para trazer o filho com foco que cai no padding.
        val outro = (0 until rv.childCount).map { rv.getChildAt(it) }
            .filter { it.top >= rv.paddingTop && it.bottom <= rv.height - rv.paddingBottom }
            .map { rv.getChildViewHolder(it).bindingAdapterPosition }
            .first { it != pos }
        requireNotNull(tvEm(rv, outro)) { "Parágrafo $outro sem TextView" }.requestFocus()
        return outro
    }

    /**
     * Pré-condição de [focarOutroParagrafoInteiro] (rb-60b): o foco está dentro da lista, ou em nenhum lugar (nulo,
     * depois de reciclar o parágrafo). Nulo não bloqueia a reabertura: a regra de produção (outroTextoEmUso) só
     * barra foco fora da lista. Foco num botão da barra ou da busca invalida o teste.
     */
    private fun conferirFocoNaLista(a: ReaderActivity) {
        val foco = a.currentFocus
        assertTrue(
            "Pré-condição: o foco deveria estar na lista ou em nenhum lugar (nulo), e está em $foco",
            foco == null || foco.estaDentroDe(lista(a)),
        )
    }

    /** Manda um toque (DOWN ou UP) em [ponto] à lista, em coordenadas da lista (rb-60). */
    private fun toqueNaLista(rv: RecyclerView, ponto: Pair<Float, Float>, acao: Int, downTime: Long, eventTime: Long) {
        val ev = MotionEvent.obtain(downTime, eventTime, acao, ponto.first, ponto.second, 0)
        rv.dispatchTouchEvent(ev)
        ev.recycle()
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

    /** A pílula da limpeza está na tela agora (visível, não só no XML). */
    private fun pilulaVisivel(a: ReaderActivity): Boolean =
        a.findViewById<View>(R.id.bannerTextoOtimizado).visibility == View.VISIBLE

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

    /** Base da primeira linha do parágrafo, medida a partir do topo da lista (rb-62). Sem layout, conta 0. */
    private fun pePrimeiraLinha(tv: TextView): Int = tv.top + tv.totalPaddingTop + (tv.layout?.getLineBottom(0) ?: 0)

    /** Base da linha do progresso [pct] dentro do parágrafo, medida a partir do topo da lista (rb-65). */
    private fun baseDaLinhaEm(tv: TextView, pct: Float): Int {
        val layout = checkNotNull(tv.layout) { "parágrafo sem layout" }
        val tamanho = tv.text.length
        val offset = (tamanho * pct).toInt().coerceIn(0, tamanho - 1)
        return tv.top + tv.totalPaddingTop + layout.getLineBottom(layout.getLineForOffset(offset))
    }

    /** StateFlow de estado do manager da guiada (rb-65): o teste emite o progresso como o polling faria. */
    @Suppress("UNCHECKED_CAST")
    private fun fluxoDoGuiada(): MutableStateFlow<GuidedState> {
        val campo = GuidedPlayerManager::class.java.getDeclaredField("_state")
        campo.isAccessible = true
        return campo.get(checkNotNull(GuidedPlaybackBridge.manager)) as MutableStateFlow<GuidedState>
    }

    /**
     * Põe [pct] como progresso do parágrafo 3 da guiada e deixa o tique do acompanhamento rodar (rb-65). Devolve o topo
     * do parágrafo na lista depois do tique.
     */
    private fun emitirProgresso(scenario: ActivityScenario<ReaderActivity>, pct: Float): Int {
        scenario.onActivity { _ ->
            val fluxo = fluxoDoGuiada()
            fluxo.value = fluxo.value.copy(progressPct = pct)
        }
        esperarAte(scenario, "tique do progresso $pct") { a -> !lista(a).isLayoutRequested }
        var topo = 0
        scenario.onActivity { a -> topo = checkNotNull(tvEm(lista(a), 2)) { "parágrafo 3 fora da lista" }.top }
        return topo
    }

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
     * looper). Falha logo se o Leitor mostrar erro, e ao estourar o tempo limite. [diagnostico], se houver, roda na
     * thread principal só ao estourar, e o texto dele vai na mensagem da falha.
     */
    private fun esperarAte(
        scenario: ActivityScenario<ReaderActivity>,
        descricao: String,
        diagnostico: ((ReaderActivity) -> String)? = null,
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
        var extra = ""
        if (diagnostico != null) scenario.onActivity { a -> extra = " " + diagnostico(a) }
        fail("Tempo esgotado: $descricao. ${diagnosticoDoCache(appDaTela)}$extra")
    }

    /**
     * Insumos da decisão de reabrir o trecho guardado sozinho (rb-60b), lidos na thread principal. Replica a conta de
     * SelecaoSemMenu.decidir com os mesmos símbolos internos: `outroTextoEmUso` e a área da lista são privados lá,
     * então estão copiados aqui (mesma regra). O limite da barra vem de limiteDaAreaNaTela (72 dp), como o teste mede.
     */
    private fun diagnosticoDaReabertura(a: ReaderActivity): String = runCatching {
        val rv = listaDoLeitor(a)
        val guardada = rv.guardada
        val foco = rv.rootView.findFocus()
        val focoComSelecao = (foco as? TextView)?.hasSelection() == true
        val focoFora = foco != null && !foco.estaDentroDe(rv)
        val outroTextoEmUso = rv.selecoesAbertas > 0 || focoComSelecao || focoFora
        val seguindo = (rv.layoutManager as? LeitorLayoutManager)?.seguindoLeitura?.invoke() == true
        val barraVisivel = barraDaSelecaoTextoVisivel(a)
        val limite = if (barraVisivel) limiteDaAreaNaTela(a) else null
        val telaAtiva = a.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val partes = mutableListOf(
            "scrollState=${rv.scrollState}",
            "toqueEmAndamento=${rv.toqueEmAndamento}",
            "selecoesAbertas=${rv.selecoesAbertas}",
            "textoComSelecaoViva=${rv.textoComSelecaoViva != null}",
            "foco=${foco?.javaClass?.simpleName}(dentroDaLista=${foco?.estaDentroDe(rv)}, comSelecao=$focoComSelecao)",
            "outroTextoEmUso=$outroTextoEmUso",
            "guardada.ativa=${guardada.ativa} posicao=${guardada.posicao}" +
                " inicio=${guardada.inicio} fim=${guardada.fim}",
            "seguindoLeitura=$seguindo",
            "telaAtiva=$telaAtiva",
            "barraVisivel=$barraVisivel limiteDaBarra=$limite",
            "faixaParaReabrirSozinho=${rv.faixaParaReabrirSozinho(limite, telaAtiva)}",
        )
        medidasDaReabertura(a)?.let { m ->
            partes += "trechoNaTela=${m.trecho.topo}..${m.trecho.fundo}"
            partes += "linhaDoFim=${m.linhaDoFim.topo}..${m.linhaDoFim.fundo}"
            partes += "areaUtil=${m.area.topo}..${m.area.fundo} " +
                "areaDaLista=${m.areaDaLista.topo}..${m.areaDaLista.fundo}"
            partes += "trechoInteiroNaTela=${m.trechoInteiroNaTela}"
        }
        partes.joinToString("; ", "[diagnóstico da reabertura] ", ".")
    }.getOrElse { "[diagnóstico da reabertura indisponível: $it]" }

    /** Medidas do trecho guardado na tela, como SelecaoSemMenu as calcula para reabrir sozinho (rb-60b). */
    private class MedidasDaReabertura(
        val trecho: LinhaNaTela,
        val linhaDoFim: LinhaNaTela,
        val area: LinhaNaTela,
        val areaDaLista: LinhaNaTela,
        val limiteDaBarra: Int?,
    ) {
        /** Mesma regra de SelecaoSemMenu.trechoInteiroNaTela na vertical (a largura não entra aqui). */
        val trechoInteiroNaTela: Boolean
            get() = trechoInteiroNaArea(trecho, area) && trechoInteiroNaArea(linhaDoFim, areaDaLista)
    }

    /** Nulo sem trecho guardado medível (sem tela ativa, sem layout ou sem trecho). */
    private fun medidasDaReabertura(a: ReaderActivity): MedidasDaReabertura? {
        val rv = listaDoLeitor(a)
        val guardada = rv.guardada
        val tv = tvEm(rv, guardada.posicao) ?: return null
        val layout = tv.layout ?: return null
        if (!guardada.ativa) return null
        val posTv = IntArray(2).also { tv.getLocationOnScreen(it) }
        val posLista = IntArray(2).also { rv.getLocationOnScreen(it) }
        val base = posTv[1] + tv.totalPaddingTop - tv.scrollY
        val linhaIni = layout.getLineForOffset(guardada.inicio)
        val linhaFim = layout.getLineForOffset(guardada.fim)
        val trecho = LinhaNaTela(base + layout.getLineTop(linhaIni), base + layout.getLineBottom(linhaFim))
        val linhaDoFim = LinhaNaTela(
            base + layout.getLineTop(linhaFim) - (if (linhaFim == 0) tv.extendedPaddingTop else 0),
            base + layout.getLineBottom(linhaFim) +
                (if (linhaFim == layout.lineCount - 1) tv.extendedPaddingBottom else 0),
        )
        val areaDaLista = LinhaNaTela(posLista[1] + rv.paddingTop, posLista[1] + rv.height - rv.paddingBottom)
        val fundoDoPai = (rv.parent as? View)?.let { p ->
            IntArray(2).also { p.getLocationOnScreen(it) }[1] + p.height
        } ?: Int.MAX_VALUE
        val guiada = a.findViewById<View>(R.id.layoutGuidedPlayer)?.takeIf { it.isShown }
            ?.let { IntArray(2).also { p -> it.getLocationOnScreen(p) }[1] }
        // O limite da barra é o da produção: base da barra mais 72 dp, só com a barra visível.
        val limite = if (barraDaSelecaoTextoVisivel(a)) limiteDaAreaNaTela(a) else null
        val area = areaUtilParaReabrir(areaDaLista, fundoDoPai, limite, guiada)
        return MedidasDaReabertura(trecho, linhaDoFim, area, areaDaLista, limite)
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
