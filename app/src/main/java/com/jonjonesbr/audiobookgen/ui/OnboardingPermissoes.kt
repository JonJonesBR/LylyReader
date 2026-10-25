package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.jonjonesbr.audiobookgen.R

/**
 * Página "Para o app funcionar bem" das boas-vindas. "Permitir" ([pedirTodas]) pede as que faltam, uma de cada
 * vez: o diálogo do Android responde pelo launcher; a tela de bateria ou de configurações responde no onResume
 * da Activity ([aoRetomar]). Tocar num cartão pede só aquela. Criar como propriedade da Activity (registra os
 * launchers antes de ela iniciar).
 *
 * @param aoMudar chamado sempre que o estado muda (a Activity atualiza o botão do rodapé).
 */
class OnboardingPermissoes(
    private val activity: AppCompatActivity,
    private val aoMudar: () -> Unit
) {
    private val pedidoNotificacoes =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { aoResponderDialogo() }
    private val pedidoAudios =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { aoResponderDialogo() }
    private var view: View? = null
    private var sequenciaAtiva = false
    private var esperandoTela = false
    private val tentadas = mutableSetOf<Int>()

    /** "Permitir" já foi usado (até o fim): o rodapé passa a mostrar "Começar". */
    var sequenciaFeita = false
        private set

    private fun liberada(qual: Int) = when (qual) {
        NOTIFICACOES -> PermissoesDoApp.notificacoesLiberadas(activity)
        AUDIOS -> PermissoesDoApp.audiosLiberados(activity)
        else -> PermissoesDoApp.bateriaLiberada(activity)
    }

    fun pendentes() = listOf(NOTIFICACOES, AUDIOS, BATERIA).filterNot { liberada(it) }

    fun vincular(container: View) {
        view = container
        container.findViewById<View>(R.id.cardPermNotificacoes).setOnClickListener { pedir(NOTIFICACOES) }
        container.findViewById<View>(R.id.cardPermAudios).setOnClickListener { pedir(AUDIOS) }
        container.findViewById<View>(R.id.cardPermBateria).setOnClickListener { pedir(BATERIA) }
        atualizar()
    }

    /** Cada cartão mostra "Toque para permitir" ou "✓ Liberado". */
    fun atualizar() {
        view?.let { v ->
            listOf(
                R.id.tvStatusPermNotificacoes to NOTIFICACOES,
                R.id.tvStatusPermAudios to AUDIOS,
                R.id.tvStatusPermBateria to BATERIA
            ).forEach { (id, qual) ->
                v.findViewById<TextView>(id).setText(
                    if (liberada(qual)) R.string.perm_liberado else R.string.perm_toque_para_permitir
                )
            }
        }
        aoMudar()
    }

    private fun pedir(qual: Int) {
        if (liberada(qual)) return
        val resultado = when (qual) {
            NOTIFICACOES -> PermissoesDoApp.pedirNotificacoes(activity, pedidoNotificacoes)
            AUDIOS -> PermissoesDoApp.pedirAudios(activity, pedidoAudios)
            else -> PermissoesDoApp.pedirBateria(activity)
        }
        when (resultado) {
            PermissoesDoApp.Pedido.TELA -> esperandoTela = true
            PermissoesDoApp.Pedido.NADA -> continuar()
            PermissoesDoApp.Pedido.DIALOGO -> Unit
        }
    }

    fun pedirTodas() {
        sequenciaAtiva = true
        tentadas.clear()
        continuar()
    }

    private fun continuar() {
        if (sequenciaAtiva) {
            val proxima = pendentes().firstOrNull { it !in tentadas }
            if (proxima == null) {
                sequenciaAtiva = false
                sequenciaFeita = true
            } else {
                tentadas += proxima
                pedir(proxima)
            }
        }
        atualizar()
    }

    // O resultado do diálogo chega antes do onResume: segue depois dele, para o próximo pedido não ser
    // confundido com a volta de uma tela do sistema.
    private fun aoResponderDialogo() {
        activity.window.decorView.post { continuar() }
    }

    /** onResume da Activity: voltou da tela de bateria ou de configurações do Android (ou de outro app). */
    fun aoRetomar() {
        if (esperandoTela) {
            esperandoTela = false
            continuar()
        } else {
            atualizar()
        }
    }

    private companion object {
        const val NOTIFICACOES = 0
        const val AUDIOS = 1
        const val BATERIA = 2
    }
}
