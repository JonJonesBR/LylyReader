package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Download/gestão do bundle do motor Kokoro (Sherpa-ONNX) em `filesDir/kokoro/`.
 *
 * V6 T1.4. Decisões aplicadas:
 * - **Generalização (compartilhamento por referência)**: o download reusa as primitivas
 *   `probeFileSize`/`downloadFileWithResume` do [SupertonicAssetManager] (agora internal) —
 *   não existe um terceiro downloader bespoke. Elas ficam lá (não num arquivo novo) porque a
 *   extração reabriria os achados do detekt já baselined — e o repo proíbe regenerar o
 *   baseline. Parte A (MMS-por, bundle único) reusa o mesmo caminho.
 * - **Formato**: UM artefato `.zip` + sidecar `.sha256` no HuggingFace
 *   (`JonJonesBR/lylyreader-tts-models`, mesma infra da Parte A). O bundle oficial da k2-fsa é
 *   `.tar.bz2`, que o Android não extrai com stdlib — re-empacotado como zip (mesmo conteúdo,
 *   checksum por artefato, como decidido na T0.3).
 * - **Checksum real**: o `.sha256` do sidecar é BAIXADO e comparado contra o hash do arquivo
 *   baixado (streaming) ANTES de extrair — download corrompido/trocado nunca vira bundle.
 * - **Sob demanda**: nada chama o download automaticamente (nem 1ª abertura, nem síntese —
 *   o engine só reporta "modelo não baixado"). O gatilho é sempre ação explícita do usuário
 *   na UI (VoiceDownloadFlow/T2.2), que avisa quando fora de WiFi.
 *
 * Layout final (validação espelha o KokoroTtsEngine):
 *   filesDir/kokoro/{model.onnx, voices.bin, tokens.txt, lexicon-*.txt,
 *                   espeak-ng-data/, .ready}
 */
object KokoroModelManager {
    private const val TAG = "KokoroModelManager"
    internal const val BASE_URL = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main"
    private const val ARQUIVO_BUNDLE = "kokoro-multi-lang-v1_0.zip"
    private const val MARKER = ".ready"
    private const val TAMANHO_MIN_ARQUIVO_VALIDO = 500L
    private const val DIR_ESPEAK_DATA = "espeak-ng-data"
    private const val MIN_ARQUIVOS_ESPEAK = 10
    private const val TAMANHO_SHA_HEX = 64
    private const val INTERVALO_PROGRESSO_MS = 100

    // Espelha o conjunto validado pelo KokoroTtsEngine.isModelDownloaded (bundle v1.0).
    private val ARQUIVOS_OBRIGATORIOS = listOf(
        "model.onnx",
        "voices.bin",
        "tokens.txt",
        "lexicon-us-en.txt",
        "lexicon-gb-en.txt",
        "lexicon-zh.txt"
    )

    /** Bundle baixado, checksum validado e extraído? */
    fun isReady(context: Context): Boolean {
        val dir = dirBundle(context)
        if (!File(dir, MARKER).exists()) return false
        val arquivosOk = ARQUIVOS_OBRIGATORIOS.all { nome ->
            val f = File(dir, nome)
            f.exists() && f.length() >= TAMANHO_MIN_ARQUIVO_VALIDO
        }
        val espeak = File(dir, DIR_ESPEAK_DATA)
        return arquivosOk && espeak.isDirectory && (espeak.list()?.size ?: 0) >= MIN_ARQUIVOS_ESPEAK
    }

    fun dirBundle(context: Context): File = File(context.filesDir, KOKORO_MODEL_DIR)

    fun tamanhoOcupado(context: Context): Long = tamanhoDiretorio(dirBundle(context))

    fun delete(context: Context) {
        dirBundle(context).deleteRecursively()
        File(context.filesDir, ARQUIVO_BUNDLE).delete()
        File(context.filesDir, "$ARQUIVO_BUNDLE.sha256").delete()
        File(context.filesDir, "$ARQUIVO_BUNDLE.part").delete()
    }

