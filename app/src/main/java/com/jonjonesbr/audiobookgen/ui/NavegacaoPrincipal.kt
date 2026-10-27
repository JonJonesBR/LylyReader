package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.service.ConclusoesDeAudiobook
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** As quatro telas de topo, na ordem da barra de navegação inferior. */
enum class AbaPrincipal(val menuId: Int, val destino: Class<out AppCompatActivity>) {
    BIBLIOTECA(R.id.nav_biblioteca, MeusLivrosActivity::class.java),
    AUDIOBOOKS(R.id.nav_audiobooks, LibraryActivity::class.java),
    DOWNLOADS(R.id.nav_downloads, DownloadCentralActivity::class.java),
    AJUSTES(R.id.nav_ajustes, SettingsActivity::class.java)
}

/**
 * Barra inferior (Biblioteca · Audiobooks · Downloads · Ajustes) igual em todas as telas de topo.
 * Pilha: a Biblioteca é a raiz. Ir de uma aba para outra abre a nova e fecha a atual (a pilha não cresce);
 * voltar à Biblioteca reaproveita a que está embaixo (ou abre uma nova se não houver).
 * O selo da aba Audiobooks mostra quantos audiobooks ficaram prontos sem ser vistos (ver [ConclusoesDeAudiobook]).
 */
object NavegacaoPrincipal {
    @Suppress("DEPRECATION")
    fun configurar(activity: AppCompatActivity, atual: AbaPrincipal) {
        val barra = activity.findViewById<BottomNavigationView>(R.id.navPrincipal) ?: return
        barra.selectedItemId = atual.menuId
        barra.setOnItemSelectedListener { item ->
            abrirAba(activity, atual, AbaPrincipal.values().first { it.menuId == item.itemId })
            true
        }
        atualizarSelo(activity, barra, atual)
        // configurar() roda de novo a cada onResume: o observador é registrado só uma vez por barra
        // (cada tela tem a sua barra).
        if (barra.tag == null) {
            val observador = ObservadorDeConclusoes(activity, barra, atual)
            barra.tag = observador
            activity.lifecycle.addObserver(observador)
        }
    }

    /** Abre a aba [alvo] a partir de [atual]; não faz nada se já estiver nela. */
    @Suppress("DEPRECATION")
    fun abrirAba(activity: AppCompatActivity, atual: AbaPrincipal, alvo: AbaPrincipal) {
        if (alvo == atual) return
        val intent = Intent(activity, alvo.destino)
        if (alvo == AbaPrincipal.BIBLIOTECA) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        activity.startActivity(intent)
        if (atual != AbaPrincipal.BIBLIOTECA && alvo != AbaPrincipal.BIBLIOTECA) activity.finish()
        activity.overridePendingTransition(0, 0)
    }

    /** Selo com o número de audiobooks não vistos. Some na própria aba Audiobooks e quando não há nenhum. */
    private fun atualizarSelo(context: Context, barra: BottomNavigationView, atual: AbaPrincipal) {
        val naoVistos = AppPrefs(context).audiobooksConcluidosNaoVistos
        if (atual == AbaPrincipal.AUDIOBOOKS || naoVistos <= 0) {
            barra.removeBadge(R.id.nav_audiobooks)
        } else {
            barra.getOrCreateBadge(R.id.nav_audiobooks).number = naoVistos
        }
    }

    /** Aviso de conclusão, ancorado acima da barra; o botão leva à aba Audiobooks. */
    private fun mostrarAvisoDeConclusao(
        activity: AppCompatActivity, atual: AbaPrincipal, barra: BottomNavigationView, titulo: String
    ) {
        val texto = activity.getString(R.string.audiobook_pronto_aviso, titulo)
        val aviso = Snackbar.make(barra, texto, Snackbar.LENGTH_LONG)
        aviso.setAnchorView(barra)
        aviso.setAction(R.string.audiobook_pronto_acao) { abrirAba(activity, atual, AbaPrincipal.AUDIOBOOKS) }
        aviso.show()
    }

    /**
     * Colhe as conclusões só enquanto a tela está visível (entre onStart e onStop): o aviso e o selo valem
     * para o que acontece com a pessoa na tela. Fora disso fica só o contador salvo.
     * Sai do ciclo de vida na destruição da tela.
     */
    private class ObservadorDeConclusoes(
        private val activity: AppCompatActivity,
        private val barra: BottomNavigationView,
        private val atual: AbaPrincipal
    ) : DefaultLifecycleObserver {

        private var coleta: Job? = null

        override fun onStart(owner: LifecycleOwner) {
            coleta = activity.lifecycleScope.launch {
                ConclusoesDeAudiobook.eventos.collect { titulo -> aoConcluir(titulo) }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            coleta?.cancel()
            coleta = null
        }

        override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
        }

        private fun aoConcluir(titulo: String) {
            if (atual == AbaPrincipal.AUDIOBOOKS) {
                // Na própria aba Audiobooks o livro novo já aparece na lista: não conta como não visto.
                ConclusoesDeAudiobook.marcarVistos(activity)
            } else {
                NavegacaoPrincipal.mostrarAvisoDeConclusao(activity, atual, barra, titulo)
            }
            NavegacaoPrincipal.atualizarSelo(activity, barra, atual)
        }
    }
}
