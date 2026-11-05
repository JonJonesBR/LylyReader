#!/usr/bin/env bash
# Testes de interface no emulador (rb-46), chamado pela ação android-emulator-runner do ui-tests.yml.
#
# Não usa `set -e`: o passo precisa chegar ao fim para salvar o logcat e as capturas, nos dois casos abaixo.
# - Falha de teste (assert etc.): grava ui-resultado.txt e sai com 0. O passo final do ui-tests.yml lê o arquivo e deixa
#   o run vermelho. Não há nova tentativa, para não mascarar teste instável.
# - Falha de infraestrutura (o gradlew falhou com um dos padrões de PADROES_INFRA e o aparelho não está mais no adb):
#   não grava ui-resultado.txt e sai com 1. Isso faz o passo do emulador falhar, e o ui-tests.yml tenta de novo.
set -u

# Trechos da saída do gradlew que indicam que o emulador ou o adb caiu, e não um teste que falhou.
PADROES_INFRA=(
  "waiting for device"
  "device offline"
  "DeviceException"
  "ShellCommandUnresponsiveException"
  "Failed to install"
  "device not found"
)

# Verdadeiro se a saída guardada em ui-gradle.txt tem algum dos padrões de infraestrutura.
falha_de_infra() {
  local padrao
  for padrao in "${PADROES_INFRA[@]}"; do
    if grep -qF "$padrao" ui-gradle.txt; then
      return 0
    fi
  done
  return 1
}

# Tamanho e densidade reais do emulador, para anotar nas capturas. A densidade vira 480 dpi (tela de 360 dp de largura).
adb shell wm size
adb shell wm density
adb shell wm density 480

adb logcat -c

# Um ANR de outro app em segundo plano (ex.: "Pixel Launcher isn't responding") abria uma janela por cima da tela no
# meio dos testes (rb-48). Os ANRs continuam no logcat; um ANR do nosso app ainda faz o teste falhar por tempo.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true

# A saída vai para o log do passo e para ui-gradle.txt, para decidir depois se a falha foi de infraestrutura.
./gradlew :app:connectedDebugAndroidTest --stacktrace -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true 2>&1 \
  | tee ui-gradle.txt
GRADLE_STATUS=${PIPESTATUS[0]}

INFRA=0
if [ "$GRADLE_STATUS" -ne 0 ]; then
  # adb get-state responde "device" quando o aparelho está ok; com o emulador caído, responde outra coisa ou erro.
  if [ "$(adb get-state 2>/dev/null || true)" != "device" ] && falha_de_infra; then
    INFRA=1
    echo "Falha de infraestrutura do emulador (padrão conhecido na saída do gradlew e aparelho fora do adb)."
  else
    echo falhou > ui-resultado.txt
  fi
fi

# Log do aparelho durante os testes, para diagnóstico quando algo falha.
adb logcat -d > logcat-ui.txt

# Plano B: as capturas da pasta externa do app de teste (se o AGP não trouxe o additionalTestOutputDir).
mkdir -p ui-adb
adb pull /sdcard/Android/media/com.jonjonesbr.audiobookgen.dev ui-adb || true

if [ "$INFRA" -eq 1 ]; then
  exit 1
fi
exit 0
