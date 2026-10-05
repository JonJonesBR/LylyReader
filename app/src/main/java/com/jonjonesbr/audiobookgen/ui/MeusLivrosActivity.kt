package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.DocumentRepository
import com.jonjonesbr.audiobookgen.data.LinkedBookStore
import com.jonjonesbr.audiobookgen.domain.AbaBiblioteca
import com.jonjonesbr.audiobookgen.domain.LivroDeExemploRegras
import com.jonjonesbr.audiobookgen.domain.LivroBiblioteca
import com.jonjonesbr.audiobookgen.domain.OrdemBiblioteca
import com.jonjonesbr.audiobookgen.domain.OrigemLivro
import com.jonjonesbr.audiobookgen.domain.continuar
import com.jonjonesbr.audiobookgen.domain.ehFormatoSuportado
import com.jonjonesbr.audiobookgen.domain.filtrar
import com.jonjonesbr.audiobookgen.domain.ordenar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/** Abre direto o painel "Adicionar" (usado pela lição 1 da central "Aprender o app"). */
const val EXTRA_ABRIR_ADICIONAR = "extra_abrir_adicionar"

/**
 * Meus livros: tudo que foi importado ou baixado (arquivos em `filesDir/livros`), com abas, busca, ordenação, cartão
 * "Continuar", capas e ações por livro. Ver Fase N do plano.
 */
class MeusLivrosActivity : BasePlayerActivity() {

    private lateinit var adapter: BibliotecaAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var layoutVazio: View
    private lateinit var tvVazioTitulo: TextView
    private lateinit var tvVazioTexto: TextView
    private lateinit var btnExemplo: android.widget.Button
    private var suprimirDica = false
    private var ofertandoVozes = false
    private lateinit var etBusca: EditText
    private lateinit var tvTitulo: TextView
    private lateinit var progresso: ProgressBar
    private lateinit var fab: ExtendedFloatingActionButton

    private var todos: List<LivroBiblioteca> = emptyList()
    private var aba = AbaBiblioteca.TODOS
    private var consulta = ""
    private var ordem = OrdemBiblioteca.RECENTES
    private var buscando = false

    private val appPrefs by lazy { AppPrefs(this) }

