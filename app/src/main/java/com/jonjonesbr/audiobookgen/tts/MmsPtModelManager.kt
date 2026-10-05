package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Download/gestão do bundle oficial "MMS-TTS Português" (V6 Parte A) — mesmo mecanismo do
 * [KokoroModelManager] (zip + sidecar `.sha256` no mesmo repositório HuggingFace, checksum
 * real antes de extrair), mas o destino da extração é `filesDir/importados/$PACK_ID/` — a
 * MESMA pasta que o [BYOMManager] usa pra pacotes importados pelo usuário.
 *
 * Por quê: a Parte A é, do ponto de vista do motor de síntese, um pacote VITS igual a
 * qualquer BYOM — só a ORIGEM difere (baixado do nosso próprio repositório, com manifesto.json
 * fixo aqui, em vez de escolhido pelo usuário via .zip). Reaproveitar o mesmo formato de pasta +
 * manifesto evita duplicar toda a lógica de resolução de voz do [KokoroTtsEngine]
 * (`synthesizeByom`/`byomEngine` já sabem carregar qualquer pacote nesse formato, arquitetura
 * "vits" incluída) — a Parte A "é" um pacote BYOM pré-instalado, só que baixado em vez de
 * importado. O catálogo (visibilidade em Ajustes → Vozes offline, botão Baixar/Apagar) continua
 * registrado como pacote de PRIMEIRA CLASSE no [com.jonjonesbr.audiobookgen.util.VoiceCatalog]
 * (like Kokoro/Supertonic) — só a extração é compartilhada com o BYOM.
 *
 * Voz resultante: `mms-por::main` (arquitetura vits, sid 0 — modelo de speaker único,
 * `frontend: characters`, SEM pontuação — mitigado sintetizando por oração, ver
 * [KokoroTtsEngine.dividirParaByom]). Licença CC-BY-NC 4.0 (Meta, MMS) — atribuição no
 * manifesto.json embutido no bundle, consumida pela tela de créditos.
 */
object MmsPtModelManager {
    private const val TAG = "MmsPtModelManager"
    internal const val BASE_URL = "https://huggingface.co/JonJonesBR/lylyreader-tts-models/resolve/main"
    private const val ARQUIVO_BUNDLE = "mms-tts-por-v1.zip"
    internal const val PACK_ID = "mms-por"
    private const val TAMANHO_MIN_ARQUIVO_VALIDO = 500L
    private const val TAMANHO_SHA_HEX = 64

    private val ARQUIVOS_OBRIGATORIOS = listOf("model.onnx", "tokens.txt", "manifesto.json")

    fun dirBundle(context: Context): File = File(BYOMManager.dirImportados(context), PACK_ID)

    /** Bundle baixado, checksum validado, extraído e com manifesto reconhecido pelo BYOMManager? */
    fun isReady(context: Context): Boolean {
        val dir = dirBundle(context)
        // Piso de tamanho só faz sentido pro peso do modelo (pega download truncado); tokens.txt
        // (vocabulário) e manifesto.json (metadados) são legitimamente pequenos — o bundle real
        // tem tokens.txt de 476 bytes e manifesto.json de 311 bytes, ambos abaixo de
        // TAMANHO_MIN_ARQUIVO_VALIDO (mesma causa raiz do fix em BYOMManager.validarConteudo).
        val modelOk = File(dir, "model.onnx").let { it.exists() && it.length() >= TAMANHO_MIN_ARQUIVO_VALIDO }
        val demaisOk = (ARQUIVOS_OBRIGATORIOS - "model.onnx").all { nome ->
            File(dir, nome).let { it.exists() && it.length() > 0L }
        }
        return modelOk && demaisOk && BYOMManager.lerManifesto(dir) != null
    }

    fun tamanhoOcupado(context: Context): Long {
        val dir = dirBundle(context)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun delete(context: Context) {
        dirBundle(context).deleteRecursively()
        File(context.filesDir, ARQUIVO_BUNDLE).delete()
        File(context.filesDir, "$ARQUIVO_BUNDLE.sha256").delete()
        File(context.filesDir, "$ARQUIVO_BUNDLE.part").delete()
    }

    /**
     * Download sob demanda (só chamado pela UI após ação explícita do usuário, mesmo padrão do
     * Kokoro): baixa o zip com resume, valida o checksum real (sidecar `.sha256`) ANTES de
     * extrair, extrai pra `filesDir/importados/mms-por/` (zip-slip + limites de tamanho/entradas
     * herdados de [BYOMManager], fonte é NOSSA mas passa pela mesma trava por consistência) e
     * valida o conteúdo contra o manifesto.
     */
    // Fronteira de download: exceção ampla intencional — qualquer falha exige limpeza do estado
    // parcial; o motivo real é relançado ao chamador (UI) e logado. Mesmo padrão do KokoroModelManager.
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

            val totalZip = baixarZipComProgresso(zipFile, onProgress)
            val shaEsperado = baixarESidecar(sidecarFile)
            validarChecksum(shaEsperado, zipFile)
            extrairEValidar(zipFile, dir)

            zipFile.delete()
            sidecarFile.delete()
            onProgress("Pronto", 1f, totalZip, totalZip)
        } catch (e: Exception) {
            dir.deleteRecursively()
            zipFile.delete()
            sidecarFile.delete()
            Log.e(TAG, "Falha no download do bundle MMS-TTS pt-BR: ${e.message}", e)
            throw e
        }
    }

    private suspend fun baixarZipComProgresso(
        zipFile: File,
        onProgress: (nome: String, pct: Float, bytes: Long, total: Long) -> Unit
    ): Long {
        val urlZip = "$BASE_URL/$ARQUIVO_BUNDLE"
        val totalZip = SupertonicAssetManager.probeFileSize(urlZip)
        onProgress("Baixando voz MMS-TTS pt-BR", 0f, 0L, totalZip)

        var acumulado = File(zipFile.parentFile, "${zipFile.name}.part").let { if (it.exists()) it.length() else 0L }
        var ultimoUpdate = 0L
        SupertonicAssetManager.downloadFileWithResume(urlZip, zipFile) { chunk ->
            acumulado += chunk
            val agora = System.currentTimeMillis()
            if (agora - ultimoUpdate > INTERVALO_PROGRESSO_MS) {
                onProgress(
                    "Baixando voz MMS-TTS pt-BR",
                    (acumulado.toFloat() / totalZip.coerceAtLeast(1)).coerceIn(0f, 1f),
                    acumulado,
                    totalZip
                )
                ultimoUpdate = agora
            }
        }
        onProgress("Baixando voz MMS-TTS pt-BR", 1f, acumulado, totalZip)
        return totalZip
    }

    private suspend fun baixarESidecar(sidecarFile: File): String {
        val sidecarUrl = "$BASE_URL/$ARQUIVO_BUNDLE.sha256"
        SupertonicAssetManager.downloadFileWithResume(sidecarUrl, sidecarFile) { }
        val shaEsperado = sidecarFile.readText().trim().substringBefore(' ').substringBefore('\t')
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
        BYOMManager.extrairZipByom(zipFile, dir)
        val manifesto = BYOMManager.lerManifesto(dir)
            ?: run { dir.deleteRecursively(); throw IOException("Bundle extraído sem manifesto válido.") }
        val erro = BYOMManager.validarConteudo(dir, manifesto)
        if (erro != null) {
            dir.deleteRecursively()
            throw IOException("Bundle extraído incompleto: $erro")
        }
    }

    private const val INTERVALO_PROGRESSO_MS = 100
}
