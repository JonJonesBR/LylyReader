package com.jonjonesbr.audiobookgen.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/**
 * Miniaturas de capa da busca de livros. Imagens de ~2–10 KB: cache em memória e carga simples (sem
 * biblioteca de imagens, que seria dependência nova). Falha = sem capa, nunca erro para o usuário.
 */
object CapaLoader {
    private const val MAX_BYTES = 300 * 1024
    private const val TIMEOUT_MS = 10_000
    private const val CACHE_KB = 4 * 1024
    private const val LADO_MAX_PX = 192
    private const val USER_AGENT = "LylyReader/1 (leitor de audiobooks; contato via GitHub JonJonesBR/LylyReader)"

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun emCache(url: String): Bitmap? = cache.get(url)

    @Suppress("TooGenericExceptionCaught")
    suspend fun carregar(url: String): Bitmap? = cache.get(url) ?: withContext(Dispatchers.IO) {
        try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val bytes = conn.inputStream.use { entrada ->
                    val saida = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val n = entrada.read(buffer)
                        if (n < 0) break
                        if (saida.size() + n > MAX_BYTES) return@withContext null
                        saida.write(buffer, 0, n)
                    }
                    saida.toByteArray()
                }
                val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, limites)
                var amostra = 1
                while (limites.outWidth / (amostra * 2) >= LADO_MAX_PX && limites.outHeight / (amostra * 2) >= LADO_MAX_PX) {
                    amostra *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra })
                    ?.also { cache.put(url, it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
