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

    fun carregarBitmap(caminhoCapa: File?): Bitmap? {
        if (caminhoCapa?.exists() != true) return null
        return runCatching {
            BitmapFactory.decodeFile(caminhoCapa.absolutePath)
        }.onFailure { Log.w(tag, "Erro ao decodificar bitmap") }.getOrNull()
    }
}
