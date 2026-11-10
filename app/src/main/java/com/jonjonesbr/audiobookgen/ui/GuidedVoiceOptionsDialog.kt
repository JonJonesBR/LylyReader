package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.EstiloDeRitmo
import com.jonjonesbr.audiobookgen.domain.PerfilAudioLivro
import com.jonjonesbr.audiobookgen.domain.QualidadeDaVoz
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import java.util.Locale

/**
 * "Voz e motor" na leitura guiada: os mesmos ajustes de Configurações › Áudio, mas só os do motor em uso,
 * sem sair do livro. Tudo vale na hora. Com "Só neste livro" marcado, tom, agudos, tom nas falas, pausa e
 * preparo à frente ficam guardados no perfil do livro; sem marcar, mudam os ajustes globais.
 */
// Cada função monta uma seção do diálogo (ritmo, qualidade, avançado…): dividir mais só espalharia a tela.
@Suppress("TooManyFunctions")
object GuidedVoiceOptionsDialog {

    fun show(
        activity: AppCompatActivity,
        manager: GuidedPlayerManager,
        prepararCapitulo: (() -> Unit)? = null,
        aoMudar: () -> Unit = {}
    ) {
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

        conteudo.addView(soNesteLivro(activity, manager, aoMudar) {
            // Os controles precisam mostrar de novo os valores globais.
            dialogo.dismiss()
            show(activity, manager, prepararCapitulo, aoMudar)
        })

        // Ritmo e qualidade são globais: valem para todos os livros, então não entram no perfil do livro.
        secaoRitmo(activity, conteudo, prefs, aoMudar)
        if (motor == "onnx" || motor == "pocket") secaoQualidade(activity, conteudo, motor, prefs, aoMudar)

        // Pré-carregar o capítulo: também no ⋮ do leitor (rb-25); aqui fica junto dos ajustes de voz.
        if (prepararCapitulo != null) {
            preparoDoCapitulo(activity, conteudo) {
                dialogo.dismiss()
                prepararCapitulo()
            }
        }

        // Timbre, diálogos, Pocket e Kokoro ficam fechados, como ajustes avançados.
        val avancado = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        conteudo.addView(botaoAvancado(activity, avancado))
        conteudo.addView(avancado)
        controlesAvancados(activity, avancado, motor, prefs, atuais, aoMudar) { mudanca -> alterar(mudanca) }

        dialogo = abrirDialogo(activity, rotuloMotor(activity, motor), conteudo)
    }

