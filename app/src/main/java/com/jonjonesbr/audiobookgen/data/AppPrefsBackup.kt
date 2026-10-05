package com.jonjonesbr.audiobookgen.data

import org.json.JSONObject

/**
 * Serializa/aplica as prefs do app ([AppPrefs]) em JSON. Fonte única de verdade do conjunto
 * de campos exportados — usada tanto pela exportação de configurações isolada quanto pelo
 * backup completo (Fase 5), pra não divergir entre os dois pontos de uso.
 */
object AppPrefsBackup {
    private const val DEFAULT_READER_FONTE = 16f
    private const val DEFAULT_READER_ESPACAMENTO = 1.5f

    fun paraJson(appPrefs: AppPrefs): JSONObject {
        val readerPrefs = appPrefs.getPrefs()
        return JSONObject().apply {
            put("ritmo", appPrefs.ritmo)
            put("motor_tts", appPrefs.motorTts)
            put("voz_selecionada", appPrefs.vozSelecionada ?: "")
            put("pausa_ms", appPrefs.pausaMs)
            put("bitrate", appPrefs.bitrate)
            put("supertonic_steps", appPrefs.supertonicSteps)
            put("pocket_passos", appPrefs.pocketPassos)
            put("teto_cache_audio_mb", appPrefs.tetoCacheAudioMb)
            put("timbre_tom_meios_tons", appPrefs.tomVozMeiosTons)
            put("timbre_agudos_db", appPrefs.suavizarAgudosDb)
            put("timbre_tom_falas", appPrefs.tomNasFalasMeiosTons)
            put("gemini_modelo_analise", appPrefs.geminiModeloAnalise)
            put("ia_provedor", appPrefs.iaProvedor)
            put("ia_personalizada_url", appPrefs.iaPersonalizadaUrl)
            put("ia_personalizada_modelo", appPrefs.iaPersonalizadaModelo)
            put("pronuncias_json", appPrefs.pronunciasJson)
            put("pasta_destino", appPrefs.pastaDestino ?: "")
            put("player_engine", appPrefs.playerEngine)
            put("guided_speed_mult", appPrefs.guidedSpeedMult.toDouble())
            put("onboarding_done", appPrefs.onboardingDone)
            put("voice_download_onboarding_done", appPrefs.voiceDownloadOnboardingDone)
            put("aviso_voz_generica_dispensado", appPrefs.avisoVozGenericaDispensado)
            put("blacklist_words", appPrefs.blacklistWordsJson)
            put("reader_fonte", readerPrefs.getFloat("reader_fonte", DEFAULT_READER_FONTE).toDouble())
            put(
                "reader_espacamento",
                readerPrefs.getFloat("reader_espacamento", DEFAULT_READER_ESPACAMENTO).toDouble()
            )
            put("reader_tema", readerPrefs.getInt("reader_tema", 0))
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
        if (json.has("bitrate")) appPrefs.bitrate = json.optInt("bitrate", appPrefs.bitrate)
        if (json.has("supertonic_steps")) {
            appPrefs.supertonicSteps = json.optInt("supertonic_steps", appPrefs.supertonicSteps)
        }
        if (json.has("teto_cache_audio_mb")) {
            appPrefs.tetoCacheAudioMb = json.optInt("teto_cache_audio_mb", appPrefs.tetoCacheAudioMb)
        }
        if (json.has("timbre_tom_meios_tons")) {
            appPrefs.tomVozMeiosTons = json.optInt("timbre_tom_meios_tons", appPrefs.tomVozMeiosTons)
        }
        if (json.has("gemini_modelo_analise")) {
            appPrefs.geminiModeloAnalise = json.optString("gemini_modelo_analise", "")
            appPrefs.iaProvedor = json.optString("ia_provedor", "gemini")
            appPrefs.iaPersonalizadaUrl = json.optString("ia_personalizada_url", "")
            appPrefs.iaPersonalizadaModelo = json.optString("ia_personalizada_modelo", "")
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
        if (json.has("pasta_destino")) {
            val pasta = json.optString("pasta_destino", "")
            appPrefs.pastaDestino = pasta.ifEmpty { null }
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
    }

    private fun aplicarReaderPrefs(appPrefs: AppPrefs, json: JSONObject) {
        if (!json.has("reader_fonte") && !json.has("reader_espacamento") && !json.has("reader_tema")) return
        appPrefs.getPrefs().edit().apply {
            if (json.has("reader_fonte")) {
                val v = json.optDouble("reader_fonte", DEFAULT_READER_FONTE.toDouble()).toFloat()
                putFloat("reader_fonte", v)
            }
            if (json.has("reader_espacamento")) {
                val v = json.optDouble("reader_espacamento", DEFAULT_READER_ESPACAMENTO.toDouble()).toFloat()
                putFloat("reader_espacamento", v)
            }
            if (json.has("reader_tema")) {
                putInt("reader_tema", json.optInt("reader_tema", 0))
            }
        }.apply()
    }
}
