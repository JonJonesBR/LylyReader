# Versão para Windows de fetch-onnxruntime.sh.
$ErrorActionPreference = 'Stop'
$version = if ($env:ORT_SUPERTONIC_VERSION) { $env:ORT_SUPERTONIC_VERSION } else { '1.26.0' }
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force .ortdl | Out-Null
Invoke-WebRequest "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$version/onnxruntime-android-$version.aar" -OutFile .ortdl\ort.zip
Remove-Item -Recurse -Force .ortdl\x -ErrorAction SilentlyContinue
Expand-Archive .ortdl\ort.zip -DestinationPath .ortdl\x
foreach ($abi in 'arm64-v8a', 'x86_64') {
    New-Item -ItemType Directory -Force "app\src\main\jniLibs\$abi" | Out-Null
    Copy-Item ".ortdl\x\jni\$abi\libonnxruntime.so" "app\src\main\jniLibs\$abi\libonnxruntime_supertonic.so" -Force
}
Write-Host 'libonnxruntime_supertonic.so copiada (arm64-v8a, x86_64).'
