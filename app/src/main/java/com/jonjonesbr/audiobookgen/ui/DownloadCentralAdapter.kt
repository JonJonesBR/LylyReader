package com.jonjonesbr.audiobookgen.ui

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.domain.EstadoDownload
import com.jonjonesbr.audiobookgen.domain.IdiomaDownload
import com.jonjonesbr.audiobookgen.domain.SecaoDownload
import com.jonjonesbr.audiobookgen.domain.SecoesDownload
import com.jonjonesbr.audiobookgen.domain.TipoPacote
import com.jonjonesbr.audiobookgen.service.AndamentoDownload
import com.jonjonesbr.audiobookgen.service.GrupoDownload
import com.jonjonesbr.audiobookgen.service.ItemBaixavel

/** Um item da aba Downloads e o andamento atual dele. */
data class LinhaDownload(
    val item: ItemBaixavel,
    val andamento: AndamentoDownload
) {
    val estado: EstadoDownload get() = andamento.estado
    val ehLivro: Boolean get() = item.grupo == GrupoDownload.LIVROS
    /** Voz clonada ou importada (e os codificadores da clonagem): fica em "Minhas vozes". */
    val ehDoUsuario: Boolean get() = item.grupo == GrupoDownload.IMPORTADAS || item.grupo == GrupoDownload.CODIFICADORES
    val secao: SecaoDownload get() = SecoesDownload.secao(ehLivro, ehDoUsuario, estado)
    /** Não pronto e baixar não resolve (ex.: voz clonada sem o codificador do idioma). */
    val bloqueado: Boolean get() = estado == EstadoDownload.NAO_BAIXADO && andamento.bloqueio != null
    /** Voz do usuário ainda não pronta: não se baixa pela Central (ou não resolve), mas se apaga. */
    val apagavelSemEstarPronta: Boolean
        get() = estado == EstadoDownload.NAO_BAIXADO && item.grupo == GrupoDownload.IMPORTADAS
    /** Dá para baixar agora (entra em "Baixar todas"). */
    val baixavel: Boolean
        get() = item.download != null && !bloqueado &&
            (estado == EstadoDownload.NAO_BAIXADO || estado == EstadoDownload.PAUSADO || estado == EstadoDownload.ERRO)
}

/** Linhas da lista: título de seção, item, "Criar ou importar voz" ou o aviso de filtro vazio. */
sealed interface LinhaLista {
    data class Cabecalho(val secao: SecaoDownload, val comPausarTudo: Boolean) : LinhaLista
    data class Item(val linha: LinhaDownload) : LinhaLista
    object CriarVoz : LinhaLista
    object Vazio : LinhaLista

    companion object {
        /** Agrupa [linhas] nas seções, na ordem de [SecaoDownload]; "Minhas vozes" sempre aparece (com o +). */
        fun montar(linhas: List<LinhaDownload>, temAtividade: Boolean): List<LinhaLista> {
            val porSecao = linhas.groupBy { it.secao }
            val lista = mutableListOf<LinhaLista>()
            for (secao in SecaoDownload.values()) {
                val daSecao = porSecao[secao].orEmpty()
                if (daSecao.isEmpty() && secao != SecaoDownload.MINHAS_VOZES) continue
                lista += Cabecalho(secao, comPausarTudo = secao == SecaoDownload.BAIXANDO && temAtividade)
                daSecao.mapTo(lista) { Item(it) }
                if (secao == SecaoDownload.MINHAS_VOZES) lista += CriarVoz
            }
            if (linhas.isEmpty()) lista.add(0, Vazio)
            return lista
        }
    }
}

