package com.jonjonesbr.audiobookgen.tts

/** Thin wrapper over the Pocket TTS C API. Only instantiated in the isolated :pocket process. */
internal class NativePocketTts(
    modelsDir: String,
    voicesDir: String,
    precision: String = "int8",
    temperature: Float = 0.3f,
    lsdSteps: Int = 1,
    threads: Int = 2,
    sentencePauseMs: Int = 0,
    // Match pocket_tts.default_parameters.MAX_TOKEN_PER_CHUNK. The official
    // runtime keeps chunks short because long inputs can cause skipped words.
    maxTextTokens: Int = 50
) : AutoCloseable {
    private var handle: Long

    init {
        System.loadLibrary("pockettts_jni")
        handle = nativeCreate(
            modelsDir,
            voicesDir,
            precision,
            temperature,
            lsdSteps,
            threads,
            sentencePauseMs,
            maxTextTokens
        )
        check(handle != 0L) { "Não foi possível carregar o modelo Pocket TTS." }
    }

    @Synchronized
    fun synthesize(text: String, voicePath: String, outputPath: String): Boolean =
        handle != 0L && nativeSynthesize(handle, text, voicePath, outputPath)

    fun stop() {
        if (handle != 0L) nativeStop(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(
        modelsDir: String,
        voicesDir: String,
        precision: String,
        temperature: Float,
        lsdSteps: Int,
        threads: Int,
        sentencePauseMs: Int,
        maxTextTokens: Int
    ): Long

    private external fun nativeSynthesize(
        handle: Long,
        text: String,
        voicePath: String,
        outputPath: String
    ): Boolean

    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
