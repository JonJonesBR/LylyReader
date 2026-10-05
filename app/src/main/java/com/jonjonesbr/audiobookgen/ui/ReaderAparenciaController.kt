package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.TemaLeitura

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

    fun mostrarBottomSheet() {
        val state = viewModel.uiState.value
        val dialog = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_aparencia, null)
        dialog.setContentView(view)

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
        configurarBotoesAparencia(view)

        configurarBrilhoSlider(
            view.findViewById(R.id.seekBarBrilho),
            view.findViewById(R.id.tvBrilhoValor)
        )

        dialog.show()
    }

    private fun configurarSeekFonte(seekFonte: SeekBar, tvFonteValor: TextView, tamanhoFonteInicial: Float) {
        fun atualizarLabelsFonte(sp: Float) {
            tvFonteValor.text = String.format(java.util.Locale.US, "%.1fsp", sp)
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
                value = "%.1f".format(java.util.Locale.US, current),
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
            tvEspacamentoValor.text = String.format(java.util.Locale.US, "%.2f", mult)
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
                value = "%.2f".format(java.util.Locale.US, current),
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

    private fun configurarBotoesAparencia(view: View) {
        view.findViewById<Button>(R.id.btnTemaDoApp).setOnClickListener { SeletorTemaSheet.mostrar(activity) }
        val btnClaro  = view.findViewById<Button>(R.id.btnTemaClaro)
        val btnEscuro = view.findViewById<Button>(R.id.btnTemaEscuro)
        val btnSepia  = view.findViewById<Button>(R.id.btnTemaSepia)
        val btnOled   = view.findViewById<Button>(R.id.btnTemaOled)
        val btnFontePadrao    = view.findViewById<Button>(R.id.btnFontePadrao)
        val btnFonteSerifada  = view.findViewById<Button>(R.id.btnFonteSerifada)
        val btnMargemEstreita = view.findViewById<Button>(R.id.btnMargemEstreita)
        val btnMargemMedia    = view.findViewById<Button>(R.id.btnMargemMedia)
        val btnMargemLarga    = view.findViewById<Button>(R.id.btnMargemLarga)

        btnClaro.setOnClickListener  { viewModel.setTema(TemaLeitura.CLARO) }
        btnEscuro.setOnClickListener { viewModel.setTema(TemaLeitura.ESCURO) }
        btnSepia.setOnClickListener  { viewModel.setTema(TemaLeitura.SEPIA) }
        btnOled.setOnClickListener   { viewModel.setTema(TemaLeitura.OLED) }

        btnFontePadrao.setOnClickListener   { viewModel.setFonteSerifada(false) }
        btnFonteSerifada.setOnClickListener { viewModel.setFonteSerifada(true) }

        btnMargemEstreita.setOnClickListener { viewModel.setMargemNivel(0) }
        btnMargemMedia.setOnClickListener    { viewModel.setMargemNivel(1) }
        btnMargemLarga.setOnClickListener    { viewModel.setMargemNivel(2) }

        view.findViewById<Button>(R.id.btnRolagemVertical).setOnClickListener {
            viewModel.setRolagemHorizontal(false)
            onOrientacaoRolagem(false)
        }
        view.findViewById<Button>(R.id.btnRolagemHorizontal).setOnClickListener {
            viewModel.setRolagemHorizontal(true)
            onOrientacaoRolagem(true)
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
        seekBrilho.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val brightness = (p.toFloat() / BRILHO_MAX).coerceIn(BRILHO_MIN_FLOAT, BRILHO_MAX_FLOAT)
                tvBrilhoValor.text = "$p%"
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
            selectAll()
            setPadding(DIALOG_PADDING_H, DIALOG_PADDING_V, DIALOG_PADDING_H, DIALOG_PADDING_V)
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("Digite um valor entre $min e $max")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val texto = input.text.toString().trim()
                val valor = texto.toFloatOrNull()
                if (valor != null && valor in min..max) {
                    onConfirm(valor)
                } else {
                    Toast.makeText(activity, "Valor inválido. Use $min–$max.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
