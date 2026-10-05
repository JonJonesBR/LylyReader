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
                context.deleteSharedPreferences(SECURE_PREFS)
                createSecurePrefs(context)
            }
            .onFailure { Log.w(TAG, "Secure preferences remain unavailable.", it) }
            .getOrNull()
    }

    private fun localPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    fun getGeminiKeys(context: Context): String = getSecureValue(context, GEMINI_KEYS)

    fun setGeminiKeys(context: Context, value: String): SaveResult = setSecureValue(context, GEMINI_KEYS, value)

    fun getIaPersonalizadaChave(context: Context): String = getSecureValue(context, IA_PERSONALIZADA_KEY)

    fun setIaPersonalizadaChave(context: Context, value: String): SaveResult = setSecureValue(context, IA_PERSONALIZADA_KEY, value)

    fun getElevenLabsKey(context: Context): String = getSecureValue(context, ELEVENLABS_KEY)

    fun setElevenLabsKey(context: Context, value: String): SaveResult = setSecureValue(context, ELEVENLABS_KEY, value)

    private fun getSecureValue(context: Context, prefKey: String): String {
        val secure = securePrefs(context)
        val secureRead = runCatching { secure?.getString(prefKey, null) }
        val secureValue = secureRead.getOrElse {
            Log.w(TAG, "Could not decrypt $prefKey; recreating encrypted storage.", it)
            context.deleteSharedPreferences(SECURE_PREFS)
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
