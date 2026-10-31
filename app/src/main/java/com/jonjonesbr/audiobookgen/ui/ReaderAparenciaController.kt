package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.util.expandirEmPaisagem
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.AlinhamentoTexto
import com.jonjonesbr.audiobookgen.domain.ReaderUiState
import com.jonjonesbr.audiobookgen.domain.TemaLeitura
import com.google.android.material.chip.ChipGroup

// Constantes do bottom sheet de aparência — movidas do companion de ReaderActivity (T2.2).
private const val FONTE_MIN = 12f
private const val FONTE_MAX = 24f
private const val FONTE_MAX_PROGRESS = 24
private const val ESPACAMENTO_MIN = 1f
private const val ESPACAMENTO_MAX = 2f
private const val ESPACAMENTO_MAX_PROGRESS = 20
private const val ESCALA_ESPACAMENTO_PROGRESSO = 20f
private const val PASSO_ESPACAMENTO_PROGRESSO = 0.05f
private const val BRILHO_MIN = 5
private const val BRILHO_MAX = 100
private const val BRILHO_MIN_FLOAT = 0.05f
private const val BRILHO_MAX_FLOAT = 1f
private const val DIALOG_PADDING_H = 48
private const val DIALOG_PADDING_V = 24

/** Liga ou desliga FLAG_KEEP_SCREEN_ON na janela do leitor (preferência "Manter tela ligada"). */
fun aplicarManterTelaLigada(janela: Window, ligada: Boolean) {
    if (ligada) {
        janela.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        janela.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * Bottom sheet de aparência do leitor (tema, fonte, espaçamento, margem, brilho e orientação
 * de rolagem) — extraído de [ReaderActivity] pra reduzir o tamanho do arquivo (T2.2).
 * Padrão do projeto: data class de dependências + callbacks, sem acessar campos da Activity.
 */
class ReaderAparenciaController(
    private val activity: AppCompatActivity,
    private val viewModel: ReaderViewModel,
    private val onOrientacaoRolagem: (Boolean) -> Unit,
) {
    private val dialogos = DialogosAbertos(activity)
    private val appPrefs by lazy { AppPrefs(activity) }
    private fun androidx.appcompat.app.AlertDialog.Builder.exibir(): androidx.appcompat.app.AlertDialog = dialogos.mostrar(this)

    fun mostrarBottomSheet() {
        val state = viewModel.uiState.value
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_aparencia, null)
        dialog.setContentView(view)
        dialog.expandirEmPaisagem()

        configurarSeekFonte(
            view.findViewById(R.id.seekBarFonte),
            view.findViewById(R.id.tvFonteValor),
            state.tamanhoFonte
        )
        configurarSeekEspacamento(
            view.findViewById(R.id.seekBarEspacamento),
            view.findViewById(R.id.tvEspacamentoValor),
            state.espacamento
        )
        configurarBotoesAparencia(view, state)
        configurarAlinhamento(
            view.findViewById(R.id.chipsAlinhamento), view.findViewById(R.id.tvAlinhamentoDica), state.alinhamento
        )

        configurarBrilhoSlider(
            view.findViewById(R.id.seekBarBrilho),
            view.findViewById(R.id.tvBrilhoValor)
        )
        configurarManterTelaLigada(view.findViewById(R.id.swManterTelaLigada))

        dialog.show()
    }

    /** Liga a tela ao leitor: grava a preferência e aplica (ou tira) a flag na hora. */
    private fun configurarManterTelaLigada(chave: MaterialSwitch) {
        chave.isChecked = appPrefs.readerManterTelaLigada
        chave.setOnCheckedChangeListener { _, marcado ->
            appPrefs.readerManterTelaLigada = marcado
            aplicarManterTelaLigada(activity.window, marcado)
        }
    }

    private fun configurarSeekFonte(seekFonte: SeekBar, tvFonteValor: TextView, tamanhoFonteInicial: Float) {
        fun atualizarLabelsFonte(sp: Float) {
            // Visível só o número ("16", "16,5"); o leitor de tela ouve o texto completo com a unidade.
            val numero = formatarNumero(sp, maxCasas = 1)
            tvFonteValor.text = numero
            tvFonteValor.contentDescription = activity.getString(R.string.cd_tamanho_fonte_valor, numero)
            ViewCompat.setStateDescription(seekFonte, tvFonteValor.contentDescription)
        }

        // Fonte: min=12sp, max=24sp -> progress 0..24 representa (12+progress/2)sp
        seekFonte.max = FONTE_MAX_PROGRESS
        seekFonte.progress = ((tamanhoFonteInicial - FONTE_MIN) * 2).toInt().coerceIn(0, FONTE_MAX_PROGRESS)
        atualizarLabelsFonte(tamanhoFonteInicial)
        seekFonte.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val sp = FONTE_MIN + p / 2f
                if (fromUser) viewModel.setTamanhoFonte(sp)
                atualizarLabelsFonte(sp)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        tvFonteValor.setOnClickListener {
            val current = viewModel.uiState.value.tamanhoFonte
            mostrarDialogoInputNumerico(
                title = activity.getString(R.string.label_tamanho_fonte),
                value = formatarNumero(current, maxCasas = 1),
                min = FONTE_MIN, max = FONTE_MAX,
                onConfirm = { valor ->
                    val clamped = valor.coerceIn(FONTE_MIN, FONTE_MAX)
                    viewModel.setTamanhoFonte(clamped)
                    seekFonte.progress = ((clamped - FONTE_MIN) * 2).toInt().coerceIn(0, FONTE_MAX_PROGRESS)
                    atualizarLabelsFonte(clamped)
                }
            )
        }
    }

    private fun configurarSeekEspacamento(
        seekEspacamento: SeekBar,
        tvEspacamentoValor: TextView,
        espacamentoInicial: Float
    ) {
        fun atualizarLabelsEspacamento(mult: Float) {
            tvEspacamentoValor.text = formatarNumero(mult, minCasas = 2, maxCasas = 2)
            ViewCompat.setStateDescription(seekEspacamento, tvEspacamentoValor.text)
        }

        seekEspacamento.max = ESPACAMENTO_MAX_PROGRESS
        seekEspacamento.progress = ((espacamentoInicial - ESPACAMENTO_MIN) * ESCALA_ESPACAMENTO_PROGRESSO).toInt()
            .coerceIn(0, ESPACAMENTO_MAX_PROGRESS)
        atualizarLabelsEspacamento(espacamentoInicial)
        seekEspacamento.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val mult = ESPACAMENTO_MIN + p * PASSO_ESPACAMENTO_PROGRESSO
                if (fromUser) viewModel.setEspacamento(mult)
                atualizarLabelsEspacamento(mult)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        tvEspacamentoValor.setOnClickListener {
            val current = viewModel.uiState.value.espacamento
            mostrarDialogoInputNumerico(
                title = activity.getString(R.string.label_espacamento_linhas),
                value = formatarNumero(current, maxCasas = 2),
                min = ESPACAMENTO_MIN, max = ESPACAMENTO_MAX,
                onConfirm = { valor ->
                    val clamped = valor.coerceIn(ESPACAMENTO_MIN, ESPACAMENTO_MAX)
                    viewModel.setEspacamento(clamped)
                    seekEspacamento.progress = ((clamped - ESPACAMENTO_MIN) * ESCALA_ESPACAMENTO_PROGRESSO).toInt()
                        .coerceIn(0, ESPACAMENTO_MAX_PROGRESS)
                    atualizarLabelsEspacamento(clamped)
                }
            )
        }
    }

    /**
     * Cada grupo mostra a opção atual, marcada antes de registrar o listener: abrir a Aparência não dispara
     * nada. Depois, só a opção que fica marcada é aplicada.
     */
    private fun configurarBotoesAparencia(view: View, estado: ReaderUiState) {
        view.findViewById<Button>(R.id.btnTemaDoApp).setOnClickListener { SeletorTemaSheet.mostrar(activity) }

        val grupoTema = view.findViewById<MaterialButtonToggleGroup>(R.id.grupoTemaLeitura)
        val temas = mapOf(
            R.id.btnTemaClaro to TemaLeitura.CLARO,
            R.id.btnTemaEscuro to TemaLeitura.ESCURO,
            R.id.btnTemaSepia to TemaLeitura.SEPIA,
            R.id.btnTemaOled to TemaLeitura.OLED
        )
        temas.entries.firstOrNull { it.value == estado.tema }?.let { grupoTema.check(it.key) }
        grupoTema.addOnButtonCheckedListener { _, id, marcado ->
            if (marcado) temas[id]?.let { viewModel.setTema(it) }
        }

        val grupoFonte = view.findViewById<MaterialButtonToggleGroup>(R.id.grupoFonte)
        grupoFonte.check(if (estado.fonteSerifada) R.id.btnFonteSerifada else R.id.btnFontePadrao)
        grupoFonte.addOnButtonCheckedListener { _, id, marcado ->
            if (marcado) viewModel.setFonteSerifada(id == R.id.btnFonteSerifada)
        }

        // A posição na lista é o nível da margem: 0 estreita, 1 média, 2 larga.
        val margens = listOf(R.id.btnMargemEstreita, R.id.btnMargemMedia, R.id.btnMargemLarga)
        val grupoMargem = view.findViewById<MaterialButtonToggleGroup>(R.id.grupoMargem)
        grupoMargem.check(margens[estado.margemNivel.coerceIn(0, margens.lastIndex)])
        grupoMargem.addOnButtonCheckedListener { _, id, marcado ->
            if (marcado) viewModel.setMargemNivel(margens.indexOf(id))
        }

        val grupoRolagem = view.findViewById<MaterialButtonToggleGroup>(R.id.grupoRolagem)
        grupoRolagem.check(if (estado.rolagemHorizontal) R.id.btnRolagemHorizontal else R.id.btnRolagemVertical)
        grupoRolagem.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val horizontal = id == R.id.btnRolagemHorizontal
            viewModel.setRolagemHorizontal(horizontal)
            onOrientacaoRolagem(horizontal)
        }
    }

    /**
     * À esquerda (padrão), justificado, centralizado ou à direita; o chip marcado mostra o atual. Com o justificado
     * aparece o aviso de que a seleção de texto fica imprecisa (limitação do Android).
     */
    private fun configurarAlinhamento(grupo: ChipGroup, dica: TextView, atual: AlinhamentoTexto) {
        fun mostrarDica(alinhamento: AlinhamentoTexto) {
            dica.visibility = if (alinhamento == AlinhamentoTexto.JUSTIFICADO) View.VISIBLE else View.GONE
        }
        val chips = mapOf(
            AlinhamentoTexto.JUSTIFICADO to R.id.chipAlinhJustificado,
            AlinhamentoTexto.ESQUERDA to R.id.chipAlinhEsquerda,
            AlinhamentoTexto.CENTRO to R.id.chipAlinhCentro,
            AlinhamentoTexto.DIREITA to R.id.chipAlinhDireita
        )
        chips[atual]?.let { grupo.check(it) }
        mostrarDica(atual)
        grupo.setOnCheckedStateChangeListener { _, ids ->
            chips.entries.firstOrNull { it.value == ids.firstOrNull() }?.let {
                viewModel.setAlinhamento(it.key)
                mostrarDica(it.key)
            }
        }
    }

    private fun configurarBrilhoSlider(
        seekBrilho: SeekBar,
        tvBrilhoValor: TextView
    ) {
        val brilhoAtual = activity.window.attributes.screenBrightness
        val progressInicial = if (brilhoAtual < 0f) BRILHO_MAX
        else (brilhoAtual * BRILHO_MAX).toInt().coerceIn(BRILHO_MIN, BRILHO_MAX)
        seekBrilho.progress = progressInicial
        tvBrilhoValor.text = "${progressInicial}%"
        ViewCompat.setStateDescription(seekBrilho, tvBrilhoValor.text)
        seekBrilho.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val brightness = (p.toFloat() / BRILHO_MAX).coerceIn(BRILHO_MIN_FLOAT, BRILHO_MAX_FLOAT)
                tvBrilhoValor.text = "$p%"
                ViewCompat.setStateDescription(sb, tvBrilhoValor.text)
                if (fromUser) {
                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        tvBrilhoValor.setOnClickListener {
            mostrarDialogoInputNumerico(
                title = activity.getString(R.string.label_brilho),
                value = seekBrilho.progress.toString(),
                min = BRILHO_MIN.toFloat(),
                max = BRILHO_MAX.toFloat(),
                onConfirm = { valor ->
                    val p = valor.toInt().coerceIn(BRILHO_MIN, BRILHO_MAX)
                    seekBrilho.progress = p
                    tvBrilhoValor.text = "$p%"
                    val brightness = p / BRILHO_MAX.toFloat()
                    activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
                }
            )
        }
    }

    /** Número no formato do idioma do app ("16,5" em pt/es, "16.5" em en), sem ",0" sobrando. */
    private fun formatarNumero(valor: Float, minCasas: Int = 0, maxCasas: Int = 2): String =
        java.text.NumberFormat.getNumberInstance(activity.resources.configuration.locales[0]).apply {
            minimumFractionDigits = minCasas
            maximumFractionDigits = maxCasas
            isGroupingUsed = false
        }.format(valor)

    private fun mostrarDialogoInputNumerico(
        title: String,
        value: String,
        min: Float,
        max: Float,
        onConfirm: (Float) -> Unit
    ) {
        val input = EditText(activity).apply {
            setText(value)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            // O filtro padrão do teclado numérico só aceita ponto: a vírgula do teclado em português era recusada.
            keyListener = object : android.text.method.DigitsKeyListener(java.util.Locale.US, false, true) {
                override fun getAcceptedChars(): CharArray = "0123456789.,".toCharArray()
            }
            selectAll()
            setPadding(DIALOG_PADDING_H, DIALOG_PADDING_V, DIALOG_PADDING_H, DIALOG_PADDING_V)
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(activity.getString(R.string.reader_valor_entre, formatarNumero(min), formatarNumero(max)))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Aceita vírgula ou ponto: em português e espanhol o teclado e o hábito usam vírgula.
                val valor = input.text.toString().trim().replace(',', '.').toFloatOrNull()
                if (valor != null && valor in min..max) {
                    onConfirm(valor)
                } else {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.reader_valor_invalido, formatarNumero(min), formatarNumero(max)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .exibir()
    }
}
