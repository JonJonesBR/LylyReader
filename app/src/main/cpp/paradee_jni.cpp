#include <jni.h>
#include <android/log.h>

#include <espeak-ng/espeak_ng.h>
#include <espeak-ng/speak_lib.h>
#include <onnxruntime_cxx_api.h>

#include <cstdint>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "ParadeeJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// espeak-ng não é thread-safe: todas as chamadas ao espeak passam por este mutex.
std::mutex gEspeakMutex;
bool gEspeakPronto = false;
std::string gEspeakDir;
std::string gEspeakVoz;

// Limite de segurança para o laço de cláusulas do espeak.
constexpr int kLimiteClausulas = 10000;

struct Sessao {
    std::unique_ptr<Ort::Session> sessao;
    bool temVoz = false;
};

std::string paraString(JNIEnv* env, jstring texto) {
    if (!texto) return std::string();
    const char* c = env->GetStringUTFChars(texto, nullptr);
    if (!c) return std::string();
    std::string resultado(c);
    env->ReleaseStringUTFChars(texto, c);
    return resultado;
}

Ort::Env& ambienteOrt() {
    static Ort::Env ambiente(ORT_LOGGING_LEVEL_WARNING, "paradee");
    return ambiente;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativeParadee_espeakIniciar(
    JNIEnv* env, jclass, jstring dir, jstring voz) {
    const std::string dirAtual = paraString(env, dir);
    const std::string vozAtual = paraString(env, voz);
    std::string erro;
    try {
        std::lock_guard<std::mutex> guard(gEspeakMutex);
        if (!gEspeakPronto || dirAtual != gEspeakDir || vozAtual != gEspeakVoz) {
            gEspeakPronto = false;
            // API espeak_ng_* direta: o espeak_Initialize clássico devolve a taxa de amostragem
            // mesmo quando os dados faltam (só imprime o erro), então não serve para detectar falha.
            espeak_ng_InitializePath(dirAtual.c_str());
            espeak_ng_ERROR_CONTEXT contexto = nullptr;
            const espeak_ng_STATUS status = espeak_ng_Initialize(&contexto);
            espeak_ng_ClearErrorContext(&contexto);
            if (status != ENS_OK) {
                erro = "espeak_ng_Initialize falhou (" + std::to_string(static_cast<int>(status)) +
                       ") com dados em " + dirAtual;
            } else if (espeak_ng_InitializeOutput(ENOUTPUT_MODE_SYNCHRONOUS, 0, nullptr) != ENS_OK) {
                erro = "espeak_ng_InitializeOutput falhou";
            } else if (espeak_SetVoiceByName(vozAtual.c_str()) != EE_OK) {
                erro = "voz do espeak inválida: " + vozAtual;
            } else {
                gEspeakPronto = true;
                gEspeakDir = dirAtual;
                gEspeakVoz = vozAtual;
            }
        }
    } catch (const std::exception& e) {
        erro = std::string("exceção no espeak: ") + e.what();
    } catch (...) {
        erro = "exceção desconhecida no espeak";
    }
    if (!erro.empty()) LOGE("%s", erro.c_str());
    return env->NewStringUTF(erro.c_str());  // "" = sucesso
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativeParadee_espeakFonemas(
    JNIEnv* env, jclass, jbyteArray texto) {
    if (!texto) return nullptr;
    const jsize tamanho = env->GetArrayLength(texto);
    std::vector<char> entrada(static_cast<std::size_t>(tamanho) + 1, '\0');
    env->GetByteArrayRegion(texto, 0, tamanho, reinterpret_cast<jbyte*>(entrada.data()));

    std::string saida;
    try {
        std::lock_guard<std::mutex> guard(gEspeakMutex);
        if (!gEspeakPronto) {
            LOGE("espeakFonemas chamado antes de espeakIniciar com sucesso");
            return nullptr;
        }
        const void* ponteiro = entrada.data();
        for (int i = 0; ponteiro != nullptr && i < kLimiteClausulas; ++i) {
            const char* clausula = espeak_TextToPhonemes(
                &ponteiro, espeakCHARS_UTF8,
                espeakPHONEMES_IPA | espeakPHONEMES_TIE | ('^' << 8));
            if (clausula && *clausula) {
                if (!saida.empty()) saida += ' ';
                saida += clausula;
            }
        }
    } catch (const std::exception& e) {
        LOGE("exceção em espeakFonemas: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("exceção desconhecida em espeakFonemas");
        return nullptr;
    }

    jbyteArray resultado = env->NewByteArray(static_cast<jsize>(saida.size()));
    if (resultado && !saida.empty()) {
        env->SetByteArrayRegion(
            resultado, 0, static_cast<jsize>(saida.size()),
            reinterpret_cast<const jbyte*>(saida.data()));
    }
    return resultado;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativeParadee_criarSessao(
    JNIEnv* env, jclass, jstring modelo, jint threads) {
    const std::string caminho = paraString(env, modelo);
    try {
        Ort::SessionOptions opcoes;
        opcoes.SetIntraOpNumThreads(threads > 0 ? threads : 1);
        opcoes.SetInterOpNumThreads(1);
        opcoes.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        // Sem o pool (arena) de memória: com entradas de tamanho variável ele só cresce (passou de 1,6 GB
        // no Redmi). Sem ele o processo volta a ~100 MB entre frases; a síntese fica ~30% mais lenta
        // (RTF ~0,10), ainda 10× mais rápida que o tempo real.
        opcoes.DisableCpuMemArena();

        auto handle = std::make_unique<Sessao>();
        handle->sessao = std::make_unique<Ort::Session>(ambienteOrt(), caminho.c_str(), opcoes);

        Ort::AllocatorWithDefaultOptions alocador;
        const std::size_t entradas = handle->sessao->GetInputCount();
        for (std::size_t i = 0; i < entradas; ++i) {
            Ort::AllocatedStringPtr nome = handle->sessao->GetInputNameAllocated(i, alocador);
            if (std::strcmp(nome.get(), "voice") == 0) handle->temVoz = true;
        }
        return reinterpret_cast<jlong>(handle.release());
    } catch (const Ort::Exception& e) {
        LOGE("ORT não criou a sessão de %s: %s", caminho.c_str(), e.what());
        return 0;
    } catch (const std::exception& e) {
        LOGE("exceção ao criar a sessão de %s: %s", caminho.c_str(), e.what());
        return 0;
    } catch (...) {
        LOGE("exceção desconhecida ao criar a sessão de %s", caminho.c_str());
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativeParadee_sintetizar(
    JNIEnv* env, jclass, jlong h, jlongArray ids, jfloat velocidade, jint voz) {
    auto* sessao = reinterpret_cast<Sessao*>(h);
    if (!sessao || !sessao->sessao || !ids) return nullptr;
    const jsize tamanho = env->GetArrayLength(ids);
    if (tamanho <= 0) return nullptr;

    std::vector<jlong> idsJni(static_cast<std::size_t>(tamanho));
    env->GetLongArrayRegion(ids, 0, tamanho, idsJni.data());
    std::vector<int64_t> tokens(idsJni.begin(), idsJni.end());

    try {
        Ort::MemoryInfo memoria = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        const int64_t formaIds[] = {1, static_cast<int64_t>(tokens.size())};
        const int64_t formaEscalar[] = {1};
        float velocidadeValor = velocidade;
        int64_t vozValor = voz;

        std::vector<const char*> nomesEntrada = {"input_ids", "speed"};
        std::vector<Ort::Value> entradas;
        entradas.push_back(Ort::Value::CreateTensor<int64_t>(
            memoria, tokens.data(), tokens.size(), formaIds, 2));
        entradas.push_back(Ort::Value::CreateTensor<float>(
            memoria, &velocidadeValor, 1, formaEscalar, 1));
        if (sessao->temVoz) {
            nomesEntrada.push_back("voice");
            entradas.push_back(Ort::Value::CreateTensor<int64_t>(
                memoria, &vozValor, 1, formaEscalar, 1));
        }

        const char* nomesSaida[] = {"waveform"};
        Ort::RunOptions opcoesRun;
        std::vector<Ort::Value> saidas = sessao->sessao->Run(
            opcoesRun, nomesEntrada.data(), entradas.data(), entradas.size(), nomesSaida, 1);
        if (saidas.empty() || !saidas[0].IsTensor()) return nullptr;

        const float* dados = saidas[0].GetTensorData<float>();
        const std::size_t total = saidas[0].GetTensorTypeAndShapeInfo().GetElementCount();
        jfloatArray resultado = env->NewFloatArray(static_cast<jsize>(total));
        if (resultado && total > 0) {
            env->SetFloatArrayRegion(resultado, 0, static_cast<jsize>(total), dados);
        }
        return resultado;
    } catch (const Ort::Exception& e) {
        LOGE("ORT falhou em sintetizar: %s", e.what());
        return nullptr;
    } catch (const std::exception& e) {
        LOGE("exceção em sintetizar: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("exceção desconhecida em sintetizar");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jonjonesbr_audiobookgen_tts_NativeParadee_fecharSessao(
    JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Sessao*>(h);
}
