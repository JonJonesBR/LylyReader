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

    @Test
    fun backupSemEntradaDeVersaoEhAntigoEAceito() {
        assertNull(motivoRejeicaoBackup(mapOf("config.json" to "{}")))
    }

    @Test
    fun backupComVersaoIgualALeituraEhAceito() {
        val entradas = mapOf("versao.json" to "1", "config.json" to "{}")

        assertNull(motivoRejeicaoBackup(entradas))
    }

    @Test
    fun backupComVersaoMaisNovaEhRecusado() {
        val entradas = mapOf("versao.json" to "2", "config.json" to "{}")

        assertEquals(MotivoBackup.VERSAO_MAIS_NOVA, motivoRejeicaoBackup(entradas))
    }

    @Test
    fun versaoMaisNovaEhRecusadaMesmoSemEntradasConhecidas() {
        val entradas = mapOf("versao.json" to "9", "formato_novo.json" to "{}")

        assertEquals(MotivoBackup.VERSAO_MAIS_NOVA, motivoRejeicaoBackup(entradas))
    }

    @Test
    fun versaoIlegivelEhRecusadaComoArquivoInvalido() {
        val entradas = mapOf("versao.json" to "abc", "config.json" to "{}")

        assertEquals(MotivoBackup.ARQUIVO_INVALIDO, motivoRejeicaoBackup(entradas))
    }

    @Test
    fun versaoZeroOuNegativaEhRecusadaComoArquivoInvalido() {
        assertEquals(
            MotivoBackup.ARQUIVO_INVALIDO,
            motivoRejeicaoBackup(mapOf("versao.json" to "0", "config.json" to "{}"))
        )
        assertEquals(
            MotivoBackup.ARQUIVO_INVALIDO,
            motivoRejeicaoBackup(mapOf("versao.json" to "-1", "config.json" to "{}"))
        )
    }

    @Test
    fun mapaVazioEhRecusadoComoArquivoInvalido() {
        assertEquals(MotivoBackup.ARQUIVO_INVALIDO, motivoRejeicaoBackup(emptyMap()))
    }

    @Test
    fun zipInvalidoEhRecusadoComoArquivoInvalido() {
        val entradas = lerZip("isto não é um zip".toByteArray(Charsets.UTF_8))

        assertEquals(MotivoBackup.ARQUIVO_INVALIDO, motivoRejeicaoBackup(entradas))
    }

    @Test
    fun arquivoVazioEhRecusadoComoArquivoInvalido() {
        assertEquals(MotivoBackup.ARQUIVO_INVALIDO, motivoRejeicaoBackup(lerZip(ByteArray(0))))
    }

    @Test
    fun zipSemEntradasConhecidasEhRecusadoComoArquivoInvalido() {
        val entradas = lerZip(zipCom("outro.txt" to "x"))

        assertEquals(MotivoBackup.ARQUIVO_INVALIDO, motivoRejeicaoBackup(entradas))
    }

    @Test
    fun zipComVersaoEConteudoEhAceitoPelaLeituraDoZip() {
        val entradas = lerZip(zipCom("versao.json" to "1", "bookmarks.json" to "[]"))

        assertNull(motivoRejeicaoBackup(entradas))
    }

    /** Motivo da exceção lançada por [bloco], ou null se ele não lançar [BackupException]. */
    private fun motivoDe(bloco: () -> Unit): MotivoBackup? = try {
        bloco()
        null
    } catch (e: BackupException) {
        e.motivo
    }

    @Test
    fun entradaDesconhecidaGrandeEIgnoradaSemEstourarOLimite() {
        val zip = zipCom("config.json" to "{}", "lixo.bin" to "x".repeat(5_000))

        val entradas = lerEntradasZip(ByteArrayInputStream(zip), limiteEntrada = 1_024, limiteTotal = 2_048)

        assertEquals(setOf("config.json"), entradas.keys)
    }

    @Test
    fun entradaConhecidaMaiorQueOLimiteEhRecusadaComoMuitoGrande() {
        val zip = zipCom("config.json" to "x".repeat(2_000))

        val motivo = motivoDe { lerEntradasZip(ByteArrayInputStream(zip), limiteEntrada = 1_024, limiteTotal = 8_192) }

        assertEquals(MotivoBackup.MUITO_GRANDE, motivo)
    }

    @Test
    fun somaDasEntradasAcimaDoTotalEhRecusadaComoMuitoGrande() {
        val zip = zipCom(
            "config.json" to "x".repeat(600),
            "bookmarks.json" to "x".repeat(600),
            "highlights.json" to "x".repeat(600)
        )

        val motivo = motivoDe { lerEntradasZip(ByteArrayInputStream(zip), limiteEntrada = 1_024, limiteTotal = 1_000) }

        assertEquals(MotivoBackup.MUITO_GRANDE, motivo)
    }

    @Test
    fun backupDeTamanhoRazoavelPassaNoLimitePadrao() {
        val conteudo = "x".repeat(2_000_000)
        val zip = zipCom("bookmarks.json" to conteudo)

        val entradas = lerZip(zip)

        assertEquals(conteudo, entradas["bookmarks.json"])
    }

    @Test
    fun zipCortadoNoMeioEhRecusadoComoArquivoInvalido() {
        val aleatorio = java.util.Random(7)
        val texto = (1..200_000).map { 'a' + aleatorio.nextInt(26) }.joinToString("")
        val zip = zipCom("bookmarks.json" to texto)
        val cortado = zip.copyOf(zip.size / 2)

        val motivo = motivoDe { lerZip(cortado) }

        assertEquals(MotivoBackup.ARQUIVO_INVALIDO, motivo)
    }
}
