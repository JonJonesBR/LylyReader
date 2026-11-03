package com.jonjonesbr.audiobookgen.ui

import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.jonjonesbr.audiobookgen.service.GuidedPlaybackBridge
import com.jonjonesbr.audiobookgen.service.GuidedPlayerManager
import com.jonjonesbr.audiobookgen.service.GuidedState

/** Situação da Activity no momento de soltar a pausa: [DESTRUIDA] é fechando ou destruída pelo sistema. */
internal enum class EstadoDaTela { VIVA, GIRO, DESTRUIDA }

/** Lê o estado da tela na hora de soltar (o diálogo fechado no onDestroy chega depois, já com a tela DESTROYED). */
internal fun estadoDaTela(activity: AppCompatActivity): EstadoDaTela = when {
    activity.lifecycle.currentState != Lifecycle.State.DESTROYED -> EstadoDaTela.VIVA
    activity.isChangingConfigurations -> EstadoDaTela.GIRO
    else -> EstadoDaTela.DESTRUIDA
}

/**
 * "Tocando" para esta regra é o mesmo que o GuidedReadingService usa para perda de foco: tocando ou carregando.
 * Na troca de livro não pausa, porque o play() do livro seguinte limparia a pausa.
 */
internal fun devePausarParaOuvir(estado: GuidedState, pausado: Boolean, trocandoDeLivro: Boolean): Boolean {
    if (estado.index < 0 || pausado || trocandoDeLivro) return false
    return estado.isPlaying || estado.isLoading
}

/** Tudo o que [deveRetomarAposOuvir] precisa saber, lido no momento de soltar. */
internal data class SituacaoAoSoltar(
    val mesmaSessao: Boolean,
    val semComandosDesdeAPausa: Boolean,
    val indice: Int,
    val pausado: Boolean,
    val trocandoDeLivro: Boolean,
    val chamadaEmCurso: Boolean,
    val previewEmCurso: Boolean,
    val tela: EstadoDaTela,
)

/**
 * Retoma só se foi esta pausa que parou a leitura e nada mudou desde então: sem comando de reprodução, na mesma
 * sessão, sem chamada, sem prévia tocando e com a tela viva (ou só girando). Tela destruída não retoma, para não
 * tocar com a pessoa fora da tela; a guiada fica pausada e a notificação oferece o play.
 */
internal fun deveRetomarAposOuvir(s: SituacaoAoSoltar): Boolean = when {
    s.tela == EstadoDaTela.DESTRUIDA -> false
    !s.mesmaSessao -> false
    !s.semComandosDesdeAPausa -> false
    s.indice < 0 -> false
    !s.pausado -> false
    s.trocandoDeLivro -> false
    s.chamadaEmCurso -> false
    s.previewEmCurso -> false
    else -> true
}

/**
 * Pausa a leitura guiada enquanto a pessoa ouve uma pronúncia ou uma amostra de voz, e retoma depois (rb-24).
 *
 * Funciona como um contador de "seguradas": cada diálogo aberto do fluxo de pronúncia e cada áudio pedido segura
 * a leitura. A pausa acontece quando a primeira segurada começa com a guiada tocando; a decisão de retomar só
 * acontece quando a última solta. Só na main thread.
 *
 * Três estados de tela ao soltar:
 * - VIVA: fechamento normal; retoma se a regra deixar.
 * - GIRO (girar a tela): o diálogo some e o manager vive no application; retoma se a regra deixar.
 * - DESTRUIDA (fechando, ou tela apagada com o sistema destruindo a Activity): não retoma. A guiada fica pausada
 *   com a notificação e a barra com o play, então não fica presa.
 *
 * Casos de borda: prévia ainda gerando ou tocando no giro não retoma (a espera da Activity antiga é cancelada e o
 * [previewEmCurso] segue verdadeiro); chamada em curso não retoma; "Importar" deixa a guiada retomar com o
 * seletor de arquivos aberto, e ao voltar o dicionário pausa de novo (aceito).
 *
 * Sem Activity dentro da classe (recebe lambdas), para testar na JVM.
 */
internal class PausaParaOuvir(
    private val obterManager: () -> GuidedPlayerManager?,
    private val previewEmCurso: () -> Boolean,
    private val chamadaEmCurso: () -> Boolean,
    private val estadoDaTela: () -> EstadoDaTela,
    private val sessaoVigente: () -> GuidedPlayerManager? = { GuidedPlaybackBridge.manager },
) {
    /** Uma segurada. [soltar] age uma vez só: chamar de novo não conta duas vezes. */
    internal fun interface Segura {
        fun soltar()
    }

    private var seguradas = 0
    private var pausa: PausaFeita? = null

    /**
     * Segura a leitura. Com [pausaSoNoInicio] (diálogos), só pausa quando é a primeira segurada aberta: se a pessoa
     * deu play pela notificação com o dicionário aberto, abrir o próximo diálogo do fluxo não pausa de novo. O
     * "Ouvir" e a amostra usam false: reavaliam sempre, para não tocar por cima.
     */
    fun segurar(pausaSoNoInicio: Boolean = false): Segura {
        // Sem manager (leitura sem áudio): nada muda.
        val manager = obterManager() ?: return Segura {}
        val inicio = seguradas == 0
        seguradas++
        val podePausar = inicio || !pausaSoNoInicio
        if (podePausar && devePausarParaOuvir(manager.state.value, manager.estaPausado, manager.trocandoDeLivro)) {
            manager.pause()
            pausa = PausaFeita(manager, manager.comandosDeReproducao)
        }
        var solta = false
        return Segura {
            if (!solta) {
                solta = true
                soltarUma()
            }
        }
    }

    /** Segura a leitura enquanto o diálogo estiver aberto. Solta com um post, depois do onDismiss. */
    fun acompanhar(dialogo: AlertDialog) {
        val segura = segurar(pausaSoNoInicio = true)
        // Handler, e não view.post: o post da view não roda com a view já desanexada.
        dialogo.setOnDismissListener { Handler(Looper.getMainLooper()).post { segura.soltar() } }
    }

    private fun soltarUma() {
        seguradas = (seguradas - 1).coerceAtLeast(0)
        if (seguradas > 0) return
        val feita = pausa ?: return
        pausa = null
        val manager = feita.manager
        val situacao = SituacaoAoSoltar(
            mesmaSessao = sessaoVigente() === manager,
            semComandosDesdeAPausa = manager.comandosDeReproducao == feita.comandos,
            indice = manager.state.value.index,
            pausado = manager.estaPausado,
            trocandoDeLivro = manager.trocandoDeLivro,
            chamadaEmCurso = chamadaEmCurso(),
            previewEmCurso = previewEmCurso(),
            tela = estadoDaTela(),
        )
        if (deveRetomarAposOuvir(situacao)) manager.resume()
    }

    /** A pausa feita por esta classe e o contador de comandos no momento dela (lido depois do pause()). */
    private class PausaFeita(val manager: GuidedPlayerManager, val comandos: Long)
}
