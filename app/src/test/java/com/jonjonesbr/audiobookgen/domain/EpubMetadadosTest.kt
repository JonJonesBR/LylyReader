package com.jonjonesbr.audiobookgen.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubMetadadosTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val imagem = byteArrayOf(1, 2, 3, 4, 5)

    private fun container(opf: String) =
        """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
<rootfiles><rootfile full-path="$opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun opf(manifesto: String, extraMeta: String = "") =
        """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Dom Casmurro</dc:title><dc:creator>Machado de Assis</dc:creator>$extraMeta</metadata>
<manifest>$manifesto</manifest></package>"""

    private fun epub(entradas: Map<String, ByteArray>): File {
        val f = pasta.newFile()
        ZipOutputStream(f.outputStream()).use { zip ->
            entradas.forEach { (nome, bytes) ->
                zip.putNextEntry(ZipEntry(nome))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `capa por propriedade cover-image do epub 3`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container("content.opf").toByteArray(),
                "content.opf" to opf("""<item id="x" href="img/capa.jpg" media-type="image/jpeg" properties="cover-image"/>""").toByteArray(),
                "img/capa.jpg" to imagem
            )
        )
        val m = lerMetadadosEpub(f)
        assertEquals("Dom Casmurro", m.titulo)
        assertEquals("Machado de Assis", m.autor)
        assertArrayEquals(imagem, m.capa)
    }

    @Test
    fun `capa por meta cover do epub 2`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container("content.opf").toByteArray(),
                "content.opf" to opf(
                    """<item id="img1" href="a.png" media-type="image/png"/><item id="outra" href="b.png" media-type="image/png"/>""",
                    """<meta name="cover" content="img1"/>"""
                ).toByteArray(),
                "a.png" to imagem
            )
        )
        assertArrayEquals(imagem, lerMetadadosEpub(f).capa)
    }

    @Test
    fun `capa por imagem com cover no nome`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container("content.opf").toByteArray(),
                "content.opf" to opf("""<item id="i" href="images/cover.jpeg" media-type="image/jpeg"/>""").toByteArray(),
                "images/cover.jpeg" to imagem
            )
        )
        assertArrayEquals(imagem, lerMetadadosEpub(f).capa)
    }

    @Test
    fun `opf em subpasta resolve href relativo e percent encoding`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
                "OEBPS/content.opf" to opf("""<item id="x" href="../Imagens/minha%20capa.jpg" media-type="image/jpeg" properties="cover-image"/>""").toByteArray(),
                "Imagens/minha capa.jpg" to imagem
            )
        )
        assertArrayEquals(imagem, lerMetadadosEpub(f).capa)
    }

    @Test
    fun `sem capa devolve titulo e autor com capa nula`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container("content.opf").toByteArray(),
                "content.opf" to opf("""<item id="c1" href="cap1.xhtml" media-type="application/xhtml+xml"/>""").toByteArray()
            )
        )
        val m = lerMetadadosEpub(f)
        assertEquals("Dom Casmurro", m.titulo)
        assertNull(m.capa)
    }

    @Test
    fun `item de capa apontando para arquivo ausente nao quebra`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container("content.opf").toByteArray(),
                "content.opf" to opf("""<item id="x" href="nao-existe.jpg" media-type="image/jpeg" properties="cover-image"/>""").toByteArray()
            )
        )
        val m = lerMetadadosEpub(f)
        assertEquals("Dom Casmurro", m.titulo)
        assertNull(m.capa)
    }

    @Test
    fun `zip corrompido ou nao zip devolve tudo nulo`() {
        val f = pasta.newFile().apply { writeText("isto não é um zip") }
        val m = lerMetadadosEpub(f)
        assertNull(m.titulo)
        assertNull(m.autor)
        assertNull(m.capa)
    }

    @Test
    fun `epub sem container devolve tudo nulo`() {
        val m = lerMetadadosEpub(epub(mapOf("qualquer.txt" to "oi".toByteArray())))
        assertNull(m.titulo)
        assertNull(m.capa)
    }

    @Test
    fun `resolver caminho junta pasta, sobe com pontos e decodifica`() {
        assertEquals("OEBPS/img/a.jpg", resolverCaminho("OEBPS", "img/a.jpg"))
        assertEquals("Imagens/a b.jpg", resolverCaminho("OEBPS", "../Imagens/a%20b.jpg"))
        assertEquals("a.jpg", resolverCaminho("", "./a.jpg"))
    }
}
