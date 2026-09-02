#!/bin/bash
# Capture the raw screens for the Play listing.
#
# Same idea as the iOS script: the emulator gets put in a known state — right
# AVD, right app locale, demo mode status bar frozen at 9:41 with full bars — so
# a set captured today and a set captured next year look like the same set.
#
# Which screens, and with which intent extras, lives in store.config.json under
# "capture". This script only executes it.
#
# Usage:
#   scripts/store-capture-android.sh            # every screen, in the primary locale
#   scripts/store-capture-android.sh --locale en-US
#   scripts/store-capture-android.sh hoje kids  # just these
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
[ -f store.config.json ] || { echo "sem store.config.json — rode o storekit init primeiro"; exit 1; }

LOCALE="$(node -e "process.stdout.write(require('./store.config.json').primaryLocale||'pt-BR')")"
if [ "${1:-}" = "--locale" ]; then LOCALE="$2"; shift 2; fi

read_cfg() { node -e "const c=require('./store.config.json');process.stdout.write(String($1??''))"; }
AVD="$(read_cfg 'c.capture?.emulator')";  AVD="${AVD:-Medium_Phone}"
PKG="$(read_cfg '(c.play?.packageName || c.bundleId)')"
# As capturas saem da build de QA/debug, que leva o sufixo `.debug` para
# conviver com a release no mesmo aparelho (app/build.gradle.kts).
PKG="${PKG}.debug"
ACTIVITY="$(read_cfg 'c.capture?.activity')"; ACTIVITY="${ACTIVITY:-.MainActivity}"
OUT="$REPO_ROOT/$(read_cfg '(c.sourcesDir || "store/sources")')"
COMMON="$(read_cfg '(c.capture?.launchArgs||[]).join(" ")')"
mkdir -p "$OUT"

if ! adb devices | grep -q emulator; then
  echo "▸ subindo o emulador $AVD"
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-snapshot-save >/dev/null 2>&1 &
fi
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

# `STORE_APK=/caminho/app-qa.apk` instala um APK já compilado (o motor nativo
# precisa de NDK + CMake, e nem toda máquina de captura os tem); sem ele,
# compila o debug aqui mesmo.
if [ -n "${STORE_APK:-}" ]; then
  echo "▸ install $STORE_APK"
  adb install -r -t "$STORE_APK" >/dev/null
else
  echo "▸ build + install"
  scripts/build.sh >/dev/null
  adb install -r -t "$(find "$REPO_ROOT/app/build/outputs/apk" -name '*.apk' -print -quit)" >/dev/null
fi

# O gravador precisa de takes para o print dizer alguma coisa: uma linha do
# tempo vazia é uma tela vazia. Semeia um projeto de demonstração no app.
if [ -x scripts/store-seed-recorder.sh ]; then scripts/store-seed-recorder.sh "$PKG"; fi

echo "▸ locale do app: $LOCALE"
adb shell cmd locale set-app-locales "$PKG" --user 0 --locales "$LOCALE" >/dev/null 2>&1 || true

# Demo mode: a frozen status bar, so the clock and the battery are the same in
# every panel. Without it the set shows five different times and a dying battery.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile show -e level 4 -e datatype false >/dev/null
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

SCREENS="$(node -e "
  const c = require('./store.config.json');
  const want = process.argv.slice(1);
  const all = c.capture?.screens ?? [];
  const pick = want.length ? all.filter(s => want.includes(s.id)) : all;
  if (!pick.length) { console.error('nenhuma tela bateu'); process.exit(1); }
  console.log(pick.map(s => [s.id, s.delay ?? 3, (s.args ?? []).join(' ')].join('\t')).join('\n'));
" "$@")"

while IFS=$'\t' read -r id delay args; do
  [ -n "$id" ] || continue
  echo "▸ $id"
  adb shell am force-stop "$PKG" </dev/null
  # shellcheck disable=SC2086
  adb shell am start -n "$PKG/$ACTIVITY" $COMMON $args </dev/null >/dev/null
  sleep "$delay"
  adb exec-out screencap -p </dev/null > "$OUT/$id.png"
  echo "  $OUT/$id.png"
done <<< "$SCREENS"

adb shell am force-stop "$PKG"
adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
echo
echo "Capturado. Confira as imagens antes de moldar — tela com spinner de carregamento"
echo "vira painel com spinner. Depois: scripts/store.sh build"
