package com.jonjonesbr.audiobookgen.data

import org.json.JSONObject

/**
 * Serializa/aplica as prefs do app ([AppPrefs]) em JSON. Fonte única de verdade do conjunto
 * de campos exportados — usada tanto pela exportação de configurações isolada quanto pelo
 * backup completo (Fase 5), pra não divergir entre os dois pontos de uso.
 */
object AppPrefsBackup {
    fun paraJson(appPrefs: AppPrefs): JSONObject {
        return JSONObject().apply {
            put("ritmo", appPrefs.ritmo)
            put("motor_tts", appPrefs.motorTts)
            put("voz_selecionada", appPrefs.vozSelecionada ?: "")
            put("pausa_ms", appPrefs.pausaMs)
            put("supertonic_steps", appPrefs.supertonicSteps)
            put("pocket_passos", appPrefs.pocketPassos)
            put("pocket_expressividade", appPrefs.pocketExpressividade)
            put("entonacao_continua", appPrefs.entonacaoContinua)
            put("acabamento_audio", appPrefs.acabamentoAudio)
            put("dialogos_vivos", appPrefs.dialogosVivos)
            put("teto_cache_audio_mb", appPrefs.tetoCacheAudioMb)
            put("timbre_tom_meios_tons", appPrefs.tomVozMeiosTons)
            put("timbre_agudos_db", appPrefs.suavizarAgudosDb)
            put("timbre_tom_falas", appPrefs.tomNasFalasMeiosTons)
            put("gemini_modelo_analise", appPrefs.geminiModeloAnalise)
            put("ia_provedor", appPrefs.iaProvedor)
            put("ia_personalizada_url", appPrefs.iaPersonalizadaUrl)
            put("ia_personalizada_modelo", appPrefs.iaPersonalizadaModelo)
            put("pronuncias_json", appPrefs.pronunciasJson)
            // pasta_destino fica de fora: é uma permissão de pasta (SAF) que só vale neste aparelho e nesta instalação.
            put("player_engine", appPrefs.playerEngine)
            put("guided_speed_mult", appPrefs.guidedSpeedMult.toDouble())
            put("onboarding_done", appPrefs.onboardingDone)
            put("voice_download_onboarding_done", appPrefs.voiceDownloadOnboardingDone)
            put("aviso_voz_generica_dispensado", appPrefs.avisoVozGenericaDispensado)
            put("blacklist_words", appPrefs.blacklistWordsJson)
            put("reader_fonte", appPrefs.readerFonte.toDouble())
            put("reader_espacamento", appPrefs.readerEspacamento.toDouble())
            put("reader_tema", appPrefs.readerTema)
        }
    }

