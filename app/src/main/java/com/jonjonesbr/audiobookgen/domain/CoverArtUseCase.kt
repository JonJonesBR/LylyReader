package com.jonjonesbr.audiobookgen.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.chaquo.python.Python
import com.chaquo.python.PyObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val COVER_NOME_MAX = 50

@Suppress("TooGenericExceptionCaught")
class CoverArtUseCase(private val context: Context) {

    private val tag = "CoverArtUseCase"

    suspend fun extrairOuGerarCapa(
        caminhoLivro: String?,
        titulo: String,
        autor: String = "Audiobook"
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (!PythonEngineUseCase(context).inicializarMotorSeNecessario()) return@withContext null
            val mod: PyObject = Python.getInstance().getModule("cover_art")
            val resultado = mod.callAttr("obter_capa", caminhoLivro, titulo, autor, null)
            val bytes = resultado?.toJava(ByteArray::class.java) ?: return@withContext null
            if (bytes.isEmpty()) return@withContext null
            val dir = File(context.cacheDir, "covers")
            dir.mkdirs()
            val nomeArquivo = titulo.replace(Regex("[^a-zA-Z0-9_]"), "_").take(COVER_NOME_MAX) + ".jpg"
            val arquivo = File(dir, nomeArquivo)
            arquivo.writeBytes(bytes)
            arquivo
        } catch (e: Exception) {
            Log.w(tag, "Erro ao extrair/gerar capa: ${e.message}")
            CrashLogWriter.log(e, "CoverArtUseCase", "titulo=$titulo")
            null
        }
    }

    fun carregarBitmap(caminhoCapa: File?, ladoMax: Int? = null): Bitmap? {
        if (caminhoCapa?.exists() != true) return null
        return runCatching {
            if (ladoMax == null) {
                BitmapFactory.decodeFile(caminhoCapa.absolutePath)
            } else {
                val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(caminhoCapa.absolutePath, limites)
                val amostra = calcularAmostra(limites.outWidth, limites.outHeight, ladoMax)
                val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
                BitmapFactory.decodeFile(caminhoCapa.absolutePath, opcoes)
            }
        }.onFailure { Log.w(tag, "Erro ao decodificar bitmap") }.getOrNull()
    }
}

/**
 * Fator de reducao (potencia de 2) para decodificar a imagem com o lado maior perto de [ladoMax].
 * Continua dividindo enquanto o lado maior, depois da proxima divisao, ainda fica >= [ladoMax].
 */
internal fun calcularAmostra(largura: Int, altura: Int, ladoMax: Int): Int {
    if (ladoMax <= 0) return 1
    val maior = maxOf(largura, altura)
    var amostra = 1
    while (maior / (amostra * 2) >= ladoMax) amostra *= 2
    return amostra
}
