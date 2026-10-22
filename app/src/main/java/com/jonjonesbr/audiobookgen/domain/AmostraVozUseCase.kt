package com.jonjonesbr.audiobookgen.domain

import android.app.Application
import com.jonjonesbr.audiobookgen.R
import com.jonjonesbr.audiobookgen.util.CrashLogWriter
import com.jonjonesbr.audiobookgen.util.TtsRate
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resultado da geração de uma amostra de voz. [Pronta] traz o caminho do áudio para tocar;
 * [Falha] traz o texto de status já pronto para a UI mostrar (com o prefixo "⚠ " quando há).
 */
sealed class ResultadoAmostra {
    data class Pronta(val caminho: String) : ResultadoAmostra()
    data class Falha(val mensagem: String) : ResultadoAmostra()
}

/**
 * Nome do arquivo de cache de uma amostra sintetizada localmente. Mesmo formato de sempre:
 * `preview_<motor>_<hash da voz>_<hash do texto>.wav`.
 */
internal fun nomeArquivoCacheAmostra(prefixoMotor: String, voz: String, texto: String): String =
    "preview_${prefixoMotor}_${voz.hashCode()}_${texto.hashCode()}.wav"

/**
 * Gera a amostra de voz (prévia) para o motor indicado. Não toca áudio nem mexe no estado da
 * tela: quem chama decide o que mostrar e quando iniciar a reprodução.
 */
