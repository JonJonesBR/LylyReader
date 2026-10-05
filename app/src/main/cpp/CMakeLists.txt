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
