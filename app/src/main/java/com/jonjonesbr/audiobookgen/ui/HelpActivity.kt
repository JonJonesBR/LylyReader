package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import android.content.Context
import android.content.Intent
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

enum class HelpTopico {
    CONVERSAO, LEITOR, BIBLIOTECA, FILA, CONFIGURACOES
}

class HelpActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TOPICO = "extra_topico"

        fun start(context: Context, topico: HelpTopico) {
            context.startActivity(
                Intent(context, HelpActivity::class.java)
                    .putExtra(EXTRA_TOPICO, topico.ordinal)
            )
        }
    }

    private data class Pagina(
        val iconRes: Int = R.drawable.ic_onboarding_book,
        @Suppress("unused") val emoji: String = "",
        val titulo: String,
        val descricao: String = "",
        val passos: List<String> = emptyList(),
        val rodape: String = ""
    )

    private val paginas: List<Pagina> by lazy {
        listOf(
            Pagina(
                iconRes = R.drawable.ic_onboarding_book,
                titulo = getString(R.string.help_conversion_title),
                passos = listOf(
                    getString(R.string.help_conversion_step1),
                    getString(R.string.help_conversion_step2),
                    getString(R.string.help_conversion_step3),
                    getString(R.string.help_conversion_step4)
                ),
                rodape = getString(R.string.onboarding_p1_rodape)
            ),
            Pagina(
                iconRes = R.drawable.ic_onboarding_steps,
                titulo = getString(R.string.help_reader_title),
                passos = listOf(
                    getString(R.string.help_reader_step1),
                    getString(R.string.help_reader_step2),
                    getString(R.string.help_reader_step3),
                    getString(R.string.help_reader_step4)
                )
            ),
            Pagina(
                iconRes = R.drawable.ic_onboarding_library,
                titulo = getString(R.string.help_library_title),
                passos = listOf(
                    getString(R.string.help_library_step1),
                    getString(R.string.help_library_step2),
                    getString(R.string.help_library_step3),
                    getString(R.string.help_library_step4)
                )
            ),
            Pagina(
                emoji = "⏳",
                titulo = getString(R.string.help_queue_title),
                passos = listOf(
                    getString(R.string.help_queue_step1),
                    getString(R.string.help_queue_step2),
                    getString(R.string.help_queue_step3),
                    getString(R.string.help_queue_step4)
                )
            ),
            Pagina(
                emoji = "⚙",
                titulo = getString(R.string.help_settings_title),
                passos = listOf(
                    getString(R.string.help_settings_step1),
                    getString(R.string.help_settings_step2),
                    getString(R.string.help_settings_step3),
                    getString(R.string.help_settings_step4)
                )
            )
        )
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var btnFechar: Button
    private lateinit var btnProximo: Button
    private lateinit var layoutDots: LinearLayout
    private val dotsIndicator by lazy { PageDotsIndicator(this, layoutDots) }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.jonjonesbr.audiobookgen.util.ThemePrefs.aplicarNaActivity(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        viewPager  = findViewById(R.id.viewPagerOnboarding)
        btnFechar  = findViewById(R.id.btnPular)
        btnProximo = findViewById(R.id.btnProximo)
        layoutDots = findViewById(R.id.layoutDots)
        // T6.1 (edge-to-edge): botões ancorados na base — evita ficarem atrás da barra de gestos.
        findViewById<View>(R.id.layoutBotoesOnboarding).aplicarPaddingInferiorComNavigationBar()

        btnFechar.text = getString(R.string.help_btn_close)
        btnFechar.visibility = View.VISIBLE

        viewPager.adapter = PaginaAdapter(paginas)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dotsIndicator.atualizar(position)
                atualizarBotoes(position)
            }
        })

        dotsIndicator.criar(paginas.size)

        val topicoOrdinal = intent.getIntExtra(EXTRA_TOPICO, HelpTopico.CONVERSAO.ordinal)
        val paginaInicial = topicoOrdinal.coerceIn(0, paginas.lastIndex)
        viewPager.setCurrentItem(paginaInicial, false)
        dotsIndicator.atualizar(paginaInicial)
        atualizarBotoes(paginaInicial)

        btnFechar.setOnClickListener { finish() }
        btnProximo.setOnClickListener {
            val pos = viewPager.currentItem
            if (pos < paginas.lastIndex) viewPager.currentItem = pos + 1
            else finish()
        }
    }

    private fun atualizarBotoes(posicao: Int) {
        btnProximo.text = if (posicao == paginas.lastIndex) {
            getString(R.string.help_btn_finish)
        } else {
            getString(R.string.help_btn_next)
        }
    }

    private inner class PaginaAdapter(
        private val lista: List<Pagina>
    ) : RecyclerView.Adapter<PaginaAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon:      ImageView = view.findViewById(R.id.ivOnboardingIcon)
            val tvTitulo:    TextView = view.findViewById(R.id.tvOnboardingTitulo)
            val tvDescricao: TextView = view.findViewById(R.id.tvOnboardingDescricao)
            val tvPassos:    TextView = view.findViewById(R.id.tvOnboardingPassos)
            val tvRodape:    TextView = view.findViewById(R.id.tvOnboardingRodape)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_onboarding_page, parent, false))

        override fun getItemCount() = lista.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = lista[position]
            holder.ivIcon.setImageResource(p.iconRes)
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
