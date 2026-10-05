package com.jonjonesbr.audiobookgen.tts

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jonjonesbr.audiobookgen.util.PacoteVozes
import com.jonjonesbr.audiobookgen.util.VoiceCatalog
import com.jonjonesbr.audiobookgen.util.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * BYOM (Bring Your Own Model) — Parte B do PLANO_MELHORIAS_V6: importa pacotes de voz de
 * arquitetura Kokoro ou VITS via arquivo .zip, compatíveis com o sherpa-onnx já embutido no
 * app (mesmo AAR do motor Kokoro — nenhuma dependência nova; ver [OfflineTtsVitsConfigVerified]
 * pra confirmação da API na versão pinada 1.13.7).
 *
 * Formato esperado do .zip (arquivos soltos na raiz, sem pasta-mãe):
 * ```
 * manifesto.json  — {
 *   "arquitetura": "kokoro" | "vits",
 *   "nome": "Nome amigável do pacote",
 *   "atribuicao": "Texto de créditos/licença do modelo",
 *   "vozes": [{ "id": "voz1", "nome": "Nome", "idioma": "pt-BR", "sid": 0 }, ...]
 * }
 * model.onnx, tokens.txt        — obrigatórios pras 2 arquiteturas
 * voices.bin                    — obrigatório só pra "kokoro"
 * lexicon-*.txt (kokoro) / lexicon.txt (vits) e/ou espeak-ng-data/ — pelo menos 1 dos 2,
 *   EXCETO quando o manifesto declara "semFonemizacao": true (ver abaixo)
 *   (mesma exigência do InitFrontend do sherpa: precisa de lexicon OU dados de fonemização)
 * dict/                         — opcional (jieba, só relevante pra zh — sem uso em pt-BR)
 * ```
 *
 * `"semFonemizacao": true` (opcional no manifesto, default false, só lido pra "vits") — modelos
 * VITS de tokenização puramente por caractere (ex.: MMS-TTS da Meta, metadado ONNX
 * `frontend=characters`) não usam lexicon NEM espeak-ng-data — o sherpa-onnx lê o frontend
 * direto do metadado do `model.onnx` e ignora `lexicon`/`dataDir` vazios nesse caso (confirmado
 * na conversão real da Parte A do V6). Sem essa flag, [validarConteudo] rejeitava esses bundles
 * como "incompletos" mesmo estando corretos — bug real, achado ao testar o download da voz
 * MMS-TTS pt-BR em device (2026-09-14).
 *
 * Segurança (registro T1.5 do plano — fonte NÃO-confiável, ao contrário do bundle Kokoro Base
 * hospedado por nós): zip-slip (mesmo princípio de [extrairZip], reimplementado aqui porque a
 * versão do Kokoro não aceita os limites extras abaixo), limite de tamanho de zip/arquivo
 * individual/nº de entradas, tipos de arquivo permitidos na RAIZ do zip (só o conjunto exato
 * esperado — nada de `.so`/executável), conteúdo validado contra o manifesto ANTES de
 * registrar, destino sempre em `filesDir/importados/<id>/` com `<id>` gerado internamente
 * (hash do zip — nunca derivado de nome de entrada ou do manifesto).
 *
 * Persistência: cada pacote importado grava seu `manifesto.json` validado no próprio diretório
 * — [rehydratarTodos] relê isso na próxima inicialização (chamado por `LylyApplication`) e
 * registra de novo no [VoiceCatalog] (que só mantém pacotes em memória, RN-3/T2.1 — não há
 * banco/Room envolvido).
 */
object BYOMManager {
    private const val TAG = "BYOMManager"
    private const val DIR_IMPORTADOS = "importados"
    private const val MANIFESTO = "manifesto.json"
    private const val PREFIXO_ID = "byom"

