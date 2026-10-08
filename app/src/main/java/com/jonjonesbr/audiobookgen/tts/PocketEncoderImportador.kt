package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jonjonesbr.audiobookgen.domain.ArquivoOficialPocket
import com.jonjonesbr.audiobookgen.domain.PocketEncoderOficial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Monta o codificador de voz do Pocket a partir do arquivo oficial da Kyutai que o usuário baixou
 * com a própria conta. O app traz só a "planta" do modelo (sem pesos); os pesos vêm do arquivo do
 * usuário. Nada é enviado à internet e nenhum login é usado aqui.
 */
object PocketEncoderImportador {
    private const val PLANTA = "pocket/mimi_encoder_planta.onnx"
    private const val MAPA = "pocket/mimi_encoder_mapa.json"

    sealed class Resultado {
        data class Instalado(val arquivo: ArquivoOficialPocket) : Resultado()
        object ArquivoErrado : Resultado()
        data class Falha(val motivo: String) : Resultado()
    }

    suspend fun importar(context: Context, uri: Uri, progresso: (Float) -> Unit): Resultado =
        withContext(Dispatchers.IO) {
            val tamanho = tamanhoDe(context, uri)
            if (!PocketEncoderOficial.tamanhoPlausivel(tamanho)) return@withContext Resultado.ArquivoErrado
            val mapa = PocketEncoderOficial.lerMapa(
                context.assets.open(MAPA).bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
            val lidos = try {
                val entrada = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Resultado.Falha("arquivo indisponível")
                entrada.use { PocketEncoderOficial.lerPesos(it, mapa, tamanho ?: 0L, progresso = progresso) }
            } catch (e: PocketEncoderOficial.ArquivoInvalidoException) {
                return@withContext Resultado.ArquivoErrado
            }
            val oficial = PocketEncoderOficial.porSha256(lidos.sha256) ?: return@withContext Resultado.ArquivoErrado
            ensureActive()

            val destino = PocketEncoderManager.installedFile(context, oficial.idioma)
            destino.parentFile?.mkdirs()
            val parcial = File(destino.parentFile, "${destino.name}.part")
            parcial.outputStream().buffered().use { saida ->
                context.assets.open(PLANTA).use { planta -> PocketEncoderOficial.escreverOnnx(planta, mapa, lidos.pesos, saida) }
            }
            destino.delete()
            if (!parcial.renameTo(destino)) {
                parcial.copyTo(destino, overwrite = true)
                parcial.delete()
            }
            PocketEncoderManager.removerCopiasNosPacotes(context)
            progresso(1f)
            Resultado.Instalado(oficial)
        }

    private fun tamanhoDe(context: Context, uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
}