    private val seletorArquivos =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importar(uris.map { it to null })
        }

    private val seletorPasta =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { importarPasta(it) }
        }

    /** A busca de livros já grava na biblioteca; ao voltar só é preciso recarregar a lista. */
    private val buscaLivros =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { carregar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meus_livros)
        configurarMiniPlayerBotoes()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.BIBLIOTECA)
        // Fase N: leva uma única vez os livros do cache para a biblioteca; onboarding e novidades da versão (antes na tela inicial antiga).
        com.jonjonesbr.audiobookgen.data.MigracaoBiblioteca.iniciarSeNecessario(applicationContext)
        if (!appPrefs.onboardingDone) startActivity(Intent(this, OnboardingActivity::class.java))
        else ReleaseNotesDialog.mostrarSeNecessario(this)

        recycler = findViewById(R.id.recyclerBib)
        layoutVazio = findViewById(R.id.layoutVazioBib)
        tvVazioTitulo = findViewById(R.id.tvVazioTituloBib)
        tvVazioTexto = findViewById(R.id.tvVazioTextoBib)
        btnExemplo = findViewById(R.id.btnExemploBib)
        btnExemplo.setOnClickListener { abrirLivroDeExemplo() }
        etBusca = findViewById(R.id.etBuscaBib)
        tvTitulo = findViewById(R.id.tvTituloBib)
        progresso = findViewById(R.id.pbBib)
        fab = findViewById(R.id.fabAdicionarBib)

        ordem = runCatching { OrdemBiblioteca.valueOf(appPrefs.bibliotecaOrdem) }.getOrDefault(OrdemBiblioteca.RECENTES)

        findViewById<ImageButton>(R.id.btnVoltarBib).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnBuscarBib).setOnClickListener { alternarBusca() }
        findViewById<ImageButton>(R.id.btnTemaBib).setOnClickListener { SeletorTemaSheet.mostrar(this) }
        findViewById<ImageButton>(R.id.btnMaisBib).setOnClickListener { mostrarMenuMais(it) }
        fab.setOnClickListener { mostrarAdicionar() }

        val colunas = (resources.displayMetrics.widthPixels / resources.displayMetrics.density / LARGURA_COLUNA_DP)
            .toInt().coerceIn(COLUNAS_MIN, COLUNAS_MAX)
        adapter = BibliotecaAdapter(
            scope = lifecycleScope,
            capaDe = { BibliotecaStore.capaDo(applicationContext, it) },
            onAbrir = ::abrirLivro,
            onMenu = ::mostrarMenuDoLivro
        )
        val grade = GridLayoutManager(this, colunas)
        grade.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = adapter.colunasDoItem(position, colunas)
        }
        recycler.layoutManager = grade
        recycler.adapter = adapter

        findViewById<TabLayout>(R.id.tabsBib).addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                aba = AbaBiblioteca.values()[tab.position]
                aplicar()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        etBusca.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                consulta = s?.toString().orEmpty()
                aplicar()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (buscando) alternarBusca() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        NavegacaoPrincipal.configurar(this, AbaPrincipal.BIBLIOTECA) // volta de outra tela: realça de novo a aba certa
        // Vindo da central "Aprender o app" (lição "Adicionar um livro").
        if (intent.getBooleanExtra(EXTRA_ABRIR_ADICIONAR, false)) {
            intent.removeExtra(EXTRA_ABRIR_ADICIONAR)
            suprimirDica = true // o painel já está na tela: nada de dica por cima
            mostrarAdicionar()
        }
        // Só em build de depuração: importa o link do extra, sem digitar no diálogo (o MIUI bloqueia toque por adb).
        intent.getStringExtra("extra_debug_link")?.takeIf { applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 }?.let {
            intent.removeExtra("extra_debug_link")
            importarDeLink(it)
        }
        if (appPrefs.onboardingDone && !appPrefs.voiceDownloadOnboardingDone && !ofertandoVozes) ofertarVozesUmaVez()
        carregar() // a posição de leitura/progresso pode ter mudado no leitor
    }

    // ── Dados ────────────────────────────────────────────────────────────────

    private fun carregar() {
        lifecycleScope.launch {
            todos = BibliotecaStore.listar(applicationContext)
            aplicar()
        }
    }

    private fun caminhoDe(livro: LivroBiblioteca) = BibliotecaStore.arquivoDo(this, livro).absolutePath

    /** Fração lida (posição de rolagem ÷ total de parágrafos gravados pelo leitor); null se o leitor ainda não gravou o total. */
    private fun progressoDe(livro: LivroBiblioteca): Float? = BibliotecaStore.progressoDe(this, livro)

    private fun temAudio(livro: LivroBiblioteca): Boolean = BibliotecaStore.temAudio(this, livro)

    private fun aplicar() {
        val prog = todos.associate { it.id to progressoDe(it) }
        val audio = todos.associate { it.id to temAudio(it) }
        val filtrados = ordenar(
            filtrar(todos, aba, consulta, { prog[it.id] }, { audio[it.id] == true }),
            ordem
        )
        val itens = mutableListOf<ItemBiblioteca>()
        if (aba == AbaBiblioteca.TODOS && consulta.isBlank()) {
            continuar(todos) { prog[it.id] }?.let { itens += ItemBiblioteca.Continuar(it, prog[it.id] ?: 0f) }
        }
        filtrados.mapTo(itens) { ItemBiblioteca.Livro(it, prog[it.id], audio[it.id] == true) }
        adapter.submitList(itens)

        val vazio = itens.isEmpty()
        layoutVazio.visibility = if (vazio) View.VISIBLE else View.GONE
        recycler.visibility = if (vazio) View.GONE else View.VISIBLE
        val bibliotecaVazia = todos.isEmpty()
        tvVazioTitulo.setText(if (bibliotecaVazia) R.string.bib_vazio_titulo else R.string.bib_filtro_vazio)
        tvVazioTexto.visibility = if (bibliotecaVazia) View.VISIBLE else View.GONE
        val exemploPresente = todos.any { it.id == appPrefs.livroExemploId }
        btnExemplo.visibility = if (LivroDeExemploRegras.deveOferecer(todos.size, consulta.isNotBlank() || aba != AbaBiblioteca.TODOS, exemploPresente)) {
            View.VISIBLE
        } else View.GONE
        if (!suprimirDica) fab.post {
            CoachMark.mostrarProxima(this, com.jonjonesbr.audiobookgen.domain.TelaDica.BIBLIOTECA) { dica ->
                when (dica) {
                    com.jonjonesbr.audiobookgen.domain.Dica.BIB_ADICIONAR -> fab
                    com.jonjonesbr.audiobookgen.domain.Dica.BIB_TEMA -> findViewById<View>(R.id.btnTemaBib)
                    else -> null
                }
            }
        }
    }

    /**
     * Escolha de vozes do primeiro acesso: aparece UMA vez. Quem já tem algum pacote de voz baixado (ou já passou por ela) não a vê.
     * A tela se marca como vista ao abrir, então qualquer saída (Continuar, Voltar, outra aba) conta.
     */
    private fun ofertarVozesUmaVez() {
        ofertandoVozes = true
        lifecycleScope.launch {
            val temVoz = withContext(Dispatchers.IO) {
                com.jonjonesbr.audiobookgen.util.VoiceCatalog.pacotesRegistrados()
                    .any { it.download != null && it.isPronto(applicationContext) }
            }
            if (temVoz) {
                appPrefs.voiceDownloadOnboardingDone = true
            } else {
                startActivity(Intent(this@MeusLivrosActivity, DownloadCentralActivity::class.java)
                    .putExtra(DownloadCentralActivity.EXTRA_PRIMEIRO_ACESSO, true))
            }
            ofertandoVozes = false
        }
    }

    /** Fase J: importa "Um Apólogo" (domínio público) e abre a tela do livro. */
    private fun abrirLivroDeExemplo() {
        btnExemplo.isEnabled = false
        lifecycleScope.launch {
            val livro = runCatching { com.jonjonesbr.audiobookgen.data.LivroDeExemplo.garantir(applicationContext) }.getOrNull()
            btnExemplo.isEnabled = true
            if (livro == null) {
                Toast.makeText(this@MeusLivrosActivity, R.string.bib_erro_abrir, Toast.LENGTH_SHORT).show()
                return@launch
            }
            abrirLivro(livro, continuarGuiada = false)
        }
    }

    // ── Abrir / ações por livro ──────────────────────────────────────────────

    /** Cartão "Continuar" → direto na leitura guiada; toque num livro → tela do livro. */
    private fun abrirLivro(livro: LivroBiblioteca, continuarGuiada: Boolean) {
        if (continuarGuiada) {
            AcoesDoLivro.abrirNoLeitor(this, livro, continuar = true)
        } else {
            startActivity(Intent(this, LivroActivity::class.java).putExtra(LivroActivity.EXTRA_ID, livro.id))
        }
    }

    private fun mostrarMenuDoLivro(ancora: View, livro: LivroBiblioteca) {
        PopupMenu(this, ancora).apply {
            menu.add(0, ACAO_LER, 0, R.string.bib_acao_ler)
            menu.add(0, ACAO_AUDIOBOOK, 1, R.string.bib_acao_audiobook)
            menu.add(0, ACAO_FILA, 2, R.string.gerar_fila)
            menu.add(0, ACAO_DETALHES, 3, R.string.bib_acao_detalhes)
            menu.add(0, ACAO_APAGAR, 4, R.string.bib_acao_apagar)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    ACAO_LER -> AcoesDoLivro.abrirNoLeitor(this@MeusLivrosActivity, livro, continuar = false)
                    ACAO_AUDIOBOOK -> startActivity(
                        Intent(this@MeusLivrosActivity, LivroActivity::class.java)
                            .putExtra(LivroActivity.EXTRA_ID, livro.id)
                            .putExtra(LivroActivity.EXTRA_ABRIR_PAINEL_GERAR, true)
                    )
                    ACAO_FILA -> AcoesDoLivro.adicionarAFila(this@MeusLivrosActivity, livro)
                    ACAO_DETALHES -> mostrarDetalhes(livro)
                    ACAO_APAGAR -> AcoesDoLivro.confirmarApagar(this@MeusLivrosActivity, livro) { carregar() }
                }
                true
            }
        }.show()
    }

    private fun mostrarDetalhes(livro: LivroBiblioteca) {
        val origem = when (livro.origem) {
            OrigemLivro.ARQUIVO -> R.string.bib_origem_arquivo
            OrigemLivro.GUTENBERG -> R.string.bib_origem_gutenberg
            OrigemLivro.WIKISOURCE -> R.string.bib_origem_wikisource
            OrigemLivro.ARCHIVE -> R.string.bib_origem_archive
        }
        val linhas = buildList {
            livro.autor?.let { add("${getString(R.string.bib_det_autor)}: $it") }
            add("${getString(R.string.bib_det_formato)}: ${livro.formato.uppercase()}")
            add("${getString(R.string.bib_det_tamanho)}: ${Formatter.formatShortFileSize(this@MeusLivrosActivity, livro.tamanhoBytes)}")
            add("${getString(R.string.bib_det_origem)}: ${getString(origem)}")
            add("${getString(R.string.bib_det_adicionado)}: ${DateFormat.getMediumDateFormat(this@MeusLivrosActivity).format(Date(livro.adicionadoEm))}")
        }
        AlertDialog.Builder(this).setTitle(livro.titulo).setMessage(linhas.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null).show()
    }

    // ── Busca, ordenação, espaço ─────────────────────────────────────────────

    private fun alternarBusca() {
        buscando = !buscando
        tvTitulo.visibility = if (buscando) View.GONE else View.VISIBLE
        etBusca.visibility = if (buscando) View.VISIBLE else View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (buscando) {
            etBusca.requestFocus()
            imm.showSoftInput(etBusca, InputMethodManager.SHOW_IMPLICIT)
        } else {
            etBusca.setText("")
            imm.hideSoftInputFromWindow(etBusca.windowToken, 0)
        }
    }

    private fun mostrarMenuMais(ancora: View) {
        PopupMenu(this, ancora).apply {
            menu.add(0, MENU_ORDENAR, 0, R.string.bib_ordenar)
            menu.add(0, MENU_FILA, 2, R.string.bib_menu_fila)
            menu.add(0, MENU_CONVERSOES, 3, R.string.conv_titulo)
            menu.add(0, MENU_APRENDER, 4, R.string.aprender_titulo)
            menu.add(0, MENU_ESPACO, 1, getString(R.string.bib_espaco, Formatter.formatShortFileSize(this@MeusLivrosActivity, BibliotecaStore.espacoUsado(this@MeusLivrosActivity))))
                .isEnabled = false
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_ORDENAR -> escolherOrdem()
                    MENU_FILA -> startActivity(Intent(this@MeusLivrosActivity, QueueActivity::class.java))
                    MENU_CONVERSOES -> startActivity(Intent(this@MeusLivrosActivity, ConversoesActivity::class.java))
                    MENU_APRENDER -> startActivity(Intent(this@MeusLivrosActivity, AprenderActivity::class.java))
                }
                true
            }
        }.show()
    }

    private fun escolherOrdem() {
        val opcoes = arrayOf(getString(R.string.bib_ord_recentes), getString(R.string.bib_ord_titulo), getString(R.string.bib_ord_autor))
        AlertDialog.Builder(this)
            .setTitle(R.string.bib_ordenar)
            .setSingleChoiceItems(opcoes, ordem.ordinal) { dialogo, qual ->
                ordem = OrdemBiblioteca.values()[qual]
                appPrefs.bibliotecaOrdem = ordem.name
                aplicar()
                dialogo.dismiss()
            }
            .show()
    }

    // ── Adicionar ────────────────────────────────────────────────────────────

    private fun mostrarAdicionar() {
        val folha = BottomSheetDialog(this)
        val conteudo = layoutInflater.inflate(R.layout.bottom_sheet_adicionar_livro, null)
        conteudo.findViewById<View>(R.id.optAdicionarAparelho).setOnClickListener {
            folha.dismiss()
            seletorArquivos.launch(TIPOS_MIME)
        }
        conteudo.findViewById<View>(R.id.optAdicionarGratis).setOnClickListener {
            folha.dismiss()
            buscaLivros.launch(Intent(this, BuscarLivrosActivity::class.java))
        }
        conteudo.findViewById<View>(R.id.optAdicionarLink).setOnClickListener {
            folha.dismiss()
            mostrarDialogoLink()
        }
        conteudo.findViewById<View>(R.id.optAdicionarPasta).setOnClickListener {
            folha.dismiss()
            seletorPasta.launch(null)
        }
        folha.setContentView(conteudo)
        folha.show()
    }

    // ── Importar de um link (Fase P-E) ───────────────────────────────────────

    /** Link direto de um arquivo, com a declaração de que a pessoa tem o direito de usá-lo (a responsabilidade é dela). */
    private fun mostrarDialogoLink() {
        val vista = layoutInflater.inflate(R.layout.dialog_importar_link, null)
        val campo = vista.findViewById<android.widget.EditText>(R.id.etLinkLivro)
        val declaracao = vista.findViewById<android.widget.CheckBox>(R.id.cbLinkDireitos)
        val dialogo = AlertDialog.Builder(this)
            .setTitle(R.string.link_titulo)
            .setView(vista)
            .setPositiveButton(R.string.link_baixar, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialogo.setOnShowListener {
            val baixar = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            baixar.isEnabled = false
            val atualizar = { baixar.isEnabled = declaracao.isChecked && campo.text.isNotBlank() }
            declaracao.setOnCheckedChangeListener { _, _ -> atualizar() }
            campo.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = atualizar()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            baixar.setOnClickListener {
                dialogo.dismiss()
                importarDeLink(campo.text.toString())
            }
        }
        dialogo.show()
    }

    private fun importarDeLink(entrada: String) {
        val url = com.jonjonesbr.audiobookgen.domain.LinkDeLivro.normalizar(entrada).getOrElse {
            mostrarErroDeLink(it)
            return
        }
        progresso.visibility = View.VISIBLE
        Toast.makeText(this, R.string.link_baixando, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val baixado = com.jonjonesbr.audiobookgen.data.LinkDownloader.baixar(applicationContext, url) { _, _ -> }
                val livro = try {
                    BibliotecaStore.importarArquivo(applicationContext, baixado.arquivo, baixado.nome, OrigemLivro.ARQUIVO, null)
                } finally {
                    baixado.arquivo.delete()
                }
                carregar()
                abrirLivro(livro, continuarGuiada = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                mostrarErroDeLink(e)
            } finally {
                progresso.visibility = View.GONE
            }
        }
    }

    private fun mostrarErroDeLink(erro: Throwable) {
        val motivo = (erro as? com.jonjonesbr.audiobookgen.domain.LinkException)?.motivo
        val mensagem = when (motivo) {
            com.jonjonesbr.audiobookgen.domain.MotivoLink.VAZIO, com.jonjonesbr.audiobookgen.domain.MotivoLink.INVALIDO -> getString(R.string.link_erro_invalido)
            com.jonjonesbr.audiobookgen.domain.MotivoLink.PAGINA_WEB -> getString(R.string.link_erro_pagina)
            com.jonjonesbr.audiobookgen.domain.MotivoLink.FORMATO -> getString(R.string.link_erro_formato)
            com.jonjonesbr.audiobookgen.domain.MotivoLink.GRANDE -> getString(R.string.link_erro_grande)
            else -> getString(R.string.link_erro_rede, erro.message ?: "")
        }
        AlertDialog.Builder(this).setMessage(mensagem).setPositiveButton(android.R.string.ok, null).show()
    }

    /** Pares (uri, nome conhecido ou null → pergunta ao provedor). Copia para a biblioteca, em 2º plano. */
    @Suppress("TooGenericExceptionCaught")
    private fun importar(itens: List<Pair<Uri, String?>>) {
        progresso.visibility = View.VISIBLE
        Toast.makeText(this, R.string.bib_importando, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var ok = 0
            var falhas = 0
            withContext(Dispatchers.IO) {
                for ((uri, nomeConhecido) in itens) {
                    try {
                        val nome = nomeConhecido ?: DocumentRepository(this@MeusLivrosActivity).extrairMetadadosArquivo(uri).nome
                        BibliotecaStore.importarDeUri(applicationContext, uri, nome)
                        ok++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        falhas++
                    }
                }
            }
            progresso.visibility = View.GONE
            val texto = getString(R.string.bib_importados, ok) +
                if (falhas > 0) "\n" + getString(R.string.bib_importados_falha, falhas) else ""
            Toast.makeText(this@MeusLivrosActivity, texto, Toast.LENGTH_LONG).show()
            carregar()
        }
    }

    private fun importarPasta(arvore: Uri) {
        lifecycleScope.launch {
            val arquivos = withContext(Dispatchers.IO) { listarDaPasta(arvore) }
            if (arquivos.isEmpty()) {
                Toast.makeText(this@MeusLivrosActivity, R.string.bib_nenhum_suportado, Toast.LENGTH_LONG).show()
            } else {
                importar(arquivos)
            }
        }
    }

    /** Arquivos de formato suportado direto na pasta (sem subpastas, como na importação em lote da fila). */
    private fun listarDaPasta(arvore: Uri): List<Pair<Uri, String?>> {
        val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(arvore, DocumentsContract.getTreeDocumentId(arvore))
        val saida = mutableListOf<Pair<Uri, String?>>()
        contentResolver.query(
            filhos,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val nome = cursor.getString(1) ?: continue
                if (ehFormatoSuportado(nome)) {
                    saida += DocumentsContract.buildDocumentUriUsingTree(arvore, cursor.getString(0)) to nome
                }
            }
        }
        return saida
    }

    private companion object {
        const val ACAO_LER = 1
        const val ACAO_AUDIOBOOK = 2
        const val ACAO_FILA = 5
        const val ACAO_DETALHES = 3
        const val ACAO_APAGAR = 4
        const val MENU_ORDENAR = 1
        const val MENU_ESPACO = 2
        const val MENU_FILA = 3
        const val MENU_APRENDER = 4
        const val MENU_CONVERSOES = 5
        const val LARGURA_COLUNA_DP = 120f
        const val COLUNAS_MIN = 3
        const val COLUNAS_MAX = 8
        val TIPOS_MIME = arrayOf(
            "application/epub+zip", "application/pdf", "text/plain", "text/markdown",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword", "application/x-mobipocket-ebook",
            // Muitos provedores não sabem o MIME de .doc/.mobi e reportam este genérico.
            "application/octet-stream"
        )
    }
}
