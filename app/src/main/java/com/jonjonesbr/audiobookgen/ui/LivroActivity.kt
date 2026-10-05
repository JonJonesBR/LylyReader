package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.data.BibliotecaStore
import com.jonjonesbr.audiobookgen.data.SecurePreferences
import com.jonjonesbr.audiobookgen.domain.LivroBiblioteca
import com.jonjonesbr.audiobookgen.domain.OrigemLivro
import com.jonjonesbr.audiobookgen.domain.posicaoDoRitmo
import com.jonjonesbr.audiobookgen.domain.ritmoDaPosicao
import com.jonjonesbr.audiobookgen.util.aplicarSnapVelocidade
import com.jonjonesbr.audiobookgen.service.GuidedBookPrefsStore
import com.jonjonesbr.audiobookgen.util.NetworkStatus
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tela do livro: capa, ação principal (continuar a leitura guiada), gerar audiobook, a voz DESTE livro e atalhos para
 * capítulos, personagens, pronúncia e timbre. Voz escolhida aqui vale só para este livro (`GuidedBookPrefsStore`).
 */
class LivroActivity : BasePlayerActivity() {

    private val audiobookViewModel: AudiobookViewModel by viewModels()
    private val appPrefs by lazy { AppPrefs(this) }
    private var livro: LivroBiblioteca? = null