    /**
     * Download sob demanda (chamado SÓ pela UI após ação explícita do usuário):
     * 1. baixa o zip (resume, `.part`+rename) para filesDir;
     * 2. baixa o sidecar `.sha256` e compara o hash REAL do zip (streaming) — mismatch apaga
     *    tudo e falha;
     * 3. extrai para filesDir/kokoro/ (zip-slip protegido), valida os arquivos e grava `.ready`.
     */
    // Fronteira de download: exceção ampla intencional — qualquer falha exige a limpeza do
    // estado parcial; o motivo real é relançado ao chamador (UI) e logado.
    @Suppress("TooGenericExceptionCaught")
    suspend fun download(
        context: Context,
        onProgress: (nome: String, pct: Float, bytes: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dir = dirBundle(context)
        val zipFile = File(context.filesDir, ARQUIVO_BUNDLE)
        val sidecarFile = File(context.filesDir, "$ARQUIVO_BUNDLE.sha256")
        try {
            dir.deleteRecursively()
            if (!dir.exists()) dir.mkdirs()
            File(dir, MARKER).delete()

            val totalZip = baixarZipComProgresso(zipFile, onProgress)
            val shaEsperado = baixarESidecar(sidecarFile)
            validarChecksum(shaEsperado, zipFile)
            extrairEValidar(zipFile, dir)

            File(dir, MARKER).createNewFile()
            zipFile.delete()
            sidecarFile.delete()
            onProgress("Pronto", 1f, totalZip, totalZip)
        } catch (e: Exception) {
            File(dir, MARKER).delete()
            zipFile.delete()
            sidecarFile.delete()
            Log.e(TAG, "Falha no download do bundle Kokoro: ${e.message}", e)
            throw e
        }
    }

    private suspend fun baixarZipComProgresso(
        zipFile: File,
        onProgress: (nome: String, pct: Float, bytes: Long, total: Long) -> Unit
    ): Long {
        val urlZip = "$BASE_URL/$ARQUIVO_BUNDLE"
        val totalZip = SupertonicAssetManager.probeFileSize(urlZip)
        onProgress("Baixando modelo Kokoro", 0f, 0L, totalZip)

        var acumulado = File(zipFile.parentFile, "${zipFile.name}.part").let { if (it.exists()) it.length() else 0L }
        var ultimoUpdate = 0L
        SupertonicAssetManager.downloadFileWithResume(urlZip, zipFile) { chunk ->
            acumulado += chunk
            val agora = System.currentTimeMillis()
            if (agora - ultimoUpdate > INTERVALO_PROGRESSO_MS) {
                onProgress(
                    "Baixando modelo Kokoro",
                    (acumulado.toFloat() / totalZip.coerceAtLeast(1)).coerceIn(0f, 1f),
                    acumulado,
                    totalZip
                )
                ultimoUpdate = agora
            }
        }
        onProgress("Baixando modelo Kokoro", 1f, acumulado, totalZip)
        return totalZip
    }

    private suspend fun baixarESidecar(sidecarFile: File): String {
        val sidecarUrl = "$BASE_URL/$ARQUIVO_BUNDLE.sha256"
        SupertonicAssetManager.downloadFileWithResume(sidecarUrl, sidecarFile) { }
        val shaEsperado = sidecarFile.readText()
            .trim()
            .substringBefore(' ')
            .substringBefore('\t')
        if (shaEsperado.length != TAMANHO_SHA_HEX) {
            throw IOException("Sidecar sha256 inválido (${shaEsperado.length} chars)")
        }
        return shaEsperado
    }

    private fun validarChecksum(shaEsperado: String, zipFile: File) {
        val shaReal = sha256Hex(zipFile)
        if (!shaEsperado.equals(shaReal, ignoreCase = true)) {
            Log.e(TAG, "sha256 mismatch: esperado=$shaEsperado real=$shaReal")
            throw IOException("Checksum do download não confere — arquivo descartado. Tente novamente.")
        }
    }

    private fun extrairEValidar(zipFile: File, dir: File) {
        extrairZip(zipFile, dir)
        val espeak = File(dir, DIR_ESPEAK_DATA)
        val valido = ARQUIVOS_OBRIGATORIOS.all {
            File(dir, it).let { f -> f.exists() && f.length() >= TAMANHO_MIN_ARQUIVO_VALIDO }
        } && espeak.isDirectory && (espeak.list()?.size ?: 0) >= MIN_ARQUIVOS_ESPEAK
        if (!valido) {
            dir.deleteRecursively()
            throw IOException("Bundle extraído incompleto — descartado. Tente novamente.")
        }
    }

    private fun tamanhoDiretorio(diretorio: File): Long {
        if (!diretorio.exists()) return 0L
        return diretorio.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

/**
 * Extrai [zipFile] em [destino] via stdlib (java.util.zip). Rejeita entradas com ".." ou
 * caminho absoluto (zip-slip). Função pura (sem android) — testável em JVM.
 */
internal fun extrairZip(zipFile: File, destino: File) {
    if (destino.exists()) destino.deleteRecursively()
    if (!destino.exists()) destino.mkdirs()
    var entradas = 0
    ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            val nome = entry.name
            if (!nomeZipSeguro(nome)) {
                throw IOException("Entrada insegura no zip: '$nome'")
            }
            if (!entry.isDirectory) {
                extrairArquivoDoZip(zip, destino, nome)
                entradas++
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    if (entradas == 0) {
        throw IOException("Zip vazio ou sem arquivos: ${zipFile.name}")
    }
}

private fun extrairArquivoDoZip(zip: ZipInputStream, destino: File, nome: String) {
    val saida = File(destino, nome)
    saida.parentFile?.let { if (!it.exists()) it.mkdirs() }
    FileOutputStream(saida).use { out -> zip.copyTo(out) }
}

private fun nomeZipSeguro(nome: String): Boolean =
    !nome.startsWith("/") && !nome.split('/', '\\').any { it == ".." }

/** SHA-256 (hex, minúsculo) de um arquivo, lido em streaming. Função pura — testável. */
internal fun sha256Hex(arquivo: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    arquivo.inputStream().buffered().use { input ->
        val buffer = ByteArray(BUFFER_SHA)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    val bytes = digest.digest()
    val hex = CharArray(bytes.size * 2)
    for ((i, b) in bytes.withIndex()) {
        val v = b.toInt() and MASCARA_BYTE
        hex[i * 2] = DIGITS_HEX[v ushr DESLOCAMENTO_NIBBLE_ALTO]
        hex[i * 2 + 1] = DIGITS_HEX[v and MASCARA_NIBBLE]
    }
    return String(hex)
}

private const val BUFFER_SHA = 65_536
private const val MASCARA_BYTE = 0xFF
private const val MASCARA_NIBBLE = 0x0F
private const val DESLOCAMENTO_NIBBLE_ALTO = 4
private val DIGITS_HEX = "0123456789abcdef".toCharArray()
