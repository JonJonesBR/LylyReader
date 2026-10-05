package com.jonjonesbr.audiobookgen.domain

import kotlin.math.ceil
import kotlin.math.log10

/**
 * Divisão do áudio em partes para o YouTube (até ~12 h por vídeo), cortando nos silêncios da
 * narração. Tudo aqui é lógica pura (sem MediaCodec): o detector de silêncio, a escolha do corte e
 * o roteamento dos pacotes AAC entre partes — o [VideoExportUseCase] só liga isso aos codecs.
 */
enum class ModoDivisao { MAXIMO, PARTES_IGUAIS }

internal data class Silencio(val inicioUs: Long, val fimUs: Long) {
    val meioUs: Long get() = (inicioUs + fimUs) / 2
}

private const val US_POR_SEGUNDO = 1_000_000L
private const val JANELA_ENERGIA_US = 20_000L
private const val SILENCIO_MINIMO_US = 400_000L
private const val PISO_DB = -120.0
private const val ESCALA_PCM16 = 32768.0
internal val LIMIARES_SILENCIO_DB = doubleArrayOf(-35.0, -30.0, -25.0)
internal const val JANELA_BUSCA_CORTE_US = 15L * 60 * US_POR_SEGUNDO
internal const val PARTE_MINIMA_US = 60L * US_POR_SEGUNDO

/** Fim da parte que começa em [inicioParteUs], ou null se o que sobra já cabe numa parte só. */
internal fun proximoLimiteUs(inicioParteUs: Long, duracaoTotalUs: Long, maxParteUs: Long, modo: ModoDivisao): Long? {
    if (duracaoTotalUs - inicioParteUs <= maxParteUs) return null
    val alvo = when (modo) {
        ModoDivisao.MAXIMO -> maxParteUs
        ModoDivisao.PARTES_IGUAIS -> {
            val partes = ceil(duracaoTotalUs.toDouble() / maxParteUs).toLong()
            ceil(duracaoTotalUs.toDouble() / partes).toLong()
        }
    }
    return inicioParteUs + alvo
}

/**
 * Corte no fim de capítulo mais próximo do limite (se houver na janela) ou no meio do silêncio mais próximo do limite (dentro da janela antes dele). Os limiares são
 * tentados do mais rígido ao mais frouxo; sem silêncio algum, corta no próprio limite.
 */
internal fun escolherCorteUs(
    silenciosPorLimiar: List<List<Silencio>>,
    limiteUs: Long,
    inicioParteUs: Long,
    janelaUs: Long = JANELA_BUSCA_CORTE_US,
    fronteirasUs: List<Long> = emptyList()
): Long {
    val minimo = maxOf(limiteUs - janelaUs, inicioParteUs + PARTE_MINIMA_US)
    // Fim de capítulo é um corte melhor que qualquer silêncio detectado.
    fronteirasUs.filter { it in minimo..limiteUs }.maxOrNull()?.let { return it }
    for (silencios in silenciosPorLimiar) {
        val melhor = silencios.map { it.meioUs }.filter { it in minimo..limiteUs }.maxOrNull()
        if (melhor != null) return melhor
    }
    return limiteUs
}

/** Detecta pausas em janelas de 20 ms de PCM 16 bits; guarda as que duram ao menos 0,4 s. */
internal class AnalisadorDeSilencios {
    private val limiares = LIMIARES_SILENCIO_DB
    private val encontrados = List(limiares.size) { mutableListOf<Silencio>() }
    private val inicioAberto = LongArray(limiares.size) { -1L }
    private var quadrosTotais = 0L
    private var quadrosNaJanela = 0
    private var somaQuadrados = 0.0

    fun silenciosPorLimiar(): List<List<Silencio>> = encontrados

    fun alimentar(pcm: ShortArray, canais: Int, taxa: Int) {
        if (canais <= 0 || taxa <= 0) return
        val quadrosPorJanela = (taxa * JANELA_ENERGIA_US / US_POR_SEGUNDO).toInt().coerceAtLeast(1)
        var i = 0
        while (i + canais <= pcm.size) {
            var soma = 0.0
            for (c in 0 until canais) {
                val v = pcm[i + c] / ESCALA_PCM16
                soma += v * v
            }
            somaQuadrados += soma / canais
            quadrosNaJanela++
            quadrosTotais++
            if (quadrosNaJanela == quadrosPorJanela) fecharJanela(taxa)
            i += canais
        }
    }