class DownloadCentralAdapter(
    private val onPrincipal: (LinhaDownload) -> Unit,
    private val onSecundario: (LinhaDownload) -> Unit,
    private val onPausarTudo: () -> Unit,
    private val onCriarVoz: () -> Unit
) : ListAdapter<LinhaLista, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val BARRA_MAX = 1000
        private const val TIPO_CABECALHO = 0
        private const val TIPO_ITEM = 1
        private const val TIPO_CRIAR_VOZ = 2
        private const val TIPO_VAZIO = 3
        private val DIFF = object : DiffUtil.ItemCallback<LinhaLista>() {
            override fun areItemsTheSame(old: LinhaLista, new: LinhaLista) = when {
                old is LinhaLista.Item && new is LinhaLista.Item -> old.linha.item.id == new.linha.item.id
                old is LinhaLista.Cabecalho && new is LinhaLista.Cabecalho -> old.secao == new.secao
                else -> old === new
            }
            // ItemBaixavel carrega lambdas: a identidade do item já basta, o que muda é o andamento.
            override fun areContentsTheSame(old: LinhaLista, new: LinhaLista) = when {
                old is LinhaLista.Item && new is LinhaLista.Item -> old.linha.andamento == new.linha.andamento
                else -> old == new
            }
        }
    }

    class ItemVH(view: View) : RecyclerView.ViewHolder(view) {
        val nome: TextView = view.findViewById(R.id.tvDownloadNome)
        val descricao: TextView = view.findViewById(R.id.tvDownloadDescricao)
        val estado: TextView = view.findViewById(R.id.tvDownloadEstado)
        val barra: ProgressBar = view.findViewById(R.id.pbDownload)
        val principal: Button = view.findViewById(R.id.btnDownloadPrincipal)
        val secundario: Button = view.findViewById(R.id.btnDownloadSecundario)
    }

    class CabecalhoVH(view: View) : RecyclerView.ViewHolder(view) {
        val titulo: TextView = view.findViewById(R.id.tvSecaoDownload)
        val acao: Button = view.findViewById(R.id.btnSecaoDownload)
    }

    class SimplesVH(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is LinhaLista.Cabecalho -> TIPO_CABECALHO
        is LinhaLista.Item -> TIPO_ITEM
        LinhaLista.CriarVoz -> TIPO_CRIAR_VOZ
        LinhaLista.Vazio -> TIPO_VAZIO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TIPO_CABECALHO -> CabecalhoVH(inflater.inflate(R.layout.item_download_cabecalho, parent, false))
            TIPO_ITEM -> ItemVH(inflater.inflate(R.layout.item_download, parent, false))
            TIPO_CRIAR_VOZ -> SimplesVH(inflater.inflate(R.layout.item_download_criar_voz, parent, false)).also {
                it.itemView.setOnClickListener { onCriarVoz() }
            }
            else -> SimplesVH(inflater.inflate(R.layout.item_download_vazio, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val linha = getItem(position)) {
            is LinhaLista.Cabecalho -> bindCabecalho(holder as CabecalhoVH, linha)
            is LinhaLista.Item -> bindItem(holder as ItemVH, linha.linha)
            LinhaLista.CriarVoz, LinhaLista.Vazio -> Unit
        }
    }

    private fun bindCabecalho(holder: CabecalhoVH, linha: LinhaLista.Cabecalho) {
        holder.titulo.setText(
            when (linha.secao) {
                SecaoDownload.BAIXANDO -> R.string.dl_secao_baixando
                SecaoDownload.NO_APARELHO -> R.string.dl_secao_no_aparelho
                SecaoDownload.PARA_BAIXAR -> R.string.dl_secao_para_baixar
                SecaoDownload.MINHAS_VOZES -> R.string.dl_secao_minhas_vozes
                SecaoDownload.LIVROS -> R.string.dl_central_grupo_livros
            }
        )
        holder.acao.visibility = if (linha.comPausarTudo) View.VISIBLE else View.GONE
        holder.acao.setText(R.string.dl_central_pausar_tudo)
        holder.acao.setOnClickListener { onPausarTudo() }
    }

    private fun bindItem(holder: ItemVH, linha: LinhaDownload) {
        val ctx = holder.itemView.context
        val a = linha.andamento
        holder.nome.text = SecoesDownload.nomeAmigavel(nomeExibidoDoPacote(ctx, linha.item.id, linha.item.nome))
        holder.descricao.text = descricao(ctx, linha)
        holder.descricao.visibility = if (holder.descricao.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val mostraBarra = linha.estado == EstadoDownload.BAIXANDO || linha.estado == EstadoDownload.PAUSADO ||
            linha.estado == EstadoDownload.NA_FILA
        holder.barra.visibility = if (mostraBarra) View.VISIBLE else View.GONE
        holder.barra.isIndeterminate = linha.estado == EstadoDownload.BAIXANDO && a.total <= 0L
        holder.barra.max = BARRA_MAX
        holder.barra.progress = (a.fracao * BARRA_MAX).toInt()

        holder.estado.text = estado(ctx, linha)

        val (textoPrincipal, textoSecundario) = botoes(linha)
        holder.principal.visibility = if (textoPrincipal == null) View.GONE else View.VISIBLE
        textoPrincipal?.let { holder.principal.setText(it) }
        holder.secundario.visibility = if (textoSecundario == null) View.GONE else View.VISIBLE
        textoSecundario?.let { holder.secundario.setText(it) }
        holder.principal.setOnClickListener { onPrincipal(linha) }
        holder.secundario.setOnClickListener { onSecundario(linha) }
    }

    /** Rótulos do botão principal e do secundário (vermelho) para o estado da linha; null = sem botão. */
    private fun botoes(linha: LinhaDownload): Pair<Int?, Int?> = when (linha.estado) {
        EstadoDownload.NAO_BAIXADO -> {
            val principal = if (linha.bloqueado || linha.item.download == null) null else R.string.dl_central_baixar
            principal to (if (linha.apagavelSemEstarPronta) R.string.dl_central_apagar else null)
        }
        EstadoDownload.NA_FILA -> R.string.dl_central_tirar_fila to null
        EstadoDownload.BAIXANDO -> R.string.dl_central_pausar to R.string.dl_central_cancelar
        EstadoDownload.PAUSADO -> R.string.dl_central_continuar to R.string.dl_central_cancelar
        EstadoDownload.ERRO -> R.string.dl_central_tentar_novamente to R.string.dl_central_cancelar
        EstadoDownload.PRONTO -> null to R.string.dl_central_apagar
    }

    /** "Vozes naturais · PT, EN, ES": o que é o pacote e para que idiomas. */
    private fun descricao(ctx: Context, linha: LinhaDownload): String {
        val tipo = when (SecoesDownload.tipo(linha.item.id, linha.ehLivro, linha.ehDoUsuario)) {
            TipoPacote.SUPERTONIC -> R.string.dl_tipo_supertonic
            TipoPacote.KOKORO -> R.string.dl_tipo_kokoro
            TipoPacote.PIPER -> R.string.dl_tipo_piper
            TipoPacote.POCKET -> R.string.dl_tipo_pocket
            TipoPacote.MMS -> R.string.dl_tipo_mms
            TipoPacote.CLONADA -> R.string.dl_tipo_clonada
            TipoPacote.IMPORTADA -> R.string.dl_tipo_importada
            TipoPacote.LIVRO -> null
            TipoPacote.OUTRO -> R.string.dl_tipo_outro
        }
        val idiomas = linha.item.idiomas
        val textoIdiomas = SecoesDownload.idiomasCurtos(idiomas).ifBlank {
            if (IdiomaDownload.MULTILINGUE in idiomas) ctx.getString(R.string.dl_varios_idiomas) else ""
        }
        return listOfNotNull(tipo?.let { ctx.getString(it) }, textoIdiomas.ifBlank { null }).joinToString(" · ")
    }

    private fun estado(ctx: Context, linha: LinhaDownload): String {
        val a = linha.andamento
        val pct = (a.fracao * 100).toInt()
        val bloqueio = a.bloqueio
        if (linha.bloqueado && bloqueio != null) return ctx.getString(bloqueio)
        return when (linha.estado) {
            EstadoDownload.NAO_BAIXADO -> ctx.getString(R.string.dl_central_estado_nao_baixado, linha.item.tamanhoMb)
            EstadoDownload.NA_FILA -> ctx.getString(R.string.dl_central_estado_fila)
            EstadoDownload.BAIXANDO ->
                if (a.total > 0L) {
                    ctx.getString(
                        R.string.dl_central_estado_baixando,
                        Formatter.formatShortFileSize(ctx, a.bytes), Formatter.formatShortFileSize(ctx, a.total), pct
                    )
                } else {
                    ctx.getString(R.string.dl_central_estado_baixando_sem_total)
                }
            EstadoDownload.PAUSADO -> ctx.getString(R.string.dl_central_estado_pausado, pct)
            EstadoDownload.PRONTO ->
                ctx.getString(R.string.dl_central_estado_pronto, Formatter.formatShortFileSize(ctx, a.ocupadoBytes))
            EstadoDownload.ERRO -> ctx.getString(R.string.dl_central_estado_erro, a.erro.orEmpty())
        }
    }
}

/** Primeira linha da lista: os filtros de idioma, que rolam junto com ela. */
class CabecalhoFixoAdapter(private val vista: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        // A mesma view serve a todo holder novo: se a lista recriar o holder, ela sai do pai antigo antes.
        (vista.parent as? ViewGroup)?.removeView(vista)
        return object : RecyclerView.ViewHolder(vista) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
