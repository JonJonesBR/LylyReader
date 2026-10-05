package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import com.jonjonesbr.audiobookgen.util.PacoteVozes
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption

/**
 * Liga as vozes clonadas ([PocketCustomVoices]) ao catálogo de vozes: cada uma vira um pacote local
 * do motor "pocket" — assim aparece no seletor, baixa o que falta (pacote do idioma + codificador)
 * e sintetiza pelos mesmos caminhos das demais vozes Pocket.
 */
object PocketCustomVoiceCatalog {
    private const val PACK_MB = 96

    /** (Re)registra todas as vozes clonadas; chamar ao abrir o app e depois de criar/apagar uma voz. */
    fun registrarTodas(context: Context) {
        val vozes = PocketCustomVoices.list(context)
        VoiceCatalog.pacotesRegistrados()
            .filter { PocketCustomVoices.isCustom(it.id) && vozes.none { v -> v.id == it.id } }
            .forEach { VoiceCatalog.removerPacoteLocal(it.id) }
        vozes.forEach { VoiceCatalog.registrarPacoteLocal(pacote(context, it)) }
    }

    private fun pacote(context: Context, voz: PocketCustomVoices.Voice): PacoteVozes {
        val spec = PocketTtsModelManager.specForVoice(voz.id)
        val faltaPacote = spec == null || !PocketTtsModelManager.isReady(context, spec.id)
        val faltaCodificador = !PocketEncoderManager.isInstalled(context, voz.languageTag)
        return PacoteVozes(
            id = voz.id,
            engine = "pocket",
            nomeExibicao = voz.name,
            tamanhoDownloadMb = (if (faltaPacote) PACK_MB else 0) + (if (faltaCodificador) PocketEncoderManager.DOWNLOAD_MB else 0),
            vozes = listOf(
                VoiceOption(
                    voz.id, voz.name, voz.languageTag, false, "pocket",
                    description = "Voz clonada · Pocket TTS · a referência fica só neste aparelho",
                    generoConhecido = false
                )
            ),
            isPronto = { ctx ->
                PocketEncoderManager.isInstalled(ctx, voz.languageTag) &&
                    spec != null && PocketTtsModelManager.isReady(ctx, spec.id)
            },
            tamanhoOcupadoBytes = { ctx -> PocketCustomVoices.file(ctx, voz.id)?.length() ?: 0L },
            download = { ctx, progresso ->
                if (spec != null && !PocketTtsModelManager.isReady(ctx, spec.id)) {
                    PocketTtsModelManager.download(ctx, spec.id, progresso)
                }
                PocketEncoderManager.download(ctx, voz.languageTag, progresso)
            },
            delete = { ctx ->
                PocketCustomVoices.remove(ctx, voz.id)
                VoiceCatalog.removerPacoteLocal(voz.id)
            }
        )
    }
}