    private const val MB = 1024L * 1024L
    private const val MAX_ZIP_BYTES = 500L * MB
    private const val MAX_TOTAL_EXTRAIDO_BYTES = 600L * MB
    private const val MAX_ENTRADAS = 300
    private const val MAX_BYTES_ARQUIVO_PEQUENO = 20L * MB
    private const val TAMANHO_MIN_ARQUIVO_VALIDO = 500L

    // Nomes de arquivo aceitos na RAIZ do zip (fora dos subdiretórios de dados abaixo).
    private val RAIZ_PERMITIDA = setOf(
        "manifesto.json", "model.onnx", "tokens.txt", "voices.bin", "lexicon.txt"
    )
    // Subdiretórios de dados de fonemização — mesmo formato do bundle Kokoro Base já vetado
    // (espeak-ng-data) + jieba (dict, zh). Conteúdo interno não é restrito por extensão (dados
    // de fonemização variam de formato), mas segue as mesmas travas de tamanho/zip-slip.
    private val SUBDIRS_DADOS = setOf("espeak-ng-data", "dict")

    data class VozImportada(val idOriginal: String, val nome: String, val idioma: String, val sid: Int)

    data class ManifestoImportado(
        val arquitetura: String,
        val nome: String,
        val atribuicao: String,
        val vozes: List<VozImportada>,
        val semFonemizacao: Boolean = false,
    )

    fun dirImportados(context: Context): File = File(context.filesDir, DIR_IMPORTADOS)

    // ── Importação ──────────────────────────────────────────────────────────

    /** Importa o .zip apontado por [zipUri] (escolhido via SAF). Sucesso devolve o nome do
     * pacote pra UI; falha devolve o motivo (mensagem técnica — a UI decide como exibir). */
    suspend fun importar(context: Context, zipUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val marcador = System.currentTimeMillis()
        val stagingZip = File(context.cacheDir, "byom_import_$marcador.zip")
        val stagingDir = File(context.cacheDir, "byom_staging_$marcador")
        try {
            copiarUriParaArquivo(context, zipUri, stagingZip)
            if (stagingZip.length() > MAX_ZIP_BYTES) {
                return@withContext Result.failure(
                    IOException("Arquivo maior que o limite de ${MAX_ZIP_BYTES / MB}MB.")
                )
            }
            extrairZipByom(stagingZip, stagingDir)
            val manifesto = lerManifesto(stagingDir)
                ?: return@withContext Result.failure(IOException("manifesto.json ausente ou inválido."))
            validarConteudo(stagingDir, manifesto)?.let { erro ->
                return@withContext Result.failure(IOException(erro))
            }

            val packId = "$PREFIXO_ID-" + sha256Hex(stagingZip).take(TAMANHO_HASH_ID)
            val destino = File(dirImportados(context), packId)
            if (destino.exists()) destino.deleteRecursively()
            destino.parentFile?.mkdirs()
            if (!stagingDir.renameTo(destino)) {
                stagingDir.copyRecursively(destino, overwrite = true)
            }

            VoiceCatalog.registrarPacoteLocal(construirPacote(packId, destino, manifesto))
            Result.success(manifesto.nome)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao importar pacote BYOM: ${e.message}", e)
            Result.failure(e)
        } finally {
            stagingZip.delete()
            stagingDir.deleteRecursively()
        }
    }

    private fun copiarUriParaArquivo(context: Context, uri: Uri, destino: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Não foi possível abrir o arquivo selecionado.")
        input.use { ins -> FileOutputStream(destino).use { out -> ins.copyTo(out) } }
    }

