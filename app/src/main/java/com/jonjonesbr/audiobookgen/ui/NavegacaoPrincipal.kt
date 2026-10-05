package com.jonjonesbr.audiobookgen.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.jonjonesbr.audiobookgen.R

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
 */
object NavegacaoPrincipal {
    @Suppress("DEPRECATION")
    fun configurar(activity: AppCompatActivity, atual: AbaPrincipal) {
        val barra = activity.findViewById<BottomNavigationView>(R.id.navPrincipal) ?: return
        barra.selectedItemId = atual.menuId
        barra.setOnItemSelectedListener { item ->
            val alvo = AbaPrincipal.values().first { it.menuId == item.itemId }
            if (alvo != atual) {
                val intent = Intent(activity, alvo.destino)
                if (alvo == AbaPrincipal.BIBLIOTECA) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                activity.startActivity(intent)
                if (atual != AbaPrincipal.BIBLIOTECA && alvo != AbaPrincipal.BIBLIOTECA) activity.finish()
                activity.overridePendingTransition(0, 0)
            }
            true
        }
    }
}
