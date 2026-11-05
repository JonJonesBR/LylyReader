#!/usr/bin/env bash
# Testes de interface no emulador (rb-46), chamado pela ação android-emulator-runner do ui-tests.yml.
#
# Não usa `set -e`: o passo precisa chegar ao fim para salvar o logcat e as capturas, nos dois casos abaixo.
# - Falha de teste (assert etc.): grava ui-resultado.txt e sai com 0. O passo final do ui-tests.yml lê o arquivo e deixa
#   o run vermelho. Não há nova tentativa, para não mascarar teste instável.
# - Falha de infraestrutura (o gradlew falhou com um dos padrões de PADROES_INFRA e o aparelho não está mais no adb):
#   não grava ui-resultado.txt e sai com 1. Isso faz o passo do emulador falhar, e o ui-tests.yml tenta de novo.
#   Dois casos reais no PR #24: o gradlew caiu com "Test run failed to complete. Expected 42 tests, received 36" (o
#   emulador caiu no meio) e, depois, o passo ficou ~25 min preso nos comandos adb do fim, até o job ser cancelado.
#   Por isso todo comando adb tem tempo-limite (`timeout`): uma coleta travada vira aviso e o script segue até o fim.
#   Se `adb get-state` ainda responder 'device', a falha continua sendo de teste, mesmo com o padrão na saída.
set -u

# Tempos-limite dos comandos adb, em segundos. Com o emulador caído, o adb pode esperar para sempre.
TEMPO_ADB_CURTO=30   # adb shell, adb get-state e adb logcat -c
TEMPO_LOGCAT=60      # adb logcat -d (log dos testes)
TEMPO_ADB_LONGO=120  # adb pull das capturas

# Trechos da saída do gradlew que indicam que o emulador ou o adb caiu, e não um teste que falhou.
# "Test run failed to complete" é a mensagem do AGP quando o aparelho some no meio da execução.
PADROES_INFRA=(
  "waiting for device"
  "device offline"
  "DeviceException"
  "ShellCommandUnresponsiveException"
  "Failed to install"
  "device not found"
  "Test run failed to complete"
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
timeout "$TEMPO_ADB_CURTO" adb shell wm size || true
timeout "$TEMPO_ADB_CURTO" adb shell wm density || true
timeout "$TEMPO_ADB_CURTO" adb shell wm density 480 || true

timeout "$TEMPO_ADB_CURTO" adb logcat -c || true

# Um ANR de outro app em segundo plano (ex.: "Pixel Launcher isn't responding") abria uma janela por cima da tela no
# meio dos testes (rb-48). Os ANRs continuam no logcat; um ANR do nosso app ainda faz o teste falhar por tempo.
timeout "$TEMPO_ADB_CURTO" adb shell settings put global hide_error_dialogs 1 || true
timeout "$TEMPO_ADB_CURTO" adb shell settings put secure anr_show_background 0 || true

# A saída vai para o log do passo e para ui-gradle.txt, para decidir depois se a falha foi de infraestrutura.
./gradlew :app:connectedDebugAndroidTest --stacktrace -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true 2>&1 \
  | tee ui-gradle.txt
GRADLE_STATUS=${PIPESTATUS[0]}

INFRA=0
if [ "$GRADLE_STATUS" -ne 0 ]; then
  # adb get-state responde "device" quando o aparelho está ok; com o emulador caído, responde outra coisa, erro ou
  # nada (tempo-limite).
  if [ "$(timeout "$TEMPO_ADB_CURTO" adb get-state 2>/dev/null || true)" != "device" ] && falha_de_infra; then
    INFRA=1
    echo "Falha de infraestrutura do emulador (padrão conhecido na saída do gradlew e aparelho fora do adb)."
  else
    echo falhou > ui-resultado.txt
  fi
fi

# Log do aparelho durante os testes, para diagnóstico quando algo falha.
timeout "$TEMPO_LOGCAT" adb logcat -d > logcat-ui.txt || true

# Plano B: as capturas da pasta externa do app de teste (se o AGP não trouxe o additionalTestOutputDir).
mkdir -p ui-adb
timeout "$TEMPO_ADB_LONGO" adb pull /sdcard/Android/media/com.jonjonesbr.audiobookgen.dev ui-adb || true

if [ "$INFRA" -eq 1 ]; then
  exit 1
fi
exit 0
