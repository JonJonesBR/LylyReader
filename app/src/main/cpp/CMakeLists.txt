cmake_minimum_required(VERSION 3.22.1)
project(lyly_pocket_tts LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
get_filename_component(POCKET_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../.." ABSOLUTE)
add_compile_options(
    "-ffile-prefix-map=${POCKET_REPO_ROOT}="
    "-fdebug-prefix-map=${POCKET_REPO_ROOT}=")
if(DEFINED ANDROID_NDK)
    add_compile_options(
        "-ffile-prefix-map=${ANDROID_NDK}=<android-ndk>"
        "-fdebug-prefix-map=${ANDROID_NDK}=<android-ndk>")
endif()

set(POCKET_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/PocketTTS.cpp")
set(DR_LIBS_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/dr_libs")
set(ORT_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/onnxruntime/include")
set(ORT_LIBRARY "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libonnxruntime_supertonic.so")

if(NOT EXISTS "${ORT_LIBRARY}")
    message(FATAL_ERROR "Pocket TTS reuses libonnxruntime_supertonic.so; fetch it before configuring the Android build.")
endif()
if(NOT EXISTS "${ORT_ROOT}/onnxruntime/core/session/onnxruntime_cxx_api.h")
    message(FATAL_ERROR "Pinned ONNX Runtime headers are missing from app/src/main/cpp/third_party/onnxruntime/include.")
endif()

# Keep the native dependency graph deterministic. The ONNX Runtime binary is the same
# 1.26.0 library used by Supertonic; the vendored 1.20 C API headers are backward
# compatible and keep this integration from adding another ~17 MB runtime per ABI.
include(FetchContent)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_ABSL_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    sentencepiece
    GIT_REPOSITORY https://github.com/google/sentencepiece.git
    GIT_TAG v0.2.1
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(sentencepiece)
if(NOT sentencepiece_POPULATED)
    FetchContent_Populate(sentencepiece)
    add_subdirectory(
        "${sentencepiece_SOURCE_DIR}"
        "${sentencepiece_BINARY_DIR}"
        EXCLUDE_FROM_ALL
    )
endif()

if(TARGET sentencepiece-static)
    target_compile_options(sentencepiece-static PRIVATE -w)
endif()
foreach(spm_tool spm_decode spm_encode spm_export_vocab spm_normalize spm_train)
    if(TARGET ${spm_tool})
        target_link_libraries(${spm_tool} log)
    endif()
endforeach()

add_library(pockettts_jni SHARED
    "${POCKET_ROOT}/pocket_tts.cpp"
    "pocket_jni.cpp"
)
target_compile_definitions(pockettts_jni PRIVATE PTT_SHARED_LIB)
target_include_directories(pockettts_jni PRIVATE
    "${ORT_ROOT}"
    "${ORT_ROOT}/onnxruntime/core/session"
    "${sentencepiece_SOURCE_DIR}/src"
    "${DR_LIBS_ROOT}"
)
target_link_libraries(pockettts_jni PRIVATE
    "${ORT_LIBRARY}"
    sentencepiece-static
    log
    android
)

# Paradee (fonemizador espeak-ng 1.52.0 + ORT). Só o código-fonte é baixado: sem add_subdirectory,
# porque o CMake upstream baixa sonic, inclui CTest e executa binário do host.
FetchContent_Declare(
    espeakng
    URL https://github.com/espeak-ng/espeak-ng/archive/refs/tags/1.52.0.tar.gz
    URL_HASH SHA256=bb4338102ff3b49a81423da8a1a158b420124b055b60fa76cfb4b18677130a23
)
FetchContent_GetProperties(espeakng)
if(NOT espeakng_POPULATED)
    FetchContent_Populate(espeakng)
endif()

# config.h equivalente ao config.h.in upstream (as macros que os fontes usam; ARCH_BIG e
# PLATFORM_POSIX já vêm de speech.h).
set(ESPEAK_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/espeak-config")
file(MAKE_DIRECTORY "${ESPEAK_CONFIG_DIR}")
file(WRITE "${ESPEAK_CONFIG_DIR}/config.h"
"#define HAVE_MKSTEMP 1
#define USE_ASYNC 0
#define USE_KLATT 0
#define USE_LIBPCAUDIO 0
#define USE_LIBSONIC 0
#define USE_MBROLA 0
#define USE_SPEECHPLAYER 0
#define PACKAGE_VERSION \"1.52.0\"
")

set(ESPEAK_LIB_DIR "${espeakng_SOURCE_DIR}/src/libespeak-ng")
set(ESPEAK_UCD_DIR "${espeakng_SOURCE_DIR}/src/ucd-tools/src")
add_library(espeak_ng_paradee STATIC
    "${ESPEAK_LIB_DIR}/common.c"
    "${ESPEAK_LIB_DIR}/mnemonics.c"
    "${ESPEAK_LIB_DIR}/error.c"
    "${ESPEAK_LIB_DIR}/ieee80.c"
    "${ESPEAK_LIB_DIR}/compiledata.c"
    "${ESPEAK_LIB_DIR}/compiledict.c"
    "${ESPEAK_LIB_DIR}/dictionary.c"
    "${ESPEAK_LIB_DIR}/encoding.c"
    "${ESPEAK_LIB_DIR}/intonation.c"
    "${ESPEAK_LIB_DIR}/langopts.c"
    "${ESPEAK_LIB_DIR}/numbers.c"
    "${ESPEAK_LIB_DIR}/phoneme.c"
    "${ESPEAK_LIB_DIR}/phonemelist.c"
    "${ESPEAK_LIB_DIR}/readclause.c"
    "${ESPEAK_LIB_DIR}/setlengths.c"
    "${ESPEAK_LIB_DIR}/soundicon.c"
    "${ESPEAK_LIB_DIR}/spect.c"
    "${ESPEAK_LIB_DIR}/ssml.c"
    "${ESPEAK_LIB_DIR}/synthdata.c"
    "${ESPEAK_LIB_DIR}/synthesize.c"
    "${ESPEAK_LIB_DIR}/tr_languages.c"
    "${ESPEAK_LIB_DIR}/translate.c"
    "${ESPEAK_LIB_DIR}/translateword.c"
    "${ESPEAK_LIB_DIR}/voices.c"
    "${ESPEAK_LIB_DIR}/wavegen.c"
    "${ESPEAK_LIB_DIR}/speech.c"
    "${ESPEAK_LIB_DIR}/espeak_api.c"
    "${ESPEAK_UCD_DIR}/case.c"
    "${ESPEAK_UCD_DIR}/categories.c"
    "${ESPEAK_UCD_DIR}/ctype.c"
    "${ESPEAK_UCD_DIR}/proplist.c"
    "${ESPEAK_UCD_DIR}/scripts.c"
    "${ESPEAK_UCD_DIR}/tostring.c"
)
target_include_directories(espeak_ng_paradee
    PRIVATE
        "${ESPEAK_CONFIG_DIR}"
        "${ESPEAK_LIB_DIR}"
        "${ESPEAK_UCD_DIR}/include"
        "${espeakng_SOURCE_DIR}/src/include/compat"
    PUBLIC
        "${espeakng_SOURCE_DIR}/src/include"
)
target_compile_definitions(espeak_ng_paradee PRIVATE
    "LIBESPEAK_NG_EXPORT=1"
    "PATH_ESPEAK_DATA=\"/nonexistent\""
)
target_compile_options(espeak_ng_paradee PRIVATE -fvisibility=hidden -fwrapv -w)
set_target_properties(espeak_ng_paradee PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(espeak_ng_paradee PRIVATE m)

add_library(paradee_jni SHARED
    "paradee_jni.cpp"
)
target_include_directories(paradee_jni PRIVATE
    "${ORT_ROOT}"
    "${ORT_ROOT}/onnxruntime/core/session"
)
target_link_libraries(paradee_jni PRIVATE
    "${ORT_LIBRARY}"
    espeak_ng_paradee
    log
)
# Inline/templates dos headers do ORT e do libc++ ficam ocultos; o JNIEXPORT segue exportado.
target_compile_options(paradee_jni PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
# Nenhum símbolo do espeak/ucd sai exportado pela .so: só as funções JNI de NativeParadee.
target_link_options(paradee_jni PRIVATE "-Wl,--exclude-libs,ALL")
