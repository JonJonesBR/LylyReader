package com.jonjonesbr.audiobookgen.ui

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.tabs.TabLayout
import com.jonjonesbr.audiobookgen.R

// A partir desta escala as abas de largura igual cortariam o nome; nesse caso elas rolam.
private const val ESCALA_FONTE_ABAS_ROLAM = 1.3f

/**
 * Navegação dos Ajustes (rb-11): as 4 abas (Voz e leitura, Som, Dados, Ajuda) e as páginas que abrem no lugar
 * delas (escolha da voz, ajustes avançados, Sobre, permissões), com a seta no topo para voltar. O voltar do
 * sistema também fecha a página. Extraído da [SettingsActivity]: a Activity chama [vincular] no onCreate,
 * antes das outras views, e [configurarAbas] depois delas.
 */
class AjustesPaginasController(private val activity: AppCompatActivity) {
    // Agrupam as seções em 4 categorias, ao invés de uma rolagem só.
    private val tabsSettings: TabLayout = activity.findViewById(R.id.tabsSettings)
    private val scrollSettings: NestedScrollView = activity.findViewById(R.id.scrollSettings)
    private val gruposSettings: List<View> = listOf(
        activity.findViewById<View>(R.id.grupoSettingsGeral),
        activity.findViewById<View>(R.id.grupoSettingsAudio),
        activity.findViewById<View>(R.id.grupoSettingsDados),
        activity.findViewById<View>(R.id.grupoSettingsAjuda)
    )

    // Página aberta por uma linha (escolha da voz, ajustes avançados, Sobre); null = nas abas.
    private var paginaAberta: View? = null
    val temPaginaAberta: Boolean get() = paginaAberta != null

    private val voltarDaPagina = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = fechar()
    }

    /** Liga o voltar do sistema e a seta do topo. */
    fun vincular() {
        activity.onBackPressedDispatcher.addCallback(activity, voltarDaPagina)
        activity.findViewById<ImageButton>(R.id.btnVoltarSettings).setOnClickListener { fechar() }
    }

    /** Liga as abas e abre a aba inicial pedida (-1 = a primeira). */
    fun configurarAbas(abaInicial: Int) {
        mostrarGrupo(0)
        // Fonte ampliada: as abas de largura igual cortariam o nome; nesse caso elas rolam.
        if (activity.resources.configuration.fontScale >= ESCALA_FONTE_ABAS_ROLAM) {
            tabsSettings.tabMode = TabLayout.MODE_SCROLLABLE
            tabsSettings.tabGravity = TabLayout.GRAVITY_START
        }
        tabsSettings.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                mostrarGrupo(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        // Abrir direto numa aba (ex.: "Mais ajustes" da leitura guiada abre em Som).
        if (abaInicial >= 0) tabsSettings.getTabAt(abaInicial)?.select()
    }

    /** Abre uma página no lugar das abas, com a seta no topo para voltar. */
    fun abrir(id: Int, titulo: Int) {
        gruposSettings.forEach { it.visibility = View.GONE }
        paginaAberta?.visibility = View.GONE
        paginaAberta = activity.findViewById<View>(id).apply { visibility = View.VISIBLE }
        tabsSettings.visibility = View.GONE
        activity.findViewById<View>(R.id.divisorTabsSettings).visibility = View.GONE
        activity.findViewById<View>(R.id.btnVoltarSettings).visibility = View.VISIBLE
        // A Sobre não tem ajuda própria: o "?" do topo some enquanto ela está aberta.
        activity.findViewById<View>(R.id.btnAjudaSettings).visibility =
            if (id == R.id.pagSobre) View.GONE else View.VISIBLE
        activity.findViewById<TextView>(R.id.tvTituloSettings).setText(titulo)
        voltarDaPagina.isEnabled = true
        scrollSettings.scrollTo(0, 0)
    }

    /** Fecha a página aberta e volta para a aba que estava selecionada. */
    fun fechar() {
        val pagina = paginaAberta ?: return
        pagina.visibility = View.GONE
        paginaAberta = null
        tabsSettings.visibility = View.VISIBLE
        activity.findViewById<View>(R.id.divisorTabsSettings).visibility = View.VISIBLE
        activity.findViewById<View>(R.id.btnVoltarSettings).visibility = View.GONE
        activity.findViewById<View>(R.id.btnAjudaSettings).visibility = View.VISIBLE
        activity.findViewById<TextView>(R.id.tvTituloSettings).setText(R.string.nav_ajustes)
        voltarDaPagina.isEnabled = false
        mostrarGrupo(tabsSettings.selectedTabPosition)
    }

    private fun mostrarGrupo(posicao: Int) {
        if (paginaAberta != null) fechar()
        gruposSettings.forEachIndexed { i, grupo -> grupo.visibility = if (i == posicao) View.VISIBLE else View.GONE }
        scrollSettings.scrollTo(0, 0)
    }
}
