package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.PerfilAudioLivro
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import java.util.Locale

/**
 * "Voz e motor" na leitura guiada: os mesmos ajustes de Configurações › Áudio, mas só os do motor em uso,
 * sem sair do livro. Tudo vale na hora. Com "Só neste livro" marcado, tom, agudos, tom nas falas, pausa e
 * preparo à frente ficam guardados no perfil do livro; sem marcar, mudam os ajustes globais.
 */
object GuidedVoiceOptionsDialog {

    private val MOTORES_COM_PAUSA_ENTRE_FRASES = setOf("kokoro", "onnx", "pocket")

    fun show(activity: AppCompatActivity, manager: GuidedPlayerManager, aoMudar: () -> Unit = {}) {
        val prefs = AppPrefs(activity)
        val dp = activity.resources.displayMetrics.density
        val (voz, motorSalvo) = manager.vozEMotorDoLivro()
        val motor = VoiceCatalog.effectiveEngine(voz, motorSalvo)

        /** Aplica uma mudança no perfil do livro (se há) ou nos ajustes globais. */
        fun alterar(mudanca: (PerfilAudioLivro) -> PerfilAudioLivro) {
            val novo = mudanca(manager.ajustesDeAudio())
            if (manager.temPerfilDeAudio()) {
                manager.definirPerfilDeAudio(novo)
            } else {
                prefs.tomVozMeiosTons = novo.tomMeiosTons
                prefs.suavizarAgudosDb = novo.agudosDb
                prefs.tomNasFalasMeiosTons = novo.tomNasFalasMeiosTons
                prefs.pausaFinalFraseMs = novo.pausaFinalFraseMs
                prefs.bufferParagrafosAdiante = novo.bufferAdiante
                manager.aplicarTimbre()
            }
            aoMudar()
        }

        val atuais = manager.ajustesDeAudio()
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
        }
        lateinit var dialogo: AlertDialog

        conteudo.addView(CheckBox(activity).apply {
            setText(R.string.guided_options_only_book)
            isChecked = manager.temPerfilDeAudio()
            setOnCheckedChangeListener { _, marcado ->
                manager.definirPerfilDeAudio(if (marcado) manager.ajustesDeAudio() else null)
                aoMudar()
                if (!marcado) {
                    // Os controles precisam mostrar de novo os valores globais.
                    dialogo.dismiss()
                    show(activity, manager, aoMudar)
                }
            }
        })
        conteudo.addView(TextView(activity).apply { setText(R.string.guided_options_only_book_desc); textSize = 12f })

