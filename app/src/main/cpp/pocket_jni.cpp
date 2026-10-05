#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
void* ptt_create(const char*, const char*, const char*, const char*, float, int, int, int, int);
void ptt_destroy(void*);
void* ptt_stream_start(void*, const char*, const char*);
int ptt_stream_read(void*, float**, int*);
void ptt_stream_cancel(void*);
void ptt_stream_end(void*);
void ptt_free_audio(float*);
}

namespace {
struct Engine {
    void* tts = nullptr;
    std::atomic<void*> stream{nullptr};
    std::mutex synthMutex;
};

Engine* fromHandle(jlong handle) {
    return reinterpret_cast<Engine*>(handle);
}

void writeU16(FILE* file, std::uint16_t value) {
    const unsigned char bytes[] = {
        static_cast<unsigned char>(value & 0xff),
        static_cast<unsigned char>((value >> 8) & 0xff)
    };
    std::fwrite(bytes, 1, sizeof(bytes), file);
}

void writeU32(FILE* file, std::uint32_t value) {
    const unsigned char bytes[] = {
        static_cast<unsigned char>(value & 0xff),
        static_cast<unsigned char>((value >> 8) & 0xff),
        static_cast<unsigned char>((value >> 16) & 0xff),
        static_cast<unsigned char>((value >> 24) & 0xff)
    };
    std::fwrite(bytes, 1, sizeof(bytes), file);
}

bool writeWavHeader(FILE* file, std::uint32_t dataBytes) {
    if (std::fseek(file, 0, SEEK_SET) != 0) return false;
    std::fwrite("RIFF", 1, 4, file);
    writeU32(file, 36U + dataBytes);
    std::fwrite("WAVEfmt ", 1, 8, file);
    writeU32(file, 16);
    writeU16(file, 1);       // PCM
    writeU16(file, 1);       // mono
    writeU32(file, 24000);   // Pocket TTS sample rate
    writeU32(file, 48000);   // byte rate
    writeU16(file, 2);       // block alignment
    writeU16(file, 16);      // bits per sample
    std::fwrite("data", 1, 4, file);
    writeU32(file, dataBytes);
    return std::ferror(file) == 0;
}

bool writePcm16(
    FILE* file,
    const float* samples,
    int count,
    std::uint64_t* totalBytes) {
    if (count <= 0 || !samples || *totalBytes + static_cast<std::uint64_t>(count) * 2 > UINT32_MAX - 36U) {
        return false;
    }
    std::vector<std::int16_t> pcm(static_cast<std::size_t>(count));
    for (int i = 0; i < count; ++i) {
        const float sample = samples[i];
        const float finite = std::isfinite(sample) ? sample : 0.0f;
        const float clamped = std::clamp(finite, -1.0f, 1.0f);
        const float scaled = clamped < 0.0f ? clamped * 32768.0f : clamped * 32767.0f;
        pcm[static_cast<std::size_t>(i)] = static_cast<std::int16_t>(std::lrint(scaled));
    }
    const auto bytes = static_cast<std::size_t>(count) * sizeof(std::int16_t);
    if (std::fwrite(pcm.data(), 1, bytes, file) != bytes) return false;
    *totalBytes += bytes;
    return true;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativePocketTts_nativeCreate(
    JNIEnv* env, jobject, jstring models, jstring voices, jstring precision,
    jfloat temperature, jint lsdSteps, jint threads, jint sentencePauseMs,
    jint maxTextTokens) {
    const char* modelPath = env->GetStringUTFChars(models, nullptr);
    const char* voicePath = env->GetStringUTFChars(voices, nullptr);
    const char* precisionValue = env->GetStringUTFChars(precision, nullptr);
    if (!modelPath || !voicePath || !precisionValue) {
        if (modelPath) env->ReleaseStringUTFChars(models, modelPath);
        if (voicePath) env->ReleaseStringUTFChars(voices, voicePath);
        if (precisionValue) env->ReleaseStringUTFChars(precision, precisionValue);
        return 0;
    }

    auto* engine = new Engine();
    const std::string tokenizerPath = std::string(modelPath) + "/tokenizer.model";
    engine->tts = ptt_create(
        modelPath, voicePath, tokenizerPath.c_str(), precisionValue, temperature,
        lsdSteps, threads, sentencePauseMs, maxTextTokens);
    env->ReleaseStringUTFChars(models, modelPath);
    env->ReleaseStringUTFChars(voices, voicePath);
    env->ReleaseStringUTFChars(precision, precisionValue);
    if (!engine->tts) {
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativePocketTts_nativeSynthesize(
    JNIEnv* env, jobject, jlong handle, jstring text, jstring voice, jstring outputPath) {
    auto* engine = fromHandle(handle);
    if (!engine || !engine->tts) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(engine->synthMutex);

    const char* utf8Text = env->GetStringUTFChars(text, nullptr);
    const char* utf8Voice = env->GetStringUTFChars(voice, nullptr);
    const char* utf8Output = env->GetStringUTFChars(outputPath, nullptr);
    if (!utf8Text || !utf8Voice || !utf8Output) {
        if (utf8Text) env->ReleaseStringUTFChars(text, utf8Text);
        if (utf8Voice) env->ReleaseStringUTFChars(voice, utf8Voice);
        if (utf8Output) env->ReleaseStringUTFChars(outputPath, utf8Output);
        return JNI_FALSE;
    }
    const std::string outputFile(utf8Output);

    FILE* output = std::fopen(utf8Output, "wb+");
    void* stream = nullptr;
    bool success = output != nullptr;
    std::uint64_t dataBytes = 0;
    if (success) {
        unsigned char emptyHeader[44] = {};
        success = std::fwrite(emptyHeader, 1, sizeof(emptyHeader), output) == sizeof(emptyHeader);
    }
    if (success) {
        stream = ptt_stream_start(engine->tts, utf8Text, utf8Voice);
        success = stream != nullptr;
        engine->stream.store(stream);
    }

    while (success) {
        float* samples = nullptr;
        int count = 0;
        const int state = ptt_stream_read(stream, &samples, &count);
        if (state == 0) break;
        if (state < 0) {
            success = false;
            break;
        }
        success = writePcm16(output, samples, count, &dataBytes);
        ptt_free_audio(samples);
    }

    engine->stream.store(nullptr);
    if (stream) ptt_stream_end(stream);
    if (output) {
        if (success && dataBytes > 0) success = writeWavHeader(output, static_cast<std::uint32_t>(dataBytes));
        success = std::fflush(output) == 0 && success;
        std::fclose(output);
    }
    if (!success || dataBytes == 0) {
        std::remove(outputFile.c_str());
        env->ReleaseStringUTFChars(text, utf8Text);
        env->ReleaseStringUTFChars(voice, utf8Voice);
        env->ReleaseStringUTFChars(outputPath, utf8Output);
        return JNI_FALSE;
    }
    env->ReleaseStringUTFChars(text, utf8Text);
    env->ReleaseStringUTFChars(voice, utf8Voice);
    env->ReleaseStringUTFChars(outputPath, utf8Output);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativePocketTts_nativeStop(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (engine) {
        if (void* stream = engine->stream.load()) ptt_stream_cancel(stream);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativePocketTts_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    if (void* stream = engine->stream.load()) ptt_stream_cancel(stream);
    std::lock_guard<std::mutex> guard(engine->synthMutex);
    ptt_destroy(engine->tts);
    delete engine;
}
