package com.jonjonesbr.audiobookgen.data

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File

/** SharedPreferences em memória para testes JVM (sem Robolectric): grava na hora, em apply() e commit(). */
class PrefsEmMemoria : SharedPreferences {
    private val valores = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = valores.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = valores[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (valores[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = valores[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = valores[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = valores[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = valores[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in valores
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val mudancas = mutableMapOf<String, Any?>()
        private val removidas = mutableSetOf<String>()
        private var limpar = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            mudancas[key] = value
            return this
        }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            mudancas[key] = values?.toSet()
            return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            mudancas[key] = value
            return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            mudancas[key] = value
            return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            mudancas[key] = value
            return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            mudancas[key] = value
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            removidas += key
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            limpar = true
            return this
        }
        override fun commit(): Boolean {
            if (limpar) valores.clear()
            removidas.forEach { valores.remove(it) }
            mudancas.forEach { (k, v) -> if (v == null) valores.remove(k) else valores[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }

    companion object {
        /** Context falso: SharedPreferences em memória por nome e filesDir/cacheDir dentro de [raiz]. */
        fun contexto(raiz: File): Context {
            val porNome = mutableMapOf<String, PrefsEmMemoria>()
            val files = File(raiz, "files").apply { mkdirs() }
            val cache = File(raiz, "cache").apply { mkdirs() }
            val ctx = mockk<Context>()
            every { ctx.getSharedPreferences(any(), any()) } answers { porNome.getOrPut(firstArg()) { PrefsEmMemoria() } }
            every { ctx.filesDir } returns files
            every { ctx.cacheDir } returns cache
            every { ctx.applicationContext } returns ctx
            return ctx
        }
    }
}
