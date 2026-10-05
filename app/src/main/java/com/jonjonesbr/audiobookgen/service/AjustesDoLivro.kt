package com.jonjonesbr.audiobookgen.service

import android.content.Context
import com.jonjonesbr.audiobookgen.data.AppPrefs
import com.jonjonesbr.audiobookgen.domain.AjustesDaConversao
import com.jonjonesbr.audiobookgen.domain.AjustesDaConversaoRegras
import com.jonjonesbr.audiobookgen.util.VoiceCatalog

/** Ajustes da conversão de um livro: escolha na hora > perfil do livro (voz, velocidade, tom) > preferências globais. */
object AjustesDoLivro {
    fun resolver(
        context: Context,
        caminhoDoLivro: String?,
        escolhaVoz: String? = null,
        escolhaMotor: String? = null,
        escolhaRitmo: Int? = null
    ): AjustesDaConversao {
        val appPrefs = AppPrefs(context)
        val perfilVoz = caminhoDoLivro?.let { GuidedBookPrefsStore.load(context, it) }
        val perfilAudio = caminhoDoLivro?.let { GuidedBookAudioStore.load(context, it) }
        return AjustesDaConversaoRegras.montar(
            escolhaVoz = escolhaVoz,
            escolhaMotor = escolhaMotor,
            escolhaRitmo = escolhaRitmo,
            livroVoz = perfilVoz?.voz,
            livroMotor = perfilVoz?.motor,
            livroVelocidade = perfilVoz?.speedMult,
            perfilAudio = perfilAudio,
            globalVoz = appPrefs.vozSelecionada,
            globalMotor = appPrefs.motorTts,
            globalRitmo = appPrefs.ritmo
        ) { motor -> VoiceCatalog.defaultFor(motor).id }
    }
}
