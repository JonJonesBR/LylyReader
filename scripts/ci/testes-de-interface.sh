#!/usr/bin/env bash
# Testes de interface no emulador (rb-46), chamado pela ação android-emulator-runner do ui-tests.yml.
#
# Não usa `set -e`: o passo precisa chegar ao fim para salvar o logcat e as capturas mesmo quando os testes falham.
# A falha fica registrada no arquivo ui-resultado.txt, que o passo final do workflow lê. Este script sempre sai com 0.
set -u

# Tamanho e densidade reais do emulador, para anotar nas capturas. A densidade vira 480 dpi (tela de 360 dp de largura).
adb shell wm size
adb shell wm density
adb shell wm density 480

adb logcat -c

# Um ANR de outro app em segundo plano (ex.: "Pixel Launcher isn't responding") abria uma janela por cima da tela no
# meio dos testes (rb-48). Os ANRs continuam no logcat; um ANR do nosso app ainda faz o teste falhar por tempo.
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure anr_show_background 0 || true

./gradlew :app:connectedDebugAndroidTest --stacktrace -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  || echo falhou > ui-resultado.txt

# Log do aparelho durante os testes, para diagnóstico quando algo falha.
adb logcat -d > logcat-ui.txt

# Plano B: as capturas da pasta externa do app de teste (se o AGP não trouxe o additionalTestOutputDir).
mkdir -p ui-adb
adb pull /sdcard/Android/media/com.jonjonesbr.audiobookgen.dev ui-adb || true

exit 0