    private val seletorDeVoz = VoiceSelectionDelegate(
        activity = this,
        onVoiceSelected = ::aoEscolherVoz,
        onVoicePreview = ::ouvirAmostra,
        obterVozAtualId = { vozDoLivro().first }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_livro)
        configurarMiniPlayerBotoes()
        findViewById<ImageButton>(R.id.btnVoltarLivro).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnMaisLivro).setOnClickListener { mostrarMenu(it) }
        findViewById<View>(R.id.btnTrocarVozLivro).setOnClickListener { seletorDeVoz.abrirSeletorVozes() }
        findViewById<View>(R.id.linhaTimbreLivro).setOnClickListener { TimbreDialog.show(this, appPrefs) }
    }

    override fun onResume() {
        super.onResume()
        // Recarrega sempre: o progresso e o vínculo com o audiobook mudam no leitor / na conversão.
        val id = intent.getStringExtra(EXTRA_ID)
        lifecycleScope.launch {
            val encontrado = BibliotecaStore.listar(applicationContext).firstOrNull { it.id == id }
            if (encontrado == null) {
                finish()
            } else {
                livro = encontrado
                mostrar(encontrado)
                // Atalho de quem quer cair direto no painel de conversão (ex.: validação por adb).
                if (intent.getBooleanExtra(EXTRA_ABRIR_PAINEL_GERAR, false)) {
                    intent.removeExtra(EXTRA_ABRIR_PAINEL_GERAR)
                    mostrarPainelGerar(encontrado)
                } else {
                    findViewById<View>(R.id.btnLivroGerarAudiobook).post {
                        CoachMark.mostrarProxima(this@LivroActivity, com.jonjonesbr.audiobookgen.domain.TelaDica.LIVRO) { dica ->
                            when (dica) {
                                com.jonjonesbr.audiobookgen.domain.Dica.LIVRO_GERAR -> findViewById<View>(R.id.btnLivroGerarAudiobook)
                                com.jonjonesbr.audiobookgen.domain.Dica.LIVRO_VOZ -> findViewById<View>(R.id.btnTrocarVozLivro)
                                else -> null
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mostrar(l: LivroBiblioteca) {
        val capaView = findViewById<CapaView>(R.id.ivLivroCapaGrande)
        capaView.tag = l.id
        val serifa = runCatching { ResourcesCompat.getFont(this, R.font.literata) }.getOrNull()
        capaView.setImageDrawable(CapaTipografica(l.titulo, l.autor, l.id, serifa))
        BibliotecaStore.capaDo(this, l)?.let { arquivo ->
            lifecycleScope.launch {
                val imagem = CapasDaBiblioteca.carregar(arquivo) ?: return@launch
                if (capaView.tag == l.id) capaView.setImageDrawable(BitmapDrawable(resources, imagem))
            }
        }
        findViewById<TextView>(R.id.tvLivroTituloGrande).text = l.titulo
        findViewById<TextView>(R.id.tvLivroAutorGrande).apply {
            text = l.autor.orEmpty()
            visibility = if (l.autor.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        val origem = when (l.origem) {
            OrigemLivro.ARQUIVO -> R.string.bib_origem_arquivo
            OrigemLivro.GUTENBERG -> R.string.bib_origem_gutenberg
            OrigemLivro.WIKISOURCE -> R.string.bib_origem_wikisource
            OrigemLivro.ARCHIVE -> R.string.bib_origem_archive
        }
        findViewById<TextView>(R.id.tvLivroMeta).text =
            "${l.formato.uppercase()} · ${Formatter.formatShortFileSize(this, l.tamanhoBytes)} · ${getString(origem)}"

        val progresso = BibliotecaStore.progressoDe(this, l)
        val barra = findViewById<ProgressBar>(R.id.pbLivroProgresso)
        val pct = findViewById<TextView>(R.id.tvLivroProgresso)
        val iniciado = progresso != null && progresso > 0f
        barra.visibility = if (iniciado) View.VISIBLE else View.GONE
        pct.visibility = if (iniciado) View.VISIBLE else View.GONE
        if (iniciado) {
            barra.progress = (progresso!! * BARRA_MAX).toInt()
            pct.text = getString(R.string.bib_progresso, (progresso * 100).toInt())
        }

        val principal = findViewById<Button>(R.id.btnLivroContinuar)
        principal.setText(if (iniciado) R.string.livro_continuar else R.string.livro_comecar)
        principal.setOnClickListener { AcoesDoLivro.abrirNoLeitor(this, l, continuar = true) }
        findViewById<Button>(R.id.btnLivroGerarAudiobook).setOnClickListener { mostrarPainelGerar(l) }

        mostrarVoz()
        findViewById<View>(R.id.linhaCapitulosLivro).setOnClickListener {
            AcoesDoLivro.abrirNoLeitor(this, l, continuar = false, acaoInicial = ReaderActivity.ACAO_INICIAL_CAPITULOS)
        }
        findViewById<View>(R.id.linhaPersonagensLivro).setOnClickListener {
            AcoesDoLivro.abrirNoLeitor(this, l, continuar = false, acaoInicial = ReaderActivity.ACAO_INICIAL_PERSONAGENS)
        }
        findViewById<View>(R.id.linhaPronunciaLivro).setOnClickListener {
            AcoesDoLivro.abrirNoLeitor(this, l, continuar = false, acaoInicial = ReaderActivity.ACAO_INICIAL_PRONUNCIA)
        }
    }

    // ── Painel "Gerar audiobook" ─────────────────────────────────────────────

    private var painelVozNome: TextView? = null
    private var painelVozDetalhe: TextView? = null
    private var painelPasta: TextView? = null

    private val seletorPasta = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        com.jonjonesbr.audiobookgen.data.DocumentRepository(this).processarPastaSaf(uri) // guarda a permissão da pasta
        appPrefs.pastaDestino = uri.toString()
        atualizarRotuloPasta()
    }

    private fun atualizarRotuloPasta() {
        val salva = appPrefs.pastaDestino
        painelPasta?.text = if (salva.isNullOrBlank()) {
            getString(R.string.gerar_pasta_padrao)
        } else {
            runCatching {
                com.jonjonesbr.audiobookgen.data.DocumentRepository(this).processarPastaSaf(android.net.Uri.parse(salva)).nome
            }.getOrNull() ?: getString(R.string.gerar_pasta_padrao)
        }
    }

    private val estilosDeNarracao = listOf("padrao", "terror", "acao", "scifi", "drama", "comedia")

    private fun rotuloDoEstilo(id: String): String = when (id) {
        "terror" -> getString(R.string.style_horror)
        "acao" -> getString(R.string.style_action)
        "scifi" -> getString(R.string.style_scifi)
        "drama" -> getString(R.string.style_drama)
        "comedia" -> getString(R.string.style_comedy)
        else -> getString(R.string.style_standard)
    }

    private fun mostrarPainelGerar(l: LivroBiblioteca) {
        val folha = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val vista = layoutInflater.inflate(R.layout.bottom_sheet_gerar_audiobook, null)
        folha.setContentView(vista)
        vista.findViewById<TextView>(R.id.tvGerarLivro).text = l.titulo
        // O mapa de personagens é por parágrafo da Leitura guiada; a geração direta não o aplica, então avisa onde usá-lo.
        val temVozesDePersonagens = com.jonjonesbr.audiobookgen.domain.SpeakerAttributionStore(java.io.File(filesDir, "speaker_attribution"))
            .temVozesAtribuidas(caminho())
        vista.findViewById<View>(R.id.tvGerarAvisoPersonagens).visibility = if (temVozesDePersonagens) View.VISIBLE else View.GONE
        painelVozNome = vista.findViewById(R.id.tvGerarVozNome)
        painelVozDetalhe = vista.findViewById(R.id.tvGerarVozDetalhe)
        atualizarPainelVoz()
        painelPasta = vista.findViewById(R.id.tvGerarPasta)
        atualizarRotuloPasta()
        folha.setOnDismissListener { painelVozNome = null; painelVozDetalhe = null; painelPasta = null }
        vista.findViewById<View>(R.id.btnGerarTrocarVoz).setOnClickListener { seletorDeVoz.abrirSeletorVozes() }

        val seek = vista.findViewById<android.widget.SeekBar>(R.id.seekGerarRitmo)
        val rotulo = vista.findViewById<TextView>(R.id.tvGerarRitmo)
        seek.progress = posicaoDoRitmo(appPrefs.ritmo)
        rotulo.text = "${ritmoDaPosicao(seek.progress)}%"
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                rotulo.text = "${ritmoDaPosicao(progress)}%"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                // Trava perto dos presets curados (0,75x–2,0x), como no controle da tela de conversão.
                val travado = (aplicarSnapVelocidade(ritmoDaPosicao(sb.progress) / PCT_POR_MULTIPLICADOR) * PCT_POR_MULTIPLICADOR).toInt()
                sb.progress = posicaoDoRitmo(travado)
                rotulo.text = "${ritmoDaPosicao(sb.progress)}%"
            }
        })

        // Opções avançadas: pasta de saída e, só com Gemini, o estilo de narração.
        val avancadas = vista.findViewById<View>(R.id.layoutGerarAvancadas)
        vista.findViewById<View>(R.id.btnGerarAvancadas).setOnClickListener {
            avancadas.visibility = if (avancadas.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        vista.findViewById<View>(R.id.btnGerarPasta).setOnClickListener { seletorPasta.launch(null) }
        val (vozAtual, motorAtual) = vozDoLivro()
        val spinnerEstilo = vista.findViewById<android.widget.Spinner>(R.id.spinnerGerarEstilo)
        spinnerEstilo.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, estilosDeNarracao.map { rotuloDoEstilo(it) }
        )
        val usaEstilo = VoiceCatalog.effectiveEngine(vozAtual, motorAtual) == "gemini"
        vista.findViewById<View>(R.id.layoutGerarEstilo).visibility = if (usaEstilo) View.VISIBLE else View.GONE
        fun estiloEscolhido(): String? = if (usaEstilo) estilosDeNarracao[spinnerEstilo.selectedItemPosition] else null

        vista.findViewById<View>(R.id.btnGerarAgora).setOnClickListener {
            val ritmo = ritmoDaPosicao(seek.progress)
            appPrefs.ritmo = ritmo
            val (voz, motor) = vozDoLivro()
            folha.dismiss()
            AcoesDoLivro.gerarAudiobookDireto(this, l, OpcoesConversao(voz = voz, motor = motor, ritmo = ritmo, estilo = estiloEscolhido()))
        }
        vista.findViewById<View>(R.id.btnGerarFila).setOnClickListener {
            appPrefs.ritmo = ritmoDaPosicao(seek.progress)
            folha.dismiss()
            AcoesDoLivro.adicionarAFila(this, l)
        }
        folha.show()
    }

    private fun atualizarPainelVoz() {
        val (vozId, motor) = vozDoLivro()
        val opcao = VoiceCatalog.findAny(vozId)
        painelVozNome?.text = opcao?.name ?: vozId
        val rede = if (NetworkStatus.engineRequiresNetwork(motor)) getString(R.string.livro_voz_online) else getString(R.string.livro_voz_offline)
        painelVozDetalhe?.text = listOfNotNull(rotuloDoMotor(motor), opcao?.language, rede).joinToString(" · ")
    }

    // ── Voz deste livro ──────────────────────────────────────────────────────

    private fun caminho(): String = livro?.let { BibliotecaStore.arquivoDo(this, it).absolutePath }.orEmpty()

    /** Voz e motor deste livro: o perfil salvo nele ou, na falta, a escolha global. */
    private fun vozDoLivro(): Pair<String, String> {
        GuidedBookPrefsStore.load(this, caminho())?.let { return it.voz to it.motor }
        val motor = appPrefs.motorTts
        return (appPrefs.vozSelecionada ?: VoiceCatalog.defaultFor(motor).id) to motor
    }

    private fun mostrarVoz() {
        val (vozId, motor) = vozDoLivro()
        val opcao = VoiceCatalog.findAny(vozId)
        findViewById<TextView>(R.id.tvLivroVozNome).text = opcao?.name ?: vozId
        val rede = if (NetworkStatus.engineRequiresNetwork(motor)) getString(R.string.livro_voz_online) else getString(R.string.livro_voz_offline)
        val partes = listOfNotNull(rotuloDoMotor(motor), opcao?.language, rede)
        findViewById<TextView>(R.id.tvLivroVozDetalhe).text = partes.joinToString(" · ")
    }

    private fun rotuloDoMotor(motor: String): String? = when (motor) {
        "pocket" -> "Pocket TTS"
        "onnx" -> "Supertonic"
        "kokoro" -> "Kokoro / Piper"
        "edge" -> "Edge"
        "gemini" -> "Gemini"
        "android" -> getString(R.string.livro_motor_android)
        "elevenlabs" -> "ElevenLabs"
        else -> motor.takeIf { it.isNotBlank() }
    }

    /** Grava a voz só neste livro; a velocidade que ele já tinha é mantida. */
    private fun aoEscolherVoz(voz: VoiceOption) {
        appPrefs.registrarEvento(com.jonjonesbr.audiobookgen.domain.EventoAprendizado.VOZ_DO_LIVRO_TROCADA.id)
        val atual = GuidedBookPrefsStore.load(this, caminho())
        GuidedBookPrefsStore.save(this, caminho(), voz.id, voz.engine, atual?.speedMult ?: 1.0f)
        mostrarVoz()
        atualizarPainelVoz()
        val precisaChave = (voz.engine == "gemini" && SecurePreferences.getGeminiKeys(this).isNullOrBlank()) ||
            (voz.engine == "elevenlabs" && SecurePreferences.getElevenLabsKey(this).isNullOrBlank())
        if (precisaChave) {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(if (voz.engine == "gemini") "focar_chave_gemini" else "focar_chave_elevenlabs", true)
            )
        }
    }

    private fun ouvirAmostra(voz: VoiceOption, aoTerminar: () -> Unit) {
        audiobookViewModel.pararPreview()
        if (voz.engine == "elevenlabs" && SecurePreferences.getElevenLabsKey(this).isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_elevenlabs_configure_key, Toast.LENGTH_LONG).show()
            aoTerminar()
            return
        }
        val chave = SecurePreferences.getGeminiKeys(this)
        audiobookViewModel.prepararConfiguracoesPython(voz.engine, chave, appPrefs.pausaMs)
        lifecycleScope.launch {
            audiobookViewModel.gerarAmostra(voz.id, appPrefs.ritmo, voz.engine, chave, VoiceCatalog.previewSampleText(voz))
            audiobookViewModel.uiState.first { !it.amostraGerando }
            aoTerminar()
        }
    }

    // ── Menu ─────────────────────────────────────────────────────────────────

    private fun mostrarMenu(ancora: View) {
        val atual = livro ?: return
        PopupMenu(this, ancora).apply {
            menu.add(0, 1, 0, R.string.bib_acao_apagar)
            setOnMenuItemClickListener {
                AcoesDoLivro.confirmarApagar(this@LivroActivity, atual) { finish() }
                true
            }
        }.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) audiobookViewModel.pararPreview()
    }

    companion object {
        const val EXTRA_ID = "livro_id"
        const val EXTRA_ABRIR_PAINEL_GERAR = "abrir_painel_gerar"
        private const val BARRA_MAX = 1000
        private const val PCT_POR_MULTIPLICADOR = 100f
    }
}