    /**
     * Extração com as travas extras exigidas pra fonte não-confiável (T1.5): zip-slip, nº de
     * entradas, tamanho total extraído, tamanho por arquivo pequeno (model.onnx/voices.bin
     * ficam de fora desse teto — são os únicos legitimamente grandes) e nomes permitidos (raiz
     * restrita ao conjunto exato esperado; dentro de espeak-ng-data/dict, qualquer nome, mesmas
     * travas de tamanho/zip-slip).
     */
    internal fun extrairZipByom(zipFile: File, destino: File) {
        if (destino.exists()) destino.deleteRecursively()
        destino.mkdirs()
        var entradas = 0
        var totalExtraido = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val nome = entry.name
                if (!nomeZipSeguroByom(nome)) {
                    throw IOException("Entrada insegura no zip: '$nome'")
                }
                if (!entry.isDirectory) {
                    entradas++
                    if (entradas > MAX_ENTRADAS) {
                        throw IOException("Zip tem mais de $MAX_ENTRADAS arquivos — rejeitado.")
                    }
                    if (!nomeRaizPermitidoOuDentroDeSubdirDados(nome)) {
                        throw IOException("Arquivo não esperado no pacote: '$nome'")
                    }
                    totalExtraido += extrairArquivoByom(zip, destino, nome, totalExtraido)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (entradas == 0) throw IOException("Zip vazio ou sem arquivos: ${zipFile.name}")
    }

    private fun extrairArquivoByom(
        zip: ZipInputStream, destino: File, nome: String, totalAcumulado: Long
    ): Long {
        val saida = File(destino, nome)
        saida.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val ehArquivoGrandePermitido = nome == "model.onnx" || nome == "voices.bin"
        val tetoArquivo = if (ehArquivoGrandePermitido) MAX_TOTAL_EXTRAIDO_BYTES else MAX_BYTES_ARQUIVO_PEQUENO
        var escritos = 0L
        FileOutputStream(saida).use { out ->
            val buffer = ByteArray(BUFFER_EXTRACAO)
            var lidos: Int
            while (zip.read(buffer).also { lidos = it } != -1) {
                escritos += lidos
                if (escritos > tetoArquivo || totalAcumulado + escritos > MAX_TOTAL_EXTRAIDO_BYTES) {
                    throw IOException("Pacote excede o limite de tamanho permitido.")
                }
                out.write(buffer, 0, lidos)
            }
        }
        return escritos
    }

    private fun nomeZipSeguroByom(nome: String): Boolean =
        !nome.startsWith("/") && !nome.split('/', '\\').any { it == ".." }

    private fun nomeRaizPermitidoOuDentroDeSubdirDados(nome: String): Boolean {
        val partes = nome.split('/')
        if (partes.size == 1) return nome in RAIZ_PERMITIDA || nome.matches(REGEX_LEXICON_KOKORO)
        return partes.first() in SUBDIRS_DADOS
    }

    // ── Manifesto ────────────────────────────────────────────────────────────

    internal fun lerManifesto(dir: File): ManifestoImportado? {
        val arquivo = File(dir, MANIFESTO)
        if (!arquivo.exists()) return null
        return try {
            val json = JSONObject(arquivo.readText(Charsets.UTF_8))
            val arquitetura = json.optString("arquitetura")
            if (arquitetura != "kokoro" && arquitetura != "vits") return null
            val nome = json.optString("nome").trim()
            if (nome.isEmpty()) return null
            val atribuicao = json.optString("atribuicao").trim()
            val vozesJson = json.optJSONArray("vozes") ?: return null
            if (vozesJson.length() == 0) return null
            val vozes = (0 until vozesJson.length()).mapNotNull { i ->
                val v = vozesJson.optJSONObject(i) ?: return@mapNotNull null
                val id = v.optString("id").trim()
                val nomeVoz = v.optString("nome").trim()
                val idioma = v.optString("idioma").trim()
                if (id.isEmpty() || nomeVoz.isEmpty() || idioma.isEmpty()) return@mapNotNull null
                VozImportada(id, nomeVoz, idioma, v.optInt("sid", 0))
            }
            if (vozes.isEmpty()) return null
            val semFonemizacao = json.optBoolean("semFonemizacao", false)
            ManifestoImportado(arquitetura, nome, atribuicao, vozes, semFonemizacao)
        } catch (e: Exception) {
            Log.w(TAG, "manifesto.json malformado: ${e.message}")
            null
        }
    }

    /** Confere se os arquivos que a arquitetura declarada exige existem de verdade e não estão
     * vazios/truncados — null = válido; String = motivo da rejeição. */
    internal fun validarConteudo(dir: File, manifesto: ManifestoImportado): String? {
        fun arquivoOk(nome: String) = File(dir, nome).let { it.exists() && it.length() >= TAMANHO_MIN_ARQUIVO_VALIDO }
        // tokens.txt não segue o piso de TAMANHO_MIN_ARQUIVO_VALIDO: é uma tabela de vocabulário
        // (token→id), não um peso de modelo — um vocabulário por caractere (ex.: MMS-TTS) tem
        // poucas dezenas de entradas e pode legitimamente ficar abaixo de 500 bytes (achado real
        // ao testar o download do MMS-TTS pt-BR em device: tokens.txt de 476 bytes, correto,
        // rejeitado como "incompleto" por este piso). Só precisa existir e não estar vazio.
        fun tokensOk() = File(dir, "tokens.txt").let { it.exists() && it.length() > 0L }

        if (!arquivoOk("model.onnx")) return "model.onnx ausente ou vazio."
        if (!tokensOk()) return "tokens.txt ausente ou vazio."

        val temEspeak = File(dir, "espeak-ng-data").let { it.isDirectory && (it.list()?.isNotEmpty() == true) }

        when (manifesto.arquitetura) {
            "kokoro" -> {
                if (!arquivoOk("voices.bin")) return "voices.bin ausente ou vazio (obrigatório pra arquitetura kokoro)."
                val temLexicon = dir.listFiles { f -> f.name.matches(REGEX_LEXICON_KOKORO) }?.isNotEmpty() == true
                if (!temLexicon && !temEspeak) {
                    return "Faltam dados de fonemização (lexicon-*.txt ou espeak-ng-data/)."
                }
            }
            "vits" -> {
                val temLexicon = arquivoOk("lexicon.txt")
                if (!temLexicon && !temEspeak && !manifesto.semFonemizacao) {
                    return "Faltam dados de fonemização (lexicon.txt ou espeak-ng-data/)."
                }
            }
        }
        return null
    }

    // ── Catálogo (VoiceCatalog / PacoteVozes) ──────────────────────────────────

    private fun construirPacote(
        packId: String, dir: File, manifesto: ManifestoImportado
    ): PacoteVozes {
        val vozes = manifesto.vozes.map { v ->
            val ehPt = v.idioma.startsWith("pt", ignoreCase = true)
            val vozIdInterno = if (ehPt) "${v.idOriginal}-pt" else v.idOriginal
            VoiceOption(
                id = "$packId::$vozIdInterno",
                name = v.nome,
                language = v.idioma,
                isMale = false,
                engine = "kokoro",
                description = "Voz importada (BYOM, ${manifesto.arquitetura}) · ${manifesto.atribuicao}"
                    .trimEnd(' ', '·')
            )
        }
        return PacoteVozes(
            id = packId,
            engine = "kokoro",
            nomeExibicao = manifesto.nome,
            tamanhoDownloadMb = 0,
            vozes = vozes,
            isPronto = { true },
            tamanhoOcupadoBytes = { tamanhoDiretorioByom(dir) },
            download = null,
            delete = { _ ->
                dir.deleteRecursively()
                VoiceCatalog.removerPacoteLocal(packId)
                voiceParamsCache.remove(packId)
            }
        )
    }

    private fun tamanhoDiretorioByom(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Registra novamente um pacote embutido no app após download ou remoção de cache. */
    @Synchronized
    fun rehidratarPacote(context: Context, packId: String) {
        val dir = File(dirImportados(context), packId)
        val manifesto = lerManifesto(dir) ?: return
        if (validarConteudo(dir, manifesto) != null) return
        VoiceCatalog.registrarPacoteLocal(construirPacote(packId, dir, manifesto))
    }

    /** Invalida apenas metadados de um pacote embutido, sem tirá-lo do catálogo. */
    @Synchronized
    fun invalidarCachePacote(packId: String) {
        voiceParamsCache.remove(packId)
    }

    /** Invalida o cache de manifesto de um pacote apagado. */
    @Synchronized
    fun esquecerPacote(packId: String) {
        VoiceCatalog.removerPacoteLocal(packId)
        voiceParamsCache.remove(packId)
    }
    /** Reconstrói o registro de pacotes importados a partir do disco (`filesDir/importados/`)
     * — chamado uma vez no cold start do processo principal ([com.jonjonesbr.audiobookgen.LylyApplication]).
     * O `manifesto.json` validado no import é a ÚNICA fonte persistida (sem SharedPreferences
     * nem Room — segue o mesmo padrão in-memory do resto do [VoiceCatalog], RN-3/T2.1). */
    fun rehydratarTodos(context: Context) {
        val raiz = dirImportados(context)
        val pastas = raiz.listFiles { f -> f.isDirectory } ?: return
        for (dir in pastas) {
            val manifesto = lerManifesto(dir) ?: continue
            if (validarConteudo(dir, manifesto) != null) continue
            VoiceCatalog.registrarPacoteLocal(construirPacote(dir.name, dir, manifesto))
        }
    }

    // ── Resolução de voz (consumida pelo KokoroTtsEngine na síntese) ──────────

    internal data class VozResolvidaByom(
        val dir: File,
        val arquitetura: String,
        val sid: Int,
        val lang: String?,
    )

    private data class PackCache(val dir: File, val manifesto: ManifestoImportado)
    private val voiceParamsCache = mutableMapOf<String, PackCache>()

    /** Resolve um voiceId no formato `<packId>::<vozIdInterno>` pra onde carregar o modelo e
     * qual sid/lang usar. Null = pacote não encontrado/não registrado (ex.: apagado desde a
     * última leitura). */
    @Synchronized
    internal fun resolver(context: Context, voiceId: String): VozResolvidaByom? {
        val partes = voiceId.split("::", limit = 2)
        if (partes.size != 2) return null
        val (packId, vozIdInterno) = partes
        val cache = voiceParamsCache.getOrPut(packId) {
            val dir = File(dirImportados(context), packId)
            val manifesto = lerManifesto(dir) ?: return null
            PackCache(dir, manifesto)
        }
        val voz = cache.manifesto.vozes.firstOrNull { v ->
            val ehPt = v.idioma.startsWith("pt", ignoreCase = true)
            (if (ehPt) "${v.idOriginal}-pt" else v.idOriginal) == vozIdInterno
        } ?: return null
        val lang = if (voz.idioma.startsWith("pt", ignoreCase = true)) "pt-BR" else null
        return VozResolvidaByom(cache.dir, cache.manifesto.arquitetura, voz.sid, lang)
    }

    private const val TAMANHO_HASH_ID = 16
    private const val BUFFER_EXTRACAO = 65_536
    private val REGEX_LEXICON_KOKORO = Regex("^lexicon-[A-Za-z0-9_-]+\\.txt$")
}

/** Marcador de documentação — a API [com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig] foi
 * confirmada via `javap` diretamente no AAR pinado (1.13.7, sherpa-onnx-static-link-onnxruntime)
 * antes de escrever este arquivo: construtor
 * `(model, lexicon, tokens, dataDir, dictDir, noiseScale, noiseScaleW, lengthScale)`, todos com
 * default via Kotlin — mesma forma de uso do `kokoro` em [com.k2fsa.sherpa.onnx.OfflineTtsModelConfig]. */
private object OfflineTtsVitsConfigVerified