    /** Encerra silêncios ainda abertos no fim do áudio. */
    fun finalizar(taxa: Int) {
        if (taxa <= 0) return
        val fim = quadrosTotais * US_POR_SEGUNDO / taxa
        for (k in limiares.indices) fecharSilencio(k, fim)
    }

    /** Esquece silêncios muito antigos (o áudio de várias horas não precisa guardar todos). */
    fun descartarAntesDe(us: Long) {
        for (lista in encontrados) lista.removeAll { it.fimUs < us }
    }

    private fun fecharJanela(taxa: Int) {
        val rms = kotlin.math.sqrt(somaQuadrados / quadrosNaJanela)
        val db = if (rms <= 0.0) PISO_DB else maxOf(PISO_DB, 20 * log10(rms))
        val fimUs = quadrosTotais * US_POR_SEGUNDO / taxa
        val inicioUs = fimUs - JANELA_ENERGIA_US
        for (k in limiares.indices) {
            if (db < limiares[k]) {
                if (inicioAberto[k] < 0) inicioAberto[k] = inicioUs
            } else {
                fecharSilencio(k, inicioUs)
            }
        }
        quadrosNaJanela = 0
        somaQuadrados = 0.0
    }

    private fun fecharSilencio(k: Int, fimUs: Long) {
        val inicio = inicioAberto[k]
        if (inicio >= 0 && fimUs - inicio >= SILENCIO_MINIMO_US) encontrados[k].add(Silencio(inicio, fimUs))
        inicioAberto[k] = -1L
    }
}

internal interface SaidaDeParte {
    /** [amostra] já vem com o tempo relativo ao início da parte. */
    fun gravarAudio(amostra: AmostraCodificada)
    fun finalizar(duracaoUs: Long)
    fun liberar()
}

/**
 * Encaminha os pacotes AAC para as partes. Perto do limite de cada parte os pacotes ficam retidos
 * (~15 min ≈ 7 MB a 64 kbps) até haver silêncio suficiente para escolher o corte; a parte seguinte
 * começa com os pacotes retidos, com o tempo deslocado.
 */
internal class DivisorDePartes(
    private val duracaoTotalUs: Long,
    private val maxParteUs: Long,
    private val modo: ModoDivisao,
    private val silencios: () -> List<List<Silencio>>,
    private val criarSaida: (indice: Int) -> SaidaDeParte,
    private val janelaUs: Long = minOf(JANELA_BUSCA_CORTE_US, maxParteUs / 2),
    private val fronteirasUs: List<Long> = emptyList()
) {
    var partesGravadas = 0
        private set
    private var saida: SaidaDeParte? = null
    private var inicioParteUs = 0L
    private var limiteUs: Long? = proximoLimiteUs(0L, duracaoTotalUs, maxParteUs, modo)
    private val retidas = ArrayList<AmostraCodificada>()

    fun receber(amostra: AmostraCodificada) {
        val limite = limiteUs
        when {
            limite == null || amostra.presentationTimeUs < limite - janelaUs -> escrever(amostra)
            amostra.presentationTimeUs < limite -> retidas.add(amostra)
            else -> cortar(limite, amostra)
        }
    }

    /** Fecha a última parte; [duracaoRealUs] é a duração efetivamente decodificada. */
    fun finalizar(duracaoRealUs: Long = duracaoTotalUs) {
        retidas.forEach { escrever(it) }
        retidas.clear()
        fecharParte(maxOf(duracaoRealUs - inicioParteUs, 1L))
    }

    fun liberar() {
        saida?.liberar()
        saida = null
    }

    private fun cortar(limite: Long, amostra: AmostraCodificada) {
        val corte = escolherCorteUs(silencios(), limite, inicioParteUs, janelaUs, fronteirasUs)
        val pendentes = ArrayList<AmostraCodificada>(retidas.size + 1).apply { addAll(retidas); add(amostra) }
        retidas.clear()
        val (antes, depois) = pendentes.partition { it.presentationTimeUs < corte }
        antes.forEach { escrever(it) }
        fecharParte(corte - inicioParteUs)
        inicioParteUs = corte
        limiteUs = proximoLimiteUs(corte, duracaoTotalUs, maxParteUs, modo)
        depois.forEach { receber(it) }
    }

    private fun escrever(amostra: AmostraCodificada) {
        val atual = saida ?: criarSaida(partesGravadas).also { saida = it }
        atual.gravarAudio(amostra.copy(presentationTimeUs = amostra.presentationTimeUs - inicioParteUs))
    }

    private fun fecharParte(duracaoUs: Long) {
        val atual = saida ?: return
        atual.finalizar(duracaoUs)
        atual.liberar()
        saida = null
        partesGravadas++
    }
}