        fun secao(titulo: Int) = conteudo.addView(TextView(activity).apply {
            setText(titulo)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (18 * dp).toInt(), 0, 0)
        })

        // Timbre e tom nas falas: valem para qualquer motor.
        secao(R.string.timbre_title)
        val timbre = TimbreDialog.controles(
            activity, prefs, aoMudar,
            inicial = atuais.tomMeiosTons to atuais.agudosDb,
            gravar = { tom, agudos -> alterar { it.copy(tomMeiosTons = tom, agudosDb = agudos) } }
        )
        conteudo.addView(timbre.view)

        controleDeslizante(
            activity, conteudo, R.string.guided_options_dialogue_pitch_title, R.string.guided_options_dialogue_pitch_desc,
            AppPrefs.TIMBRE_TOM_MIN, AppPrefs.TIMBRE_TOM_MAX, atuais.tomNasFalasMeiosTons,
            { meios ->
                if (meios == 0) activity.getString(R.string.guided_options_dialogue_pitch_off)
                else activity.getString(R.string.timbre_pitch_value, String.format(Locale.getDefault(), "%+.1f", meios / 2f))
            }
        ) { valor -> alterar { it.copy(tomNasFalasMeiosTons = valor) } }

        when (motor) {
            "onnx" -> controleDeslizante(
                activity, conteudo, R.string.settings_supertonic_steps_title, R.string.guided_steps_desc_supertonic,
                AppPrefs.SUPERTONIC_STEPS_MIN, AppPrefs.SUPERTONIC_STEPS_MAX, prefs.supertonicSteps,
                { activity.getString(R.string.settings_steps_value, it) }
            ) { prefs.supertonicSteps = it; aoMudar() }
            "pocket" -> controleDeslizante(
                activity, conteudo, R.string.settings_pocket_steps_title, R.string.settings_pocket_steps_desc,
                AppPrefs.POCKET_PASSOS_MIN, AppPrefs.POCKET_PASSOS_MAX, prefs.pocketPassos,
                { activity.getString(R.string.settings_steps_value, it) }
            ) { prefs.pocketPassos = it; aoMudar() }
        }

        if (motor in MOTORES_COM_PAUSA_ENTRE_FRASES) {
            controleDeslizante(
                activity, conteudo, R.string.settings_pausa_frase_title, R.string.settings_pausa_frase_desc,
                0, 2000, atuais.pausaFinalFraseMs,
                { activity.getString(R.string.settings_pausa_frase_value, it) }
            ) { valor -> alterar { it.copy(pausaFinalFraseMs = valor) } }
        }

        // Quantas unidades preparar à frente: 0 = automático, 1..8 explícito.
        val atualBuffer = if (atuais.bufferAdiante == AppPrefs.BUFFER_ADIANTE_AUTOMATICO) 0 else atuais.bufferAdiante
        controleDeslizante(
            activity, conteudo, R.string.settings_buffering_title, R.string.settings_buffer_paragrafos_desc,
            0, AppPrefs.BUFFER_ADIANTE_MAX, atualBuffer,
            {
                if (it == 0) activity.getString(R.string.settings_buffer_paragrafos_valor_automatico)
                else activity.getString(R.string.settings_buffer_paragrafos_valor, it)
            }
        ) { valor -> alterar { it.copy(bufferAdiante = if (valor == 0) AppPrefs.BUFFER_ADIANTE_AUTOMATICO else valor) } }

        if (motor == "kokoro") {
            conteudo.addView(TextView(activity).apply {
                setText(R.string.settings_paralelismo_kokoro_desc)
                textSize = 12f
                setPadding(0, (16 * dp).toInt(), 0, 0)
            })
            conteudo.addView(CheckBox(activity).apply {
                setText(R.string.btn_paralelismo_reduzido)
                isChecked = prefs.paralelismoKokoroReduzido
                setOnCheckedChangeListener { _, marcado -> prefs.paralelismoKokoroReduzido = marcado; aoMudar() }
            })
        }

        dialogo = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.guided_options_title, rotuloMotor(activity, motor)))
            .setView(ScrollView(activity).apply { addView(conteudo) })
            .setPositiveButton(R.string.timbre_close, null)
            .setNeutralButton(R.string.guided_options_more, null)
            .create()
        dialogo.show()
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialogo.dismiss()
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
    }

    private fun rotuloMotor(activity: AppCompatActivity, motor: String): String = when (motor) {
        "onnx" -> "Supertonic"
        "pocket" -> "Pocket TTS"
        "kokoro" -> "Kokoro"
        "piper" -> "Piper"
        "gemini" -> "Gemini"
        "elevenlabs" -> "ElevenLabs"
        "edge" -> "Edge"
        "android" -> activity.getString(R.string.guided_options_engine_android)
        else -> motor.replaceFirstChar { it.uppercase() }
    }

    /** Linha com título, texto de ajuda, valor atual e deslizante; [aoSoltar] recebe o valor ao soltar o dedo. */
    private fun controleDeslizante(
        activity: AppCompatActivity,
        pai: LinearLayout,
        titulo: Int,
        descricao: Int,
        minimo: Int,
        maximo: Int,
        atual: Int,
        rotulo: (Int) -> String,
        aoSoltar: (Int) -> Unit
    ) {
        val dp = activity.resources.displayMetrics.density
        val valor = TextView(activity).apply { textSize = 15f; setPadding(0, (6 * dp).toInt(), 0, 0) }
        val barra = SeekBar(activity).apply {
            max = maximo - minimo
            progress = atual.coerceIn(minimo, maximo) - minimo
        }
        fun mostrar() { valor.text = rotulo(barra.progress + minimo) }
        mostrar()
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = mostrar()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) = aoSoltar(barra.progress + minimo)
        })
        pai.addView(TextView(activity).apply {
            setText(titulo)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (18 * dp).toInt(), 0, 0)
        })
        pai.addView(TextView(activity).apply { setText(descricao); textSize = 12f })
        pai.addView(valor)
        pai.addView(barra)
    }
}
