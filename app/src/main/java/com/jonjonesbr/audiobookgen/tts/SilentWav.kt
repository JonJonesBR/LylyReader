package com.jonjonesbr.audiobookgen.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** WAV PCM 16-bit mono só de silêncio, para trechos sem nada falável (ex.: quebra de cena "***"). */
internal object SilentWav {
    const val SAMPLE_RATE = 24_000
    private const val HEADER_BYTES = 44
    private const val BYTES_PER_SAMPLE = 2

    fun bytes(durationMs: Int, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val samples = sampleRate * durationMs / 1000
        val dataSize = samples * BYTES_PER_SAMPLE
        return ByteBuffer.allocate(HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)                                   // PCM
            putShort(1)                                   // mono
            putInt(sampleRate)
            putInt(sampleRate * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())          // block align
            putShort(16)                                  // bits por amostra
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }
}
