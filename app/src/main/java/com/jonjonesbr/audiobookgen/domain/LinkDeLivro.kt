package com.jonjonesbr.audiobookgen.domain

import java.net.URI
import java.net.URLDecoder

/** Por que um link não pôde virar livro. */
enum class MotivoLink { VAZIO, INVALIDO, PAGINA_WEB, FORMATO, GRANDE, REDE }

class LinkException(val motivo: MotivoLink, mensagem: String? = null) : Exception(mensagem ?: motivo.name)

/**
 * Importar um livro de um link direto (Fase P-E). O app só baixa o arquivo para a biblioteca: não faz busca nem confere o
 * conteúdo, e a pessoa declara ter o direito de usá-lo. Aqui ficam as regras puras (testadas); o download está em
 * [com.jonjonesbr.audiobookgen.data.LinkDownloader].
 */
object LinkDeLivro {
    const val MAX_BYTES = 200L * 1024L * 1024L

    /** Valida o endereço e converte links de compartilhamento do Google Drive e do Dropbox em links de download direto. */
    fun normalizar(entrada: String): Result<String> {
        val bruto = entrada.trim()
        if (bruto.isEmpty()) return Result.failure(LinkException(MotivoLink.VAZIO))
        val comEsquema = if (bruto.contains("://")) bruto else "https://$bruto"
        val uri = runCatching { URI(comEsquema) }.getOrNull() ?: return Result.failure(LinkException(MotivoLink.INVALIDO))
        val esquema = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if ((esquema != "https" && esquema != "http") || host.isNullOrBlank() || !host.contains('.')) {
            return Result.failure(LinkException(MotivoLink.INVALIDO))
        }
        return Result.success(converterCompartilhamento(uri, host))
    }

    private fun converterCompartilhamento(uri: URI, host: String): String {
        val url = uri.toString()
        if (host == "drive.google.com") {
            val id = Regex("/file/d/([A-Za-z0-9_-]+)").find(uri.path.orEmpty())?.groupValues?.get(1)
                ?: Regex("(?:^|&)id=([A-Za-z0-9_-]+)").find(uri.rawQuery.orEmpty())?.groupValues?.get(1)
            if (id != null && !uri.path.orEmpty().startsWith("/uc")) return "https://drive.google.com/uc?export=download&id=$id"
        }
        if (host == "www.dropbox.com" || host == "dropbox.com") {
            val semDl = uri.rawQuery.orEmpty().split("&").filter { it.isNotEmpty() && !it.startsWith("dl=") }
            val consulta = (semDl + "dl=1").joinToString("&")
            return "https://www.dropbox.com${uri.rawPath}?$consulta"
        }
        return url
    }

    /** Páginas da web (inclusive a de confirmação do Drive) não são arquivos de livro. */
    fun ehPaginaWeb(contentType: String?): Boolean {
        val tipo = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return tipo == "text/html" || tipo == "application/xhtml+xml"
    }

    /** Nome do arquivo: `Content-Disposition` primeiro, depois o último trecho do caminho do endereço. */
    fun nomeDoArquivo(urlFinal: String, contentDisposition: String?): String? {
        contentDisposition?.let { cabecalho ->
            Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE).find(cabecalho)?.groupValues?.get(1)
                ?.let { return decodificar(it.trim().trim('"')) }
            Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cabecalho)?.groupValues?.get(1)
                ?.let { return it.trim() }
        }
        val caminho = runCatching { URI(urlFinal).path }.getOrNull().orEmpty()
        return caminho.substringAfterLast('/').takeIf { it.isNotBlank() }?.let { decodificar(it) }
    }

    fun extensaoPorTipo(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "application/epub+zip" -> "epub"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        "text/markdown" -> "md"
        "application/msword" -> "doc"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/x-mobipocket-ebook" -> "mobi"
        else -> null
    }

    /** Nome final garantindo uma extensão suportada (do nome, ou do tipo do conteúdo); null se não dá para saber. */
    fun nomeComExtensao(nome: String?, contentType: String?): String? {
        val base = nome?.takeIf { it.isNotBlank() }
        if (base != null && ehFormatoSuportado(base)) return base
        val ext = extensaoPorTipo(contentType) ?: return null
        val semExt = (base ?: "livro").substringBeforeLast('.', base ?: "livro")
        return "$semExt.$ext"
    }

    private fun decodificar(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
}
