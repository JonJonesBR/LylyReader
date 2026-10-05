#!/usr/bin/env bash
# Baixa a biblioteca nativa do ONNX Runtime usada pelo Supertonic e a copia para jniLibs.
# O CI faz o mesmo (.github/workflows/release.yml); rode isto uma vez antes do primeiro build local.
set -euo pipefail
VERSION="${ORT_SUPERTONIC_VERSION:-1.26.0}"
cd "$(dirname "$0")/.."
mkdir -p .ortdl
curl -fsSL -o .ortdl/ort.aar \
  "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/${VERSION}/onnxruntime-android-${VERSION}.aar"
(cd .ortdl && rm -rf x && unzip -q ort.aar -d x)
for abi in arm64-v8a x86_64; do
  mkdir -p "app/src/main/jniLibs/$abi"
  cp ".ortdl/x/jni/$abi/libonnxruntime.so" "app/src/main/jniLibs/$abi/libonnxruntime_supertonic.so"
done
echo "libonnxruntime_supertonic.so copiada (arm64-v8a, x86_64)."