    /** Checkbox "Só neste livro": com ele marcado, os ajustes de voz deste livro vão para o perfil dele. */
    private fun soNesteLivro(
        activity: AppCompatActivity,
        manager: GuidedPlayerManager,
        aoMudar: () -> Unit,
        aoReabrir: () -> Unit
    ): View {
        val caixa = CheckBox(activity).apply {
            setText(R.string.guided_options_only_book)
            isChecked = manager.temPerfilDeAudio()
            setOnCheckedChangeListener { _, marcado ->
                manager.definirPerfilDeAudio(if (marcado) manager.ajustesDeAudio() else null)
                aoMudar()
                if (!marcado) aoReabrir()
            }
        }
        val descricao = TextView(activity).apply { setText(R.string.guided_options_only_book_desc); textSize = 12f }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(caixa)
            addView(descricao)
        }
    }

    /** Botão "Preparar capítulo" (o mesmo do ⋮ do leitor). */
    private fun preparoDoCapitulo(activity: AppCompatActivity, pai: LinearLayout, aoPreparar: () -> Unit) {
        titulo(activity, pai, R.string.guided_options_preload_title)
        pai.addView(TextView(activity).apply { setText(R.string.guided_options_preload_desc); textSize = 12f })
        pai.addView(com.google.android.material.button.MaterialButton(
            activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            setText(rotuloDoPreparo(activity, activity.getString(R.string.guided_options_preload_botao)))
            setOnClickListener { aoPreparar() }
        })
    }

    /** Monta o diálogo "Voz e motor: X", com o botão "Mais" que leva aos Ajustes de som. */
    private fun abrirDialogo(activity: AppCompatActivity, nomeDoMotor: String, conteudo: LinearLayout): AlertDialog {
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.guided_options_title, nomeDoMotor))
            .setView(ScrollView(activity).apply { addView(conteudo) })
            .setPositiveButton(R.string.timbre_close, null)
            .setNeutralButton(R.string.guided_options_more, null)
            .create()
        activity.mostrarDialogo(dialogo)
        dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialogo.dismiss()
            activity.startActivity(
                Intent(activity, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_ABA, SettingsActivity.ABA_SOM)
            )
        }
        return dialogo
    }

    private fun titulo(activity: AppCompatActivity, pai: LinearLayout, texto: Int) {
        val dp = activity.resources.displayMetrics.density
        pai.addView(TextView(activity).apply {
            setText(texto)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, (18 * dp).toInt(), 0, 0)
        })
    }

    /** Botão "Avançado": abre e fecha a área [avancado]. */
    private fun botaoAvancado(activity: AppCompatActivity, avancado: LinearLayout): View {
        val botao = com.google.android.material.button.MaterialButton(
            activity, null, com.google.android.material.R.attr.borderlessButtonStyle
        ).apply { setText(R.string.settings_avancado_mostrar) }
        botao.setOnClickListener {
            val abrir = avancado.visibility != android.view.View.VISIBLE
            avancado.visibility = if (abrir) android.view.View.VISIBLE else android.view.View.GONE
            botao.setText(if (abrir) R.string.settings_avancado_esconder else R.string.settings_avancado_mostrar)
        }
        return botao
    }

    /**
     * "Ritmo da narração": três rádios (Calma, Natural, Fluida) e a linha do estilo marcado. Grava a preferência
     * global ao escolher e não recria o diálogo.
     */
    private fun secaoRitmo(activity: AppCompatActivity, pai: LinearLayout, prefs: AppPrefs, aoMudar: () -> Unit) {
        titulo(activity, pai, R.string.aj_secao_ritmo)
        val descricao = TextView(activity).apply { textSize = 12f }
        val grupo = RadioGroup(activity)
        val botoes = EstiloDeRitmo.entries.associateWith { estilo ->
            RadioButton(activity).apply {
                id = View.generateViewId()
                setText(rotuloDoEstilo(estilo))
            }.also { grupo.addView(it) }
        }
        val atual = prefs.estiloDeRitmo
        botoes.getValue(atual).isChecked = true
        descricao.setText(descricaoDoEstilo(atual))
        grupo.setOnCheckedChangeListener { _, id ->
            val estilo = botoes.entries.firstOrNull { it.value.id == id }?.key ?: return@setOnCheckedChangeListener
            descricao.setText(descricaoDoEstilo(estilo))
            if (estilo != prefs.estiloDeRitmo) {
                prefs.estiloDeRitmo = estilo
                aoMudar()
            }
        }
        pai.addView(grupo)
        pai.addView(descricao)
        pai.addView(TextView(activity).apply { setText(R.string.guided_ritmo_global); textSize = 12f })
    }

    /**
     * "Qualidade da voz" (só Supertonic e Pocket): três rádios que gravam os passos dos dois motores ao escolher.
     * O aviso aparece só com Alta.
     */
    private fun secaoQualidade(
        activity: AppCompatActivity,
        pai: LinearLayout,
        motor: String,
        prefs: AppPrefs,
        aoMudar: () -> Unit
    ) {
        titulo(activity, pai, R.string.aj_secao_qualidade)
        val aviso = TextView(activity).apply { setText(R.string.aj_qualidade_aviso); textSize = 12f }
        val grupo = RadioGroup(activity)
        val botoes = QualidadeDaVoz.entries.associateWith { qualidade ->
            RadioButton(activity).apply {
                id = View.generateViewId()
                setText(rotuloDaQualidade(qualidade))
            }.also { grupo.addView(it) }
        }
        val atual = QualidadeDaVoz.exibida(motor, prefs.supertonicSteps, prefs.pocketPassos)
        botoes.getValue(atual).isChecked = true
        aviso.visibility = if (atual.avisaPausar) View.VISIBLE else View.GONE
        grupo.setOnCheckedChangeListener { _, id ->
            val qualidade = botoes.entries.firstOrNull { it.value.id == id }?.key ?: return@setOnCheckedChangeListener
            aviso.visibility = if (qualidade.avisaPausar) View.VISIBLE else View.GONE
            prefs.supertonicSteps = qualidade.supertonicPassos
            prefs.pocketPassos = qualidade.pocketPassos
            aoMudar()
        }
        pai.addView(grupo)
        pai.addView(aviso)
    }

    /**
     * Ajustes técnicos, dentro de "Avançado": timbre e tom nas falas, diálogos com mais vida, Pocket (expressividade
     * e mais rápido), reprodução alternativa (teste) e o paralelismo do Kokoro.
     */
    @Suppress("LongParameterList")
    private fun controlesAvancados(
        activity: AppCompatActivity,
        pai: LinearLayout,
        motor: String,
        prefs: AppPrefs,
        atuais: PerfilAudioLivro,
        aoMudar: () -> Unit,
        alterar: ((PerfilAudioLivro) -> PerfilAudioLivro) -> Unit
    ) {
        titulo(activity, pai, R.string.timbre_title)
        val timbre = TimbreDialog.controles(
            activity, prefs, aoMudar,
            inicial = atuais.tomMeiosTons to atuais.agudosDb,
            gravar = { tom, agudos -> alterar { it.copy(tomMeiosTons = tom, agudosDb = agudos) } }
        )
        pai.addView(timbre.view)

        controleDeslizante(
            activity, pai, R.string.guided_options_dialogue_pitch_title, R.string.guided_options_dialogue_pitch_desc,
            AppPrefs.TIMBRE_TOM_MIN, AppPrefs.TIMBRE_TOM_MAX, atuais.tomNasFalasMeiosTons,
            { meios ->
                if (meios == 0) activity.getString(R.string.guided_options_dialogue_pitch_off)
                else activity.getString(
                    R.string.timbre_pitch_value, String.format(Locale.getDefault(), "%+.1f", meios / 2f)
                )
            }
        ) { valor -> alterar { it.copy(tomNasFalasMeiosTons = valor) } }

        pai.addView(CheckBox(activity).apply {
            setText(R.string.aj_dialogos_vivos)
            isChecked = prefs.dialogosVivos
            setOnCheckedChangeListener { _, marcado -> prefs.dialogosVivos = marcado; aoMudar() }
        })
        if (motor == "pocket") {
            controleDeslizante(
                activity, pai, R.string.pocket_expr_titulo, R.string.pocket_expr_desc,
                AppPrefs.POCKET_EXPRESSIVIDADE_MIN, AppPrefs.POCKET_EXPRESSIVIDADE_MAX, prefs.pocketExpressividade,
                { NaturalidadeVozUi.rotuloExpressividade(activity, it) }
            ) { prefs.pocketExpressividade = it; aoMudar() }
            pai.addView(CheckBox(activity).apply {
                setText(R.string.settings_pocket_mais_rapido)
                isChecked = prefs.pocketMaisRapido
                setOnCheckedChangeListener { _, marcado -> prefs.pocketMaisRapido = marcado; aoMudar() }
            })
        }
        pai.addView(CheckBox(activity).apply {
            setText(R.string.aj_player_audiotrack_titulo)
            isChecked = prefs.playerAudioTrack
            setOnCheckedChangeListener { _, marcado -> prefs.playerAudioTrack = marcado; aoMudar() }
        })
        if (motor == "kokoro") {
            pai.addView(CheckBox(activity).apply {
                setText(R.string.btn_paralelismo_reduzido)
                isChecked = prefs.paralelismoKokoroReduzido
                setOnCheckedChangeListener { _, marcado -> prefs.paralelismoKokoroReduzido = marcado; aoMudar() }
            })
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

    /**
     * Linha com título, texto de ajuda, valor atual e deslizante; [aoSoltar] recebe o valor ao soltar o dedo,
     * ou a cada passo quando o valor muda sem arraste (TalkBack, teclado).
     */
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
        var arrastando = false
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                mostrar()
                // TalkBack e teclado mudam o valor sem arraste: grava já, senão a mudança se perdia.
                if (fromUser && !arrastando) aoSoltar(progress + minimo)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { arrastando = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                arrastando = false
                aoSoltar(barra.progress + minimo)
            }
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
