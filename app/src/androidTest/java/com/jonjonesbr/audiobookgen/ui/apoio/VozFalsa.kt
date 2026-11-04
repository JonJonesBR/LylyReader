package com.jonjonesbr.audiobookgen.ui.apoio

import android.content.Context
import com.jonjonesbr.audiobookgen.tts.SilentWav
import com.jonjonesbr.audiobookgen.tts.TtsSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Voz de mentira para os testes de interface (rb-46): não usa motor nenhum. Cada trecho vira um WAV de silêncio em
 * cacheDir/voz_falsa/, com duração de [msPorCaractere] por caractere (entre [minMs] e [maxMs]; com 0 a duração é
 * sempre [minMs]). Os textos pedidos ficam em [textosPedidos], para o teste conferir o que a leitura guiada mandou
 * sintetizar.
 *
 * [taxaDeAmostragem] é a do WAV (padrão a do [SilentWav]). Com trechos de minutos, a taxa padrão estoura o Int de
 * [SilentWav.bytes] (acima de ~89 s a 24 kHz): a voz muito longa usa 8 kHz.
 *
 * Entra no app pela costura [TtsSynthesizer.substitutoParaTestes], que a [RegrasDoLeitor] preenche.
 */
class VozFalsa(
    context: Context,
    private val msPorCaractere: Double,
    private val minMs: Int,
    private val maxMs: Int,
    private val taxaDeAmostragem: Int = SilentWav.SAMPLE_RATE,
) : TtsSynthesizer {

    private val pasta: File = File(context.cacheDir, "voz_falsa").apply { mkdirs() }

    /** Textos pedidos a [synthesize], na ordem em que chegaram. */
    val textosPedidos: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun synthesize(texto: String, voz: String, ritmo: Int): String? {
        textosPedidos += texto
        val ms = (texto.length * msPorCaractere).toInt().coerceIn(minMs, maxMs)
        return withContext(Dispatchers.IO) {
            // A taxa entra no nome: a mesma pasta pode ter o mesmo trecho em duas taxas.
            val arquivo = File(pasta, "${Integer.toHexString(texto.hashCode())}_${ms}_$taxaDeAmostragem.wav")
            gravarSeFaltar(arquivo, ms)
            arquivo.absolutePath
        }
    }

    /**
     * Grava o silêncio de [ms] em [arquivo] de forma atômica: escreve num temporário e renomeia. Assim um
     * MediaPlayer nunca abre o arquivo pela metade, mesmo que dois pedidos do mesmo trecho cheguem juntos. Se o
     * arquivo já estiver completo (mesmo tamanho), não grava de novo.
     */
    private fun gravarSeFaltar(arquivo: File, ms: Int) {
        val bytes = SilentWav.bytes(ms, taxaDeAmostragem)
        if (arquivo.length() == bytes.size.toLong()) return
        val temporario = File(pasta, "${arquivo.name}.${Thread.currentThread().id}.tmp")
        temporario.writeBytes(bytes)
        temporario.renameTo(arquivo)
    }

    companion object {
        /** Curta: cerca de 1,5 s por trecho, qualquer que seja o tamanho do texto. */
        fun curta(context: Context): VozFalsa =
            VozFalsa(context, msPorCaractere = 0.0, minMs = 1_500, maxMs = 1_500)

        /** Longa: 30 s fixos por trecho, para o teste ver a guiada tocando e pausando sem avançar. */
        fun longa(context: Context): VozFalsa =
            VozFalsa(context, msPorCaractere = 0.0, minMs = 30_000, maxMs = 30_000)

        /**
         * Muito longa: 240 s fixos por trecho (rb-46 U4). Cada cenário com a guiada parada leva ~45 s, e a voz de
         * 30 s acabava antes de o cenário terminar. A 8 kHz, 240 s ainda cabem no Int de [SilentWav.bytes].
         */
        fun muitoLonga(context: Context): VozFalsa =
            VozFalsa(
                context,
                msPorCaractere = 0.0,
                minMs = 240_000,
                maxMs = 240_000,
                taxaDeAmostragem = TAXA_DA_VOZ_MUITO_LONGA,
            )

        /** Taxa do WAV da voz muito longa: 8 kHz, para 240 s não estourar o Int de [SilentWav.bytes]. */
        private const val TAXA_DA_VOZ_MUITO_LONGA = 8_000
    }
}
