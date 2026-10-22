package com.jonjonesbr.audiobookgen.domain

import com.jonjonesbr.audiobookgen.service.PlaybackProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupUseCaseTest {

    private fun zipCom(vararg entradas: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entradas.forEach { (nome, conteudo) ->
                zip.putNextEntry(ZipEntry(nome))
                zip.write(conteudo.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun lerZip(bytes: ByteArray): Map<String, String> =
        lerEntradasZip(ByteArrayInputStream(bytes))

    @Test
    fun zipValidoDevolveTodasAsEntradasComUtf8() {
        val zip = zipCom(
            "config.json" to "{\"ritmo\":120}",
            "bookmarks.json" to "[{\"nota\":\"ção\"}]"
        )

        val entradas = lerZip(zip)

        assertEquals(2, entradas.size)
        assertEquals("{\"ritmo\":120}", entradas["config.json"])
        assertEquals("[{\"nota\":\"ção\"}]", entradas["bookmarks.json"])
    }

    @Test
    fun zipSemUmaEntradaNaoTemAChaveAusente() {
        val entradas = lerZip(zipCom("config.json" to "{}"))

        assertTrue(entradas.containsKey("config.json"))
        assertFalse(entradas.containsKey("progresso.json"))
    }

    @Test
    fun bytesQueNaoSaoZipDevolvemMapaVazio() {
        val entradas = lerZip("isto não é um zip".toByteArray(Charsets.UTF_8))

        assertTrue(entradas.isEmpty())
    }

    @Test
    fun arquivoVazioDevolveMapaVazio() {
        assertTrue(lerZip(ByteArray(0)).isEmpty())
    }

    @Test
    fun mapaValidoEhDecodificado() {
        val mapa = mapaDeBackup("{\"cap1\":\"10\",\"cap2\":\"20\"}")

        assertEquals(mapOf("cap1" to "10", "cap2" to "20"), mapa)
    }

    @Test
    fun mapaVazioEhValidoENaoEhNulo() {
        assertEquals(emptyMap<String, String>(), mapaDeBackup("{}"))
    }

    @Test
    fun mapaCorrompidoDevolveNulo() {
        assertNull(mapaDeBackup("{ruim"))
    }

    @Test
    fun mapaComArrayNoLugarDeObjetoDevolveNulo() {
        assertNull(mapaDeBackup("[1, 2]"))
    }

    @Test
    fun progressoValidoEhDecodificado() {
        val progresso = progressoDeBackup(
            "[{\"caminho\":\"/livros/a.epub\",\"positionMs\":10,\"durationMs\":100}]"
        )

        assertEquals(
            mapOf("/livros/a.epub" to PlaybackProgressStore.Progress(10, 100)),
            progresso
        )
    }

    @Test
    fun progressoDescartaEntradasInvalidas() {
        val progresso = progressoDeBackup(
            "[" +
                "{\"caminho\":\"\",\"positionMs\":10,\"durationMs\":100}," +
                "{\"caminho\":\"/b.epub\",\"positionMs\":-1,\"durationMs\":100}," +
                "{\"caminho\":\"/c.epub\",\"positionMs\":10,\"durationMs\":0}," +
                "{\"positionMs\":10,\"durationMs\":100}" +
                "]"
        )

        assertEquals(emptyMap<String, PlaybackProgressStore.Progress>(), progresso)
    }

    @Test
    fun progressoIgnoraElementoQueNaoEhObjetoEMantemOsValidos() {
        val progresso = progressoDeBackup(
            "[1, {\"caminho\":\"/d.epub\",\"positionMs\":0,\"durationMs\":5}]"
        )

        assertEquals(
            mapOf("/d.epub" to PlaybackProgressStore.Progress(0, 5)),
            progresso
        )
    }

    @Test
    fun progressoCorrompidoDevolveNulo() {
        assertNull(progressoDeBackup("[{"))
    }
}