    fun aplicar(appPrefs: AppPrefs, json: JSONObject) {
        if (json.has("ritmo")) appPrefs.ritmo = json.optInt("ritmo", appPrefs.ritmo)
        if (json.has("motor_tts")) appPrefs.motorTts = json.optString("motor_tts", appPrefs.motorTts)
        if (json.has("voz_selecionada")) {
            val voz = json.optString("voz_selecionada", "")
            appPrefs.vozSelecionada = voz.ifEmpty { null }
        }
        if (json.has("pausa_ms")) appPrefs.pausaMs = json.optInt("pausa_ms", appPrefs.pausaMs)
        if (json.has("supertonic_steps")) {
            appPrefs.supertonicSteps = json.optInt("supertonic_steps", appPrefs.supertonicSteps)
        }
        if (json.has("teto_cache_audio_mb")) {
            appPrefs.tetoCacheAudioMb = json.optInt("teto_cache_audio_mb", appPrefs.tetoCacheAudioMb)
        }
        if (json.has("timbre_tom_meios_tons")) {
            appPrefs.tomVozMeiosTons = json.optInt("timbre_tom_meios_tons", appPrefs.tomVozMeiosTons)
        }
        // Cada ajuste de IA só é aplicado se o backup o tiver: um backup antigo, sem algum deles, não zera o atual.
        if (json.has("gemini_modelo_analise")) appPrefs.geminiModeloAnalise = json.optString("gemini_modelo_analise", "")
        if (json.has("ia_provedor")) appPrefs.iaProvedor = json.optString("ia_provedor", appPrefs.iaProvedor)
        if (json.has("ia_personalizada_url")) {
            appPrefs.iaPersonalizadaUrl = json.optString("ia_personalizada_url", appPrefs.iaPersonalizadaUrl)
        }
        if (json.has("ia_personalizada_modelo")) {
            appPrefs.iaPersonalizadaModelo = json.optString("ia_personalizada_modelo", appPrefs.iaPersonalizadaModelo)
        }
        if (json.has("timbre_agudos_db")) {
            appPrefs.suavizarAgudosDb = json.optInt("timbre_agudos_db", appPrefs.suavizarAgudosDb)
        }
        if (json.has("timbre_tom_falas")) {
            appPrefs.tomNasFalasMeiosTons = json.optInt("timbre_tom_falas", appPrefs.tomNasFalasMeiosTons)
        }
        if (json.has("pocket_passos")) {
            appPrefs.pocketPassos = json.optInt("pocket_passos", appPrefs.pocketPassos)
        }
        if (json.has("pronuncias_json")) {
            appPrefs.pronunciasJson = json.optString("pronuncias_json", appPrefs.pronunciasJson)
        }
        if (json.has("player_engine")) {
            appPrefs.playerEngine = json.optString("player_engine", appPrefs.playerEngine)
        }
        if (json.has("guided_speed_mult")) {
            appPrefs.guidedSpeedMult = json.optDouble(
                "guided_speed_mult",
                appPrefs.guidedSpeedMult.toDouble()
            ).toFloat()
        }
        if (json.has("onboarding_done")) {
            appPrefs.onboardingDone = json.optBoolean("onboarding_done", appPrefs.onboardingDone)
        }
        if (json.has("voice_download_onboarding_done")) {
            appPrefs.voiceDownloadOnboardingDone = json.optBoolean(
                "voice_download_onboarding_done",
                appPrefs.voiceDownloadOnboardingDone
            )
        }
        if (json.has("aviso_voz_generica_dispensado")) {
            appPrefs.avisoVozGenericaDispensado = json.optBoolean(
                "aviso_voz_generica_dispensado",
                appPrefs.avisoVozGenericaDispensado
            )
        }
        if (json.has("blacklist_words")) {
            appPrefs.blacklistWordsJson = json.optString("blacklist_words", appPrefs.blacklistWordsJson)
        }
        aplicarReaderPrefs(appPrefs, json)
        aplicarNaturalidade(appPrefs, json)
    }

    /** Expressividade do Pocket e as opções de "Naturalidade da voz". */
    private fun aplicarNaturalidade(appPrefs: AppPrefs, json: JSONObject) {
        if (json.has("pocket_expressividade")) {
            appPrefs.pocketExpressividade = json.optInt("pocket_expressividade", appPrefs.pocketExpressividade)
        }
        appPrefs.entonacaoContinua = json.optBoolean("entonacao_continua", appPrefs.entonacaoContinua)
        appPrefs.acabamentoAudio = json.optBoolean("acabamento_audio", appPrefs.acabamentoAudio)
        appPrefs.dialogosVivos = json.optBoolean("dialogos_vivos", appPrefs.dialogosVivos)
    }

    private fun aplicarReaderPrefs(appPrefs: AppPrefs, json: JSONObject) {
        if (json.has("reader_fonte")) {
            appPrefs.readerFonte = json.optDouble("reader_fonte", AppPrefs.READER_FONTE_PADRAO.toDouble()).toFloat()
        }
        if (json.has("reader_espacamento")) {
            appPrefs.readerEspacamento =
                json.optDouble("reader_espacamento", AppPrefs.READER_ESPACAMENTO_PADRAO.toDouble()).toFloat()
        }
        if (json.has("reader_tema")) {
            appPrefs.readerTema = json.optInt("reader_tema", 0)
        }
    }
}
