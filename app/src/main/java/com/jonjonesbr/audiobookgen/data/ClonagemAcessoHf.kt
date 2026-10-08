package com.jonjonesbr.audiobookgen.data

import com.jonjonesbr.audiobookgen.domain.AcessoHf
import com.jonjonesbr.audiobookgen.domain.ClonagemAcessoRegras
import com.jonjonesbr.audiobookgen.domain.ResultadoAcessoClonagem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** O Hugging Face recusou o arquivo restrito para este token (sem aceite, token revogado ou sem permissão). */
class SemAcessoHfException(val codigo: Int) : IOException("Sem acesso ao arquivo restrito (HTTP $codigo)")

/**
 * Conversa com o Hugging Face usando o token da conta do próprio usuário. O token só vai para
 * huggingface.co; o redirecionamento para o CDN é seguido à mão, sem o cabeçalho de autorização.
 */
object ClonagemAcessoHf {
    const val PAGINA_KYUTAI = "https://huggingface.co/kyutai/pocket-tts"
    const val PAGINA_CODIFICADORES = "https://huggingface.co/JonJonesBR/lylyreader-pocket-encoders"
    const val PAGINA_TOKENS = "https://huggingface.co/settings/tokens"

    private const val URL_IDENTIDADE = "https://huggingface.co/api/whoami-v2"

    /** Pesos restritos da Kyutai: só lê quem aceitou os termos da fonte com a própria conta. */
    private const val URL_ACEITE_KYUTAI =
        "https://huggingface.co/kyutai/pocket-tts/resolve/main/tts_b6369a24.safetensors"

    /** Um dos codificadores do nosso repositório restrito (o mesmo gate vale para os três idiomas). */
    private const val URL_ACEITE_CODIFICADOR =
        "https://huggingface.co/JonJonesBR/lylyreader-pocket-encoders/resolve/main/pocket-tts-v3.3.0/mimi_encoder-en.onnx"

    private const val TIMEOUT_CONEXAO_MS = 15_000
    private const val TIMEOUT_LEITURA_MS = 20_000
    private const val LIMITE_TEXTO = 16_384

    /** [usuario] só vem preenchido quando o token é válido, mesmo que falte algum aceite. */
    data class Verificacao(val resultado: ResultadoAcessoClonagem, val usuario: String?)

    suspend fun verificar(token: String): Verificacao = withContext(Dispatchers.IO) {
        val (identidade, usuario) = consultarIdentidade(token)
        if (identidade != AcessoHf.AUTORIZADO) {
            val resultado = ClonagemAcessoRegras.decidir(identidade, AcessoHf.AUTORIZADO, AcessoHf.AUTORIZADO)
            return@withContext Verificacao(resultado, null)
        }
        val kyutai = statusDoArquivo(URL_ACEITE_KYUTAI, token)
        val codificador = statusDoArquivo(URL_ACEITE_CODIFICADOR, token)
        Verificacao(ClonagemAcessoRegras.decidir(identidade, kyutai, codificador), usuario)
    }

    /** Endereço temporário (CDN) de um arquivo restrito, pedido com o token do usuário. */
    fun enderecoDoArquivo(url: String, token: String): String {
        val conexao = abrir(url, token, intervalo = false)
        try {
            return when (val codigo = conexao.responseCode) {
                in 300..399 -> conexao.getHeaderField("Location")
                    ?: throw IOException("O Hugging Face não informou o endereço do arquivo.")
                401, 403 -> throw SemAcessoHfException(codigo)
                else -> throw IOException("O Hugging Face respondeu HTTP $codigo ao pedir o codificador.")
            }
        } finally {
            conexao.disconnect()
        }
    }

    /** Lê um arquivo pequeno de texto (o .sha256) com o token, sem gravar em disco. */
    fun lerTextoRestrito(url: String, token: String): String {
        val conexao = abrir(url, token, intervalo = false)
        try {
            return when (val codigo = conexao.responseCode) {
                200, 206 -> lerLimitado(conexao.inputStream)
                in 300..399 -> {
                    val destino = conexao.getHeaderField("Location")
                        ?: throw IOException("O Hugging Face não informou o endereço do arquivo.")
                    val cdn = URL(destino).openConnection() as HttpURLConnection
                    cdn.connectTimeout = TIMEOUT_CONEXAO_MS
                    cdn.readTimeout = TIMEOUT_LEITURA_MS
                    try {
                        lerLimitado(cdn.inputStream)
                    } finally {
                        cdn.disconnect()
                    }
                }
                401, 403 -> throw SemAcessoHfException(codigo)
                else -> throw IOException("O Hugging Face respondeu HTTP $codigo ao pedir o codificador.")
            }
        } finally {
            conexao.disconnect()
        }
    }

    private fun consultarIdentidade(token: String): Pair<AcessoHf, String?> {
        val conexao = abrir(URL_IDENTIDADE, token, intervalo = false)
        return try {
            val status = ClonagemAcessoRegras.classificarStatus(conexao.responseCode)
            if (status != AcessoHf.AUTORIZADO) {
                status to null
            } else {
                val nome = runCatching { JSONObject(lerLimitado(conexao.inputStream)).optString("name") }
                    .getOrDefault("")
                AcessoHf.AUTORIZADO to nome
            }
        } catch (e: IOException) {
            AcessoHf.FALHA to null
        } finally {
            conexao.disconnect()
        }
    }

    private fun statusDoArquivo(url: String, token: String): AcessoHf {
        val conexao = abrir(url, token, intervalo = true)
        return try {
            ClonagemAcessoRegras.classificarStatus(conexao.responseCode)
        } catch (e: IOException) {
            AcessoHf.FALHA
        } finally {
            conexao.disconnect()
        }
    }

    private fun abrir(url: String, token: String, intervalo: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_CONEXAO_MS
            readTimeout = TIMEOUT_LEITURA_MS
            setRequestProperty("User-Agent", "LylyReader-Android")
            setRequestProperty("Authorization", "Bearer $token")
            if (intervalo) setRequestProperty("Range", "bytes=0-0")
        }

    private fun lerLimitado(entrada: InputStream): String =
        entrada.bufferedReader(Charsets.UTF_8).use { leitor ->
            val buffer = CharArray(LIMITE_TEXTO)
            val lidos = leitor.read(buffer)
            if (lidos <= 0) "" else String(buffer, 0, lidos)
        }
}