class AmostraVozUseCase(
    private val app: Application,
    private val pythonEngine: PythonEngineUseCase
) {

    companion object {
        /** `ritmo` é percentual (100 = normal) — motores Android TTS esperam um multiplicador. */
        private const val RITMO_BASE = 100f
        private const val TEXTO_AMOSTRA_PADRAO = "Hello! This is a sample text for the selected voice engine."
    }

    // Despacha entre 3 caminhos de síntese (android/onnx/python) — cada um com seu próprio
    // fluxo de cache+erro; separar em funções menores fragmentaria a leitura do dispatch geral.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun gerar(
        voz: String,
        ritmo: Int,
        motor: String,
        chaveGemini: String,
        texto: String = ""
    ): ResultadoAmostra {
        val velStr = TtsRate.fromRitmo(ritmo)

        val motorEfetivo = VoiceCatalog.effectiveEngine(voz, motor)

        if (motorEfetivo == "android") {
            val speed = ritmo / RITMO_BASE
            val textoAmostra = texto.ifBlank { TEXTO_AMOSTRA_PADRAO }
            val outFile = File(app.cacheDir, nomeArquivoCacheAmostra("android", voz, textoAmostra))
            val result: Result<File> = if (outFile.exists() && outFile.length() > 0) {
                Result.success(outFile)
            } else {
                com.jonjonesbr.audiobookgen.tts.AndroidTtsEngineCache.synthesize(
                    app, voz, textoAmostra, outFile, speed
                )
            }
            return if (result.isSuccess) {
                ResultadoAmostra.Pronta(outFile.absolutePath)
            } else {
                CrashLogWriter.log(result.exceptionOrNull(), "gerarAmostra", "voz=$voz")
                ResultadoAmostra.Falha("⚠ " + app.getString(R.string.amostra_falha_dispositivo))
            }
        }

        // Bug #8: ONNX preview usa o engine Kotlin nativo diretamente (mais robusto)
        if (motorEfetivo == "onnx") {
            // Supertonic sem modelo baixado: ouve o clipe pré-renderizado antes do download (Q5).
            // Sem clipe, ou com falha (404/rede), segue para a síntese local abaixo, como antes.
            // Estilos multilíngues (f1/m1) tocam o clipe no idioma do aparelho (pt/es/en).
            val idiomaPrevia = java.util.Locale.getDefault().language
            // Clipe embarcado primeiro só quando o pacote ainda não está baixado (ou para
            // vozes que sempre tocam clipe, ver usarClipeEmbarcadoPrimeiro); com pacote
            // pronto a síntese local preserva ritmo e texto do usuário.
            val pacotePronto = withContext(Dispatchers.IO) {
                VoiceCatalog.modeloProntoParaVoz(voz, app)
            }
            val clipe = (if (VoiceCatalog.usarClipeEmbarcadoPrimeiro(voz, pacotePronto)) {
                clipeEmbarcado(voz, idiomaPrevia)
            } else {
                null
            }) ?: run {
                val clipeSemModelo = withContext(Dispatchers.IO) {
                    VoiceCatalog.previewClipUrl(voz, idiomaPrevia) != null && !pacotePronto
                }
                if (clipeSemModelo) baixarClipePrevia(voz, idiomaPrevia) else null
            }
            if (clipe != null) return ResultadoAmostra.Pronta(clipe)

            val caminho = withContext(Dispatchers.IO) {
                val engine = com.jonjonesbr.audiobookgen.tts.OnnxTtsEngine(app)
                val textoAmostra = texto.ifBlank { TEXTO_AMOSTRA_PADRAO }
                val outFile = File(app.cacheDir, nomeArquivoCacheAmostra("onnx", voz, textoAmostra))
                val result: Result<File> = if (outFile.exists() && outFile.length() > 0) {
                    Result.success(outFile)
                } else {
                    engine.synthesize(textoAmostra, voz, outFile)
                }
                if (result.isSuccess) outFile.absolutePath else null
            }
            return if (caminho != null) {
                ResultadoAmostra.Pronta(caminho)
            } else {
                ResultadoAmostra.Falha(app.getString(R.string.status_amostra_onnx_falha))
            }
        }

        if (motorEfetivo == "kokoro") {
            // Clipe embarcado toca antes de download ou síntese para Kokoro/MMS (sempre) e
            // para Piper só com pacote ainda não baixado (ver usarClipeEmbarcadoPrimeiro).
            val pacotePronto = withContext(Dispatchers.IO) {
                VoiceCatalog.modeloProntoParaVoz(voz, app)
            }
            val clipeAsset = if (VoiceCatalog.usarClipeEmbarcadoPrimeiro(voz, pacotePronto)) {
                clipeEmbarcado(voz)
            } else {
                null
            }
            if (clipeAsset != null) return ResultadoAmostra.Pronta(clipeAsset)
        }

        if (motorEfetivo == "kokoro" && VoiceCatalog.previewClipUrl(voz) == null) {
            // Vozes Piper/VITS (mesmo motor "kokoro") não têm clipe pré-renderizado: a amostra
            // é sintetizada localmente com o pacote já baixado (o seletor de vozes só libera o
            // ▶ depois do download).
            val sampleText = texto.ifBlank {
                VoiceCatalog.findAny(voz)?.let(VoiceCatalog::previewSampleText)
                    ?: "Olá, esta é uma amostra da voz selecionada."
            }
            val synthesizer = com.jonjonesbr.audiobookgen.tts.KokoroTtsSynthesizer(app)
            val caminho = withContext(Dispatchers.IO) {
                synthesizer.synthesize(sampleText, voz, ritmo)
            }
            return if (caminho != null) {
                ResultadoAmostra.Pronta(caminho)
            } else {
                ResultadoAmostra.Falha(
                    synthesizer.ultimoErro() ?: app.getString(R.string.preview_kokoro_falha)
                )
            }
        }

        if (motorEfetivo == "kokoro") {
            // Preview do Kokoro NÃO exige o bundle de ~350MB: baixa só o clipe
            // pré-renderizado (poucos KB, HF) — o usuário ouve a voz antes de
            // comprometer o download (V6 T1.5).
            val caminho = baixarClipePrevia(voz)
            return if (caminho != null) {
                ResultadoAmostra.Pronta(caminho)
            } else {
                ResultadoAmostra.Falha(app.getString(R.string.preview_kokoro_falha))
            }
        }

        if (motorEfetivo == "pocket") {
            val sampleText = texto.ifBlank {
                VoiceCatalog.findAny(voz)?.let(VoiceCatalog::previewSampleText)
                    ?: "Olá, esta é uma amostra da voz Pocket TTS."
            }
            val synthesizer = com.jonjonesbr.audiobookgen.tts.PocketTtsSynthesizer(app)
            val caminho = withContext(Dispatchers.IO) {
                synthesizer.synthesize(sampleText, voz, ritmo)
            }
            return if (caminho != null) {
                ResultadoAmostra.Pronta(caminho)
            } else {
                ResultadoAmostra.Falha(
                    synthesizer.ultimoErro() ?: app.getString(R.string.preview_pocket_failure)
                )
            }
        }

        return when (val result = pythonEngine.gerarPreviewVoz(voz, velStr, motorEfetivo, chaveGemini, texto)) {
            is PreviewResult.Success -> ResultadoAmostra.Pronta(result.caminho)
            is PreviewResult.Error -> ResultadoAmostra.Falha("⚠ ${result.mensagem}")
        }
    }

    /**
     * Baixa (ou reaproveita do cache) o clipe pré-renderizado de [voz] e devolve o caminho local.
     * Null se a voz não tem clipe ([VoiceCatalog.previewClipUrl]) ou o download falhou (404/rede);
     * quem chama cai no comportamento anterior. Usado pela prévia Kokoro e pela Supertonic sem modelo.
     * Clipes Supertonic embarcados em `assets/previas/` são copiados do APK, sem rede; o download
     * só acontece quando não há asset para a voz.
     */
    private suspend fun baixarClipePrevia(voz: String, idioma: String = "pt"): String? =
        withContext(Dispatchers.IO) {
            val url = VoiceCatalog.previewClipUrl(voz, idioma) ?: return@withContext null
            // Nome derivado do clipe (não do id da voz): es/en/pt da mesma voz não podem colidir no cache.
            val outFile = File(app.cacheDir, "preview_${url.substringAfterLast('/')}")
            if (!outFile.exists() || outFile.length() == 0L) {
                val copiadoDoAsset = copiarClipePrevia(VoiceCatalog.previewAssetName(voz, idioma), outFile)
                if (!copiadoDoAsset) {
                    val ok = kotlin.runCatching {
                        com.jonjonesbr.audiobookgen.tts.SupertonicAssetManager
                            .downloadFileWithResume(url, outFile) { }
                    }.isSuccess
                    if (!ok) outFile.delete()
                }
            }
            outFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        }

    /**
     * Copia o clipe embarcado de [voz] (`assets/previas/`, nome dado por
     * [VoiceCatalog.previewAssetName]) para o cache e devolve o caminho. Null se a voz não tem
     * asset ou a cópia falhou. Sem rede e sem exigir o pacote baixado.
     */
    private suspend fun clipeEmbarcado(voz: String, idioma: String = "pt"): String? =
        withContext(Dispatchers.IO) {
            val nome = VoiceCatalog.previewAssetName(voz, idioma) ?: return@withContext null
            // Mesmo nome que baixarClipePrevia usa para o arquivo remoto: os dois caminhos compartilham o cache.
            val outFile = File(app.cacheDir, "preview_$nome")
            if (!outFile.exists() || outFile.length() == 0L) {
                if (!copiarClipePrevia(nome, outFile)) return@withContext null
            }
            outFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        }

    /** Copia `assets/previas/<nomeAsset>` para [destino]. False se não há asset ou a cópia falhou. */
    private fun copiarClipePrevia(nomeAsset: String?, destino: File): Boolean {
        if (nomeAsset == null) return false
        val ok = kotlin.runCatching {
            app.assets.open("previas/$nomeAsset").use { entrada ->
                destino.outputStream().use { saida -> entrada.copyTo(saida) }
            }
        }.isSuccess
        if (!ok) destino.delete()
        return ok
    }
}
