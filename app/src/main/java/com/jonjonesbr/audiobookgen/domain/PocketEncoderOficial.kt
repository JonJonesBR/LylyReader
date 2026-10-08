package com.jonjonesbr.audiobookgen.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Arquivo oficial da Kyutai que o próprio usuário baixa (com a conta dele, depois de aceitar os
 * termos) para liberar a clonagem. A revisão fica fixada porque o codificador precisa combinar com
 * os pacotes Pocket do app; o SHA-256 confere que o arquivo é exatamente o oficial.
 */
data class ArquivoOficialPocket(
    val idioma: String,
    val pasta: String,
    val revisao: String,
    val sha256: String,
    val tamanho: Long
) {
    /** Link direto do arquivo nessa revisão; aberto no navegador, onde o usuário está logado. */
    val urlDownload: String
        get() = "https://huggingface.co/kyutai/pocket-tts/resolve/$revisao/languages/$pasta/model.safetensors?download=true"
}

/** Um peso do codificador: onde ele está no arquivo oficial e como entra no ONNX. */
data class PesoMapeado(val nome: String, val chave: String, val transposto: Boolean, val forma: List<Int>)

/** Um tensor do cabeçalho safetensors (posições relativas ao fim do cabeçalho). */
data class TensorSafetensors(val dtype: String, val forma: List<Int>, val inicio: Long, val fim: Long)

object PocketEncoderOficial {
    const val PAGINA_TERMOS = "https://huggingface.co/kyutai/pocket-tts"
    const val PAGINA_CONTA = "https://huggingface.co/join"
    private const val TAMANHO_MAXIMO_CABECALHO = 16L * 1024L * 1024L

    /** Revisões validadas: o codificador montado delas é idêntico ao que o app já usava. */
    val ARQUIVOS = listOf(
        ArquivoOficialPocket(
            "pt-BR", "portuguese", "2dd944b099d06bb9edbd221fef138685711d9bf0",
            "0b935f2a99f8a7823371e52d03360849b4aefbc08f3b8c592df038644ad0c5b0", 219_029_196L
        ),
        ArquivoOficialPocket(
            "en-US", "english", "983151f13aaeab1b13c1e5e3c2c383d49a9edf3f",
            "fb0dc01b0d4d2e1c905b7a3e0676e3d9c96d5ae460e24e3ab94981805babf997", 219_029_196L
        ),
        ArquivoOficialPocket(
            "es-ES", "spanish", "2dd944b099d06bb9edbd221fef138685711d9bf0",
            "9a1b79f0d6d506bcbae6d8fc69bad899146b609173c624dc1e816353587c8995", 219_029_196L
        )
    )

    fun porSha256(hex: String): ArquivoOficialPocket? = ARQUIVOS.firstOrNull { it.sha256.equals(hex, ignoreCase = true) }

    /** "pt-BR", "pt" ou "en-US" → arquivo do idioma; idioma desconhecido cai no português. */
    fun porIdioma(tag: String): ArquivoOficialPocket {
        val codigo = tag.substringBefore('-').lowercase()
        return ARQUIVOS.firstOrNull { it.idioma.substringBefore('-') == codigo } ?: ARQUIVOS.first()
    }

    /** Tamanho desconhecido passa (a conferência do hash decide); tamanho diferente já descarta. */
    fun tamanhoPlausivel(tamanho: Long?): Boolean = tamanho == null || tamanho <= 0 || ARQUIVOS.any { it.tamanho == tamanho }

    /** Os 8 primeiros bytes do safetensors dizem o tamanho do cabeçalho JSON (little-endian). */
    fun tamanhoCabecalho(primeiros8: ByteArray): Long {
        require(primeiros8.size >= 8)
        var valor = 0L
        for (i in 7 downTo 0) valor = (valor shl 8) or (primeiros8[i].toLong() and 0xFF)
        require(valor in 2..TAMANHO_MAXIMO_CABECALHO) { "Cabeçalho inválido" }
        return valor
    }

