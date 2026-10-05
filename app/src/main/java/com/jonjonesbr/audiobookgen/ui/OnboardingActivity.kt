package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.jonjonesbr.audiobookgen.util.aplicarPaddingInferiorComNavigationBar

class OnboardingActivity : AppCompatActivity() {

    private data class Pagina(
        val iconRes: Int,
        val titulo: String,
        val descricao: String = "",
        val passos: List<String> = emptyList(),
        val rodape: String = ""
    )

    private fun buildPaginas() = listOf(
        Pagina(
            iconRes   = R.drawable.ic_nav_livros,
            titulo    = getString(R.string.onboarding_t1_titulo),
            descricao = getString(R.string.onboarding_t1_descricao),
            rodape    = getString(R.string.onboarding_p1_rodape)
        ),
        Pagina(
            iconRes   = R.drawable.ic_abrir_arquivo,
            titulo    = getString(R.string.onboarding_t2_titulo),
            descricao = getString(R.string.onboarding_t2_descricao)
        ),
        Pagina(
            iconRes   = R.drawable.ic_player_play,
            titulo    = getString(R.string.onboarding_t3_titulo),
            descricao = getString(R.string.onboarding_t3_descricao)
        ),
        Pagina(
            iconRes   = R.drawable.ic_nav_audiobooks,
            titulo    = getString(R.string.onboarding_t4_titulo),
            descricao = getString(R.string.onboarding_t4_descricao)
        ),
        Pagina(
            iconRes   = R.drawable.ic_paleta,
            titulo    = getString(R.string.onboarding_t5_titulo),
            descricao = getString(R.string.onboarding_t5_descricao)
        )
    )

    private lateinit var paginas: List<Pagina>
    private lateinit var viewPager: ViewPager2
    private lateinit var btnPular: Button
    private lateinit var btnProximo: Button
    private lateinit var layoutDots: LinearLayout
    private val dotsIndicator by lazy { PageDotsIndicator(this, layoutDots) }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        paginas      = buildPaginas()
        viewPager    = findViewById(R.id.viewPagerOnboarding)
        btnPular     = findViewById(R.id.btnPular)
        btnProximo   = findViewById(R.id.btnProximo)
        layoutDots   = findViewById(R.id.layoutDots)
        // T6.1 (edge-to-edge): botões ancorados na base — evita ficarem atrás da barra de gestos.
        findViewById<View>(R.id.layoutBotoesOnboarding).aplicarPaddingInferiorComNavigationBar()

        viewPager.adapter = PaginaAdapter(paginas)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dotsIndicator.atualizar(position)
                atualizarBotoes(position)
            }
        })

        dotsIndicator.criar(paginas.size)
        dotsIndicator.atualizar(0)
        atualizarBotoes(0)

        btnPular.setOnClickListener { concluir() }

        btnProximo.setOnClickListener {
            val pos = viewPager.currentItem
            if (pos < paginas.lastIndex) {
                viewPager.currentItem = pos + 1
            } else {
                concluir()
            }
        }
    }

    // ── Botões ────────────────────────────────────────────────────────────────

    private fun atualizarBotoes(posicao: Int) {
        val ultima = posicao == paginas.lastIndex
        btnPular.visibility = if (ultima) View.INVISIBLE else View.VISIBLE
        btnProximo.text = if (ultima) {
            getString(R.string.onboarding_btn_comecar)
        } else {
            getString(R.string.onboarding_btn_proximo_seta)
        }
    }

    // ── Conclusão ─────────────────────────────────────────────────────────────

    private fun concluir() {
        // Revisão (Ajustes → Ajuda): só fecha; não mexe no estado do primeiro acesso.
        if (!intent.getBooleanExtra(EXTRA_REVISAO, false)) {
            com.jonjonesbr.audiobookgen.data.AppPrefs(this).onboardingDone = true
        }
        finish()
    }

    companion object {
        /** Abre o tour de novo, sem alterar `onboardingDone` (usado em Ajustes → Ajuda). */
        const val EXTRA_REVISAO = "extra_revisao_tour"
    }

    // ── Adapter interno ───────────────────────────────────────────────────────

    private inner class PaginaAdapter(
        private val lista: List<Pagina>
    ) : RecyclerView.Adapter<PaginaAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon:     ImageView = view.findViewById(R.id.ivOnboardingIcon)
            val tvTitulo:   TextView = view.findViewById(R.id.tvOnboardingTitulo)
            val tvDescricao: TextView = view.findViewById(R.id.tvOnboardingDescricao)
            val tvPassos:   TextView = view.findViewById(R.id.tvOnboardingPassos)
            val tvRodape:   TextView = view.findViewById(R.id.tvOnboardingRodape)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_onboarding_page, parent, false))

        override fun getItemCount() = lista.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = lista[position]
            holder.ivIcon.setImageResource(p.iconRes)
            holder.ivIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(holder.ivIcon, androidx.appcompat.R.attr.colorPrimary)
            )
            holder.tvTitulo.text = p.titulo

            if (p.descricao.isNotBlank()) {
                holder.tvDescricao.text = p.descricao
                holder.tvDescricao.visibility = View.VISIBLE
            } else {
                holder.tvDescricao.visibility = View.GONE
            }

            if (p.passos.isNotEmpty()) {
                holder.tvPassos.text = p.passos
                    .mapIndexed { i, s -> "${i + 1}.  $s" }
                    .joinToString("\n")
                holder.tvPassos.visibility = View.VISIBLE
            } else {
                holder.tvPassos.visibility = View.GONE
            }

            if (p.rodape.isNotBlank()) {
                holder.tvRodape.text = p.rodape
                holder.tvRodape.visibility = View.VISIBLE
            } else {
                holder.tvRodape.visibility = View.GONE
            }
        }
    }
}
