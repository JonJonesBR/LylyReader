package com.jonjonesbr.audiobookgen.ui

import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.service.GuidedBookPrefsStore
import com.jonjonesbr.audiobookgen.util.VELOCIDADES_GUIADAS
import com.jonjonesbr.audiobookgen.util.expandirEmPaisagem
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import com.jonjonesbr.audiobookgen.util.marcarComoBotao
import com.jonjonesbr.audiobookgen.util.rotuloVelocidade
import com.jonjonesbr.audiobookgen.util.velocidadeMaisProxima
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip

/**
 * "Configurar este livro" — permite ver/mudar a voz e a velocidade da leitura guiada ANTES de
 * começar a ouvir. O sistema por-livro (T4.3, [GuidedBookPrefsStore]) já existia, mas só
 * gravava depois que o usuário mexia no seletor DURANTE a leitura guiada ativa; este diálogo
 * lê/escreve o mesmo store diretamente, sem precisar de uma sessão de
 * [com.jonjonesbr.audiobookgen.service.GuidedPlayerManager] ativa.
 */
object ConfigLivroDialog {

    /**
     * [aoMudarPerfil]: chamado depois de cada gravação, para a leitura guiada aberta aplicar a escolha já.
     * [onClonarVoz] recebe a escolha deste painel: a voz clonada a passa para ela, como a lista faz.
     */
    fun mostrar(
        activity: AppCompatActivity,
        caminhoArquivo: String,
        onClonarVoz: ((aoEscolherVoz: (VoiceOption) -> Unit) -> Unit) = { _ -> },
        aoMudarPerfil: () -> Unit = {}
    ) {
        val appPrefs = AppPrefs(activity)
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_config_livro, null)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(view)
        dialog.expandirEmPaisagem()

        val tvVoz = view.findViewById<TextView>(R.id.tvConfigVozAtual)
        // Um chip por preset, com o texto no locale do aparelho ("0,75×" em pt). Sem deslizante: os chips bastam.
        val idsVelocidade = listOf(
            R.id.chipConfigVel075, R.id.chipConfigVel100, R.id.chipConfigVel125,
            R.id.chipConfigVel150, R.id.chipConfigVel175, R.id.chipConfigVel200
        )
        val chipsVelocidade = idsVelocidade.zip(VELOCIDADES_GUIADAS.toList()) { id, valor ->
            view.findViewById<Chip>(id).apply { text = rotuloVelocidade(valor) } to valor
        }

        fun atualizarUi() {
            val efetivo = resolverEfetivo(activity, caminhoArquivo, appPrefs)
            val voz = VoiceCatalog.findAny(efetivo.voz)
                ?: VoiceCatalog.find(efetivo.motor, efetivo.voz)
                ?: VoiceCatalog.defaultFor(efetivo.motor)
            tvVoz.text = voz.displayLabel(activity)
            // Velocidade salva fora dos chips (ex.: 1.1 vindo do ajuste na leitura): marca o chip mais próximo.
            val maisProxima = velocidadeMaisProxima(efetivo.speedMult)
            chipsVelocidade.forEach { (chip, valor) -> chip.isChecked = valor == maisProxima }
        }

        fun salvarVelocidade(valor: Float) {
            val atual = resolverEfetivo(activity, caminhoArquivo, appPrefs)
            GuidedBookPrefsStore.save(activity, caminhoArquivo, atual.voz, atual.motor, valor)
            aoMudarPerfil()
            atualizarUi()
        }

        // Escolha de voz deste livro (na lista ou voz recém-clonada): grava, avisa a leitura e atualiza o painel.
        val aoEscolherVoz: (VoiceOption) -> Unit = { voz ->
            gravarVozDoLivro(activity, caminhoArquivo, appPrefs, voz)
            aoMudarPerfil()
            atualizarUi()
        }

        view.findViewById<View>(R.id.itemConfigVoz).marcarComoBotao()
        view.findViewById<View>(R.id.itemConfigVoz).setOnClickListener {
            abrirSeletorDeVoz(activity, caminhoArquivo, appPrefs, aoEscolherVoz, onClonarVoz)
        }
        chipsVelocidade.forEach { (chip, valor) -> chip.setOnClickListener { salvarVelocidade(valor) } }

        atualizarUi()
        dialog.show()
    }

    private fun abrirSeletorDeVoz(
        activity: AppCompatActivity,
        caminhoArquivo: String,
        appPrefs: AppPrefs,
        aoEscolherVoz: (VoiceOption) -> Unit,
        onClonarVoz: ((aoEscolherVoz: (VoiceOption) -> Unit) -> Unit)
    ) {
        val efetivo = resolverEfetivo(activity, caminhoArquivo, appPrefs)
        VoiceSelectionDelegate(
            activity = activity,
            onVoiceSelected = aoEscolherVoz,
            onVoicePreview = { _, onFinished -> onFinished() },
            obterVozAtualId = { efetivo.voz },
            restorable = false,
            onCloneVoiceRequested = { onClonarVoz(aoEscolherVoz) }
        ).abrirSeletorVozes()
    }

    /**
     * Grava [voz] como voz deste livro, mantendo a velocidade que ele já tem.
     * Mesma rotina para a escolha na lista e para a voz clonada.
     */
    private fun gravarVozDoLivro(
        activity: AppCompatActivity,
        caminhoArquivo: String,
        appPrefs: AppPrefs,
        voz: VoiceOption
    ) {
        val atual = resolverEfetivo(activity, caminhoArquivo, appPrefs)
        GuidedBookPrefsStore.save(activity, caminhoArquivo, voz.id, voz.engine, atual.speedMult)
    }

    /** Voz/motor/velocidade efetivos deste livro: override salvo, ou fallback pras prefs globais. */
    private fun resolverEfetivo(
        activity: AppCompatActivity,
        caminhoArquivo: String,
        appPrefs: AppPrefs
    ): GuidedBookPrefsStore.BookPrefs {
        GuidedBookPrefsStore.load(activity, caminhoArquivo)?.let { return it }
        val motor = appPrefs.motorTts
        val voz = appPrefs.vozSelecionada ?: VoiceCatalog.defaultFor(motor).id
        return GuidedBookPrefsStore.BookPrefs(voz, motor, appPrefs.guidedSpeedMult)
    }
}
