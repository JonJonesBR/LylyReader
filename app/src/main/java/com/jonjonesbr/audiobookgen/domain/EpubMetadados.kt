package com.jonjonesbr.audiobookgen.domain

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Título, autor e capa lidos do próprio EPUB. Qualquer campo pode faltar. */
class MetadadosEpub(val titulo: String?, val autor: String?, val capa: ByteArray?)

private const val LIMITE_XML_BYTES = 2L * 1024L * 1024L
private const val LIMITE_CAPA_BYTES = 3L * 1024L * 1024L

private class ItemManifesto(val id: String, val href: String, val tipo: String, val propriedades: List<String>)

/** Nunca lança: qualquer falha (zip corrompido, XML estranho) devolve campos nulos. */
fun lerMetadadosEpub(arquivo: File): MetadadosEpub = try {
    ZipFile(arquivo).use { zip ->
        val container = lerEntrada(zip, "META-INF/container.xml", LIMITE_XML_BYTES)
        val caminhoOpf = container?.let { raiz(it) }
            ?.let { filhosPorNome(it, "rootfile").firstOrNull()?.getAttribute("full-path") }
            ?.takeIf { it.isNotBlank() }
        val opf = caminhoOpf?.let { lerEntrada(zip, it, LIMITE_XML_BYTES) }?.let { raiz(it) }
        if (caminhoOpf == null || opf == null) {
            MetadadosEpub(null, null, null)
        } else {
            val titulo = filhosPorNome(opf, "title").firstOrNull()?.textContent?.trim()?.ifBlank { null }
            val autor = filhosPorNome(opf, "creator").firstOrNull()?.textContent?.trim()?.ifBlank { null }
            val pastaOpf = caminhoOpf.substringBeforeLast('/', "")
            val hrefCapa = achaHrefDaCapa(opf)
            val capa = hrefCapa?.let { lerEntrada(zip, resolverCaminho(pastaOpf, it), LIMITE_CAPA_BYTES) }
            MetadadosEpub(titulo, autor, capa)
        }
    }
} catch (_: Exception) {
    MetadadosEpub(null, null, null)
}

private fun raiz(bytes: ByteArray): Element? = try {
    val fabrica = DocumentBuilderFactory.newInstance().apply {
        // Arquivo de terceiros: sem DTD/entidades externas (o parser do Android não tem o recurso, mas também não as resolve).
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isNamespaceAware = false
    }
    fabrica.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
} catch (_: Exception) {
    null
}

/** Elementos descendentes cujo nome (sem prefixo `dc:`/`opf:`) é [nome], na ordem do documento. */
private fun filhosPorNome(raiz: Element, nome: String): List<Element> {
    val todos = raiz.getElementsByTagName("*")
    return (0 until todos.length).map { todos.item(it) as Element }
        .filter { it.tagName.substringAfter(':') == nome }
}

private fun achaHrefDaCapa(opf: Element): String? {
    val itens = filhosPorNome(opf, "item").map {
        ItemManifesto(
            it.getAttribute("id"), it.getAttribute("href"), it.getAttribute("media-type"),
            it.getAttribute("properties").split(Regex("\\s+")).filter(String::isNotEmpty)
        )
    }
    // (a) propriedade EPUB 3
    itens.firstOrNull { "cover-image" in it.propriedades }?.let { return it.href }
    // (b) <meta name="cover" content="id"> (EPUB 2)
    val idCapa = filhosPorNome(opf, "meta").firstOrNull { it.getAttribute("name") == "cover" }
        ?.getAttribute("content")?.takeIf { it.isNotBlank() }
    if (idCapa != null) itens.firstOrNull { it.id == idCapa }?.let { return it.href }
    // (c) imagem cujo id/href tem "cover"
    return itens.firstOrNull {
        it.tipo.startsWith("image/") && (it.id.contains("cover", true) || it.href.contains("cover", true))
    }?.href
}

/** Junta a pasta do OPF e o href (que pode ter `../` e `%20`), sem barra inicial. */
internal fun resolverCaminho(pasta: String, href: String): String {
    val decodificado = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
    val partes = mutableListOf<String>()
    (if (pasta.isEmpty()) decodificado else "$pasta/$decodificado").split('/').forEach { parte ->
        when (parte) {
            "", "." -> Unit
            ".." -> if (partes.isNotEmpty()) partes.removeAt(partes.lastIndex)
            else -> partes.add(parte)
        }
    }
    return partes.joinToString("/")
}

/** Lê a entrada (exata ou, na falta, sem diferenciar maiúsculas) com limite de tamanho; null se não existe ou passa do limite. */
private fun lerEntrada(zip: ZipFile, nome: String, limite: Long): ByteArray? {
    val entrada = zip.getEntry(nome)
        ?: zip.entries().asSequence().firstOrNull { it.name.equals(nome, ignoreCase = true) }
        ?: return null
    if (entrada.size > limite) return null
    return zip.getInputStream(entrada).use { entradaStream ->
        val saida = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = entradaStream.read(buffer)
            if (n < 0) break
            total += n
            if (total > limite) return null
            saida.write(buffer, 0, n)
        }
        saida.toByteArray()
    }
}