    fun lerCabecalho(json: String): Map<String, TensorSafetensors> {
        val raiz = JSONObject(json)
        val tensores = LinkedHashMap<String, TensorSafetensors>()
        for (chave in raiz.keys()) {
            if (chave == "__metadata__") continue
            val info = raiz.getJSONObject(chave)
            val forma = info.getJSONArray("shape").let { a -> List(a.length()) { a.getInt(it) } }
            val posicoes = info.getJSONArray("data_offsets")
            tensores[chave] = TensorSafetensors(info.getString("dtype"), forma, posicoes.getLong(0), posicoes.getLong(1))
        }
        return tensores
    }

    fun lerMapa(json: String): List<PesoMapeado> {
        val lista = JSONArray(json)
        return List(lista.length()) { i ->
            val o = lista.getJSONObject(i)
            val forma = o.getJSONArray("forma").let { a -> List(a.length()) { a.getInt(it) } }
            PesoMapeado(o.getString("nome"), o.getString("chave"), o.getBoolean("transposto"), forma)
        }
    }

    /** Confere se o tensor do arquivo tem o tipo e a forma que o mapa espera. */
    fun compativel(peso: PesoMapeado, tensor: TensorSafetensors): Boolean {
        val formaOrigem = if (peso.transposto) peso.forma.reversed() else peso.forma
        val bytes = formaOrigem.fold(1L) { acc, d -> acc * d } * 2
        return tensor.dtype == "BF16" && tensor.forma == formaOrigem && tensor.fim - tensor.inicio == bytes
    }

    /**
     * BF16 → FP32 (little-endian): os 16 bits do BF16 viram os 16 bits altos do FP32, então a
     * conversão é exata. Com [transposto], a matriz [linhas x colunas] sai como [colunas x linhas].
     */
    fun bf16ParaFp32(origem: ByteArray, formaOrigem: List<Int>, transposto: Boolean): ByteArray {
        val total = origem.size / 2
        val destino = ByteArray(total * 4)
        if (!transposto) {
            for (k in 0 until total) {
                destino[4 * k + 2] = origem[2 * k]
                destino[4 * k + 3] = origem[2 * k + 1]
            }
            return destino
        }
        require(formaOrigem.size == 2) { "Só matrizes podem ser transpostas" }
        val linhas = formaOrigem[0]
        val colunas = formaOrigem[1]
        for (i in 0 until linhas) {
            for (j in 0 until colunas) {
                val de = i * colunas + j
                val para = j * linhas + i
                destino[4 * para + 2] = origem[2 * de]
                destino[4 * para + 3] = origem[2 * de + 1]
            }
        }
        return destino
    }

    /**
     * Pedaço de ONNX com um peso: ModelProto{ graph{ initializer{ dims, data_type=FLOAT, name, raw_data } } }.
     * Mensagens protobuf concatenadas se fundem, então planta + pedaços = modelo completo.
     */
    fun fragmentoInicializador(nome: String, forma: List<Int>, fp32: ByteArray): ByteArray {
        val tensor = java.io.ByteArrayOutputStream()
        forma.forEach { tensor.write(campoVarint(1, it.toLong())) }
        tensor.write(campoVarint(2, 1))
        tensor.write(campoBytes(8, nome.toByteArray(Charsets.UTF_8)))
        tensor.write(campoBytes(9, fp32))
        return campoBytes(7, campoBytes(5, tensor.toByteArray()))
    }

    /** O arquivo não é um safetensors com os pesos esperados (outro arquivo, download cortado...). */
    class ArquivoInvalidoException : java.io.IOException("arquivo não é o safetensors esperado")

    /** Hash do arquivo inteiro e os bytes BF16 de cada peso que o codificador usa. */
    class PesosLidos(val sha256: String, val pesos: Map<String, ByteArray>)

