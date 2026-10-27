package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePreferences {
    enum class SaveResult {
        SECURE,
        LOCAL_FALLBACK,
        FAILED
    }

    private const val TAG = "SecurePreferences"
    private const val LEGACY_PREFS = "audiobookgen_prefs"
    private const val SECURE_PREFS = "audiobookgen_secure_prefs"
    private const val GEMINI_KEYS = "gemini_keys"
    private const val ELEVENLABS_KEY = "elevenlabs_key"
    private const val IA_PERSONALIZADA_KEY = "ia_personalizada_key"
    private const val HF_TOKEN_CLONAGEM = "hf_token_clonagem"
    /** Nas prefs comuns: o cofre teve de ser recriado e as chaves se perderam; a Biblioteca avisa uma vez. */
    private const val COFRE_RECRIADO = "cofre_chaves_recriado"

    private fun createSecurePrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun securePrefs(context: Context): SharedPreferences? {
        return runCatching { createSecurePrefs(context) }
            .recoverCatching {
                // Restored encrypted files can no longer be opened when their Keystore key
                // was not restored with them. Recreate the file once before falling back.
                Log.w(TAG, "Secure preferences unavailable; recreating encrypted storage.", it)
                recriarCofre(context)
                createSecurePrefs(context)
            }
            .onFailure { Log.w(TAG, "Secure preferences remain unavailable.", it) }
            .getOrNull()
    }

    /** Apaga o cofre que não abre mais; se ele existia (havia chaves), marca o aviso para o usuário digitá-las de novo. */
    private fun recriarCofre(context: Context) {
        val arquivo = java.io.File(java.io.File(context.applicationInfo.dataDir, "shared_prefs"), "$SECURE_PREFS.xml")
        if (arquivo.exists()) localPrefs(context).edit().putBoolean(COFRE_RECRIADO, true).apply()
        context.deleteSharedPreferences(SECURE_PREFS)
    }

    /**
     * True uma única vez depois que o cofre de chaves foi recriado (falha do Android ao descriptografar): as chaves de API
     * salvas se perderam e precisam ser digitadas de novo. Antes isso acontecia em silêncio.
     */
    fun consumirAvisoCofreRecriado(context: Context): Boolean {
        val prefs = localPrefs(context)
        if (!prefs.getBoolean(COFRE_RECRIADO, false)) return false
        prefs.edit().remove(COFRE_RECRIADO).apply()
        return true
    }

    private fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    fun getGeminiKeys(context: Context): String = getSecureValue(context, GEMINI_KEYS)

    fun setGeminiKeys(context: Context, value: String): SaveResult = setSecureValue(context, GEMINI_KEYS, value)

    fun getIaPersonalizadaChave(context: Context): String = getSecureValue(context, IA_PERSONALIZADA_KEY)

    fun setIaPersonalizadaChave(context: Context, value: String): SaveResult = setSecureValue(context, IA_PERSONALIZADA_KEY, value)

    fun getElevenLabsKey(context: Context): String = getSecureValue(context, ELEVENLABS_KEY)

    fun setElevenLabsKey(context: Context, value: String): SaveResult = setSecureValue(context, ELEVENLABS_KEY, value)

    /**
     * Apaga o token do Hugging Face que versões de teste chegaram a guardar para a clonagem.
     * A clonagem não usa mais login nem token: o usuário baixa o arquivo oficial no navegador.
     */
    fun apagarTokenHfLegado(context: Context) {
        setSecureValue(context, HF_TOKEN_CLONAGEM, "")
    }

    private fun getSecureValue(context: Context, prefKey: String): String {
        val secure = securePrefs(context)
        val secureRead = runCatching { secure?.getString(prefKey, null) }
        val secureValue = secureRead.getOrElse {
            Log.w(TAG, "Could not decrypt $prefKey; recreating encrypted storage.", it)
            recriarCofre(context)
            runCatching { securePrefs(context)?.getString(prefKey, null) }
                .onFailure { error -> Log.w(TAG, "Could not read recreated secure preferences.", error) }
                .getOrNull()
        }
        if (!secureValue.isNullOrBlank()) return secureValue

        val local = localPrefs(context)
        val localValue = local.getString(prefKey, "") ?: ""
        if (localValue.isNotBlank() && secure != null) {
            val migrated = writeAndVerify(secure, prefKey, localValue)
            if (migrated) local.edit().remove(prefKey).commit()
        }
        return localValue
    }

    private fun setSecureValue(context: Context, prefKey: String, value: String): SaveResult {
        val secure = securePrefs(context)
        if (secure != null && writeAndVerify(secure, prefKey, value)) {
            localPrefs(context).edit().remove(prefKey).commit()
            return SaveResult.SECURE
        }

        val local = localPrefs(context)
        val savedLocally = if (value.isBlank()) {
            local.edit().remove(prefKey).commit()
        } else {
            local.edit().putString(prefKey, value).commit() &&
                local.getString(prefKey, null) == value
        }
        return if (savedLocally) SaveResult.LOCAL_FALLBACK else SaveResult.FAILED
    }

    private fun writeAndVerify(prefs: SharedPreferences, prefKey: String, value: String): Boolean =
        runCatching {
            val committed = if (value.isBlank()) {
                prefs.edit().remove(prefKey).commit()
            } else {
                prefs.edit().putString(prefKey, value).commit()
            }
            committed && (value.isBlank() || prefs.getString(prefKey, null) == value)
        }.onFailure {
            Log.w(TAG, "Could not update $prefKey in secure preferences.", it)
        }.getOrDefault(false)

}
