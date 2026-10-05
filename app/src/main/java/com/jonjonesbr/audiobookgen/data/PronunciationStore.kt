package com.jonjonesbr.audiobookgen.data

import android.content.Context
import com.jonjonesbr.audiobookgen.domain.PronunciationDictionary

/** Acesso ao dicionário de pronúncia salvo (memoriza a última leitura: a síntese consulta a cada trecho). */
object PronunciationStore {
    @Volatile private var rawEmCache: String? = null
    @Volatile private var dicionarioEmCache: PronunciationDictionary = PronunciationDictionary.EMPTY

    fun get(context: Context): PronunciationDictionary {
        val raw = AppPrefs(context).pronunciasJson
        if (raw == rawEmCache) return dicionarioEmCache
        val dicionario = PronunciationDictionary.fromJson(raw)
        dicionarioEmCache = dicionario
        rawEmCache = raw
        return dicionario
    }

    fun save(context: Context, dicionario: PronunciationDictionary) {
        AppPrefs(context).pronunciasJson = dicionario.toJson()
    }

    /** JSON cru para o módulo Python (conversão de audiobook). */
    fun rawJson(context: Context): String = AppPrefs(context).pronunciasJson
}