    /**
     * Lê o arquivo uma única vez, do começo ao fim: calcula o SHA-256 e guarda só os bytes dos pesos
     * do [mapa] (cerca de 20 MB dos 219 MB). [progresso] recebe de 0 a 0,9 conforme a leitura.
     */
    fun lerPesos(
        entrada: java.io.InputStream,
        mapa: List<PesoMapeado>,
        tamanhoTotal: Long,
        bloco: Int = 256 * 1024,
        progresso: (Float) -> Unit = {}
    ): PesosLidos {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val tamanhoCab = runCatching { tamanhoCabecalho(lerExato(entrada, 8, digest)) }
            .getOrElse { throw ArquivoInvalidoException() }
        val cabecalho = runCatching { lerCabecalho(String(lerExato(entrada, tamanhoCab.toInt(), digest), Charsets.UTF_8)) }
            .getOrElse { throw ArquivoInvalidoException() }
        val base = 8 + tamanhoCab
        val faixas = mapa.map { peso ->
            val t = cabecalho[peso.chave] ?: throw ArquivoInvalidoException()
            if (!compativel(peso, t)) throw ArquivoInvalidoException()
            Triple(peso.chave, base + t.inicio, base + t.fim)
        }.distinctBy { it.first }.sortedBy { it.second }
        val pesos = HashMap<String, ByteArray>()
        faixas.forEach { (chave, ini, fim) -> pesos[chave] = ByteArray((fim - ini).toInt()) }

        var posicao = base
        var proxima = 0
        val buffer = ByteArray(bloco)
        var ultimoAviso = -1
        while (true) {
            val lidos = entrada.read(buffer)
            if (lidos < 0) break
            if (lidos == 0) continue
            digest.update(buffer, 0, lidos)
            val fimBloco = posicao + lidos
            while (proxima < faixas.size && faixas[proxima].second < fimBloco) {
                val (chave, ini, fim) = faixas[proxima]
                val de = maxOf(ini, posicao)
                val ate = minOf(fim, fimBloco)
                if (de < ate) {
                    System.arraycopy(buffer, (de - posicao).toInt(), pesos.getValue(chave), (de - ini).toInt(), (ate - de).toInt())
                }
                if (fim <= fimBloco) proxima++ else break
            }
            posicao = fimBloco
            if (tamanhoTotal > 0) {
                val pct = (posicao * 90 / tamanhoTotal).toInt()
                if (pct != ultimoAviso) {
                    ultimoAviso = pct
                    progresso(pct / 100f)
                }
            }
        }
        if (proxima < faixas.size) throw ArquivoInvalidoException()
        return PesosLidos(digest.digest().joinToString("") { "%02x".format(it) }, pesos)
    }

    /** Grava o codificador: a planta (sem pesos) seguida de um pedaço por peso, na ordem do mapa. */
    fun escreverOnnx(planta: java.io.InputStream, mapa: List<PesoMapeado>, pesos: Map<String, ByteArray>, saida: java.io.OutputStream) {
        planta.copyTo(saida)
        for (peso in mapa) {
            val formaOrigem = if (peso.transposto) peso.forma.reversed() else peso.forma
            val fp32 = bf16ParaFp32(pesos.getValue(peso.chave), formaOrigem, peso.transposto)
            saida.write(fragmentoInicializador(peso.nome, peso.forma, fp32))
        }
    }

    private fun lerExato(entrada: java.io.InputStream, n: Int, digest: java.security.MessageDigest): ByteArray {
        val dados = ByteArray(n)
        var lidos = 0
        while (lidos < n) {
            val r = entrada.read(dados, lidos, n - lidos)
            if (r < 0) throw ArquivoInvalidoException()
            lidos += r
        }
        digest.update(dados)
        return dados
    }

    fun varint(valor: Long): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        var v = valor
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) saida.write(b or 0x80) else {
                saida.write(b)
                return saida.toByteArray()
            }
        }
    }

    private fun campoVarint(numero: Int, valor: Long): ByteArray = varint((numero shl 3).toLong()) + varint(valor)

    private fun campoBytes(numero: Int, dados: ByteArray): ByteArray =
        varint(((numero shl 3) or 2).toLong()) + varint(dados.size.toLong()) + dados
}
