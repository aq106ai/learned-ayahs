#!/usr/bin/env bash
# Smoke test of the release build, which R8 shrinks and obfuscates: install it on the connected
# emulator or phone, go through first-run, play Al-Fatihah, leave the app, and check it is
# still playing with no crash. The instrumented tests run the debug build, which R8 does not
# touch, so this is what catches a library class R8 removed or renamed by mistake.
#
#   ./gradlew assembleRelease && bash tools/release-smoke.sh
#   bash tools/release-smoke.sh path/to/other.apk     # e.g. the debug build, to compare
set -euo pipefail
cd "$(dirname "$0")/.."
app=$(sed -n 's/.*applicationId = "\(.*\)".*/\1/p' app/build.gradle.kts)
apk=${1:-$(ls app/build/outputs/apk/release/*.apk | head -1)}
echo "== $apk"
fail() {
  echo "FAIL: $*"
  echo "--- media sessions"; adb shell dumpsys media_session | grep -iE "package=|state=" | head -20
  echo "--- on screen"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | grep -oE '(text|content-desc)="[^"]+"' | head -40
  echo "--- app log"; adb logcat -d | grep -iE "learned|PlaybackService|ExoPlayer|media3|AndroidRuntime|ForegroundService|FGS" | tail -60
  exit 1
}

adb uninstall "$app" >/dev/null 2>&1 || true
adb install "$apk"
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell monkey -p "$app" -c android.intent.category.LAUNCHER 1 >/dev/null

# Taps the first on-screen element whose text or description is $1, shifted $2 px left.
tap() {
  for _ in $(seq 1 30); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
    # The first dumps can come before the window is up; an empty one just means try again.
    xy=$( (adb shell cat /sdcard/ui.xml 2>/dev/null || true) | python3 -c '
import re, sys
label, shift = sys.argv[1], int(sys.argv[2])
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if f"text=\"{label}\"" in node or f"content-desc=\"{label}\"" in node:
        x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        print((x1 + x2) // 2 - shift, (y1 + y2) // 2)
        break' "$1" "${2:-0}")
    if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped '$1'"; sleep 2; return 0; fi
    sleep 1
  done
  fail "'$1' never appeared"
}

# Whether an element whose text or description is $1 is on screen now.
on_screen() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  (adb shell cat /sdcard/ui.xml 2>/dev/null || true) | grep -qE "(text|content-desc)=\"$1\""
}

# Taps $1 until it's gone: a tap that lands while a dialog is still settling can do nothing.
# A dialog that did take the tap can still be on screen for a moment as it closes, so give it
# a few seconds to go before tapping again.
tap_away() {
  for _ in 1 2 3; do
    tap "$1"
    for _ in 1 2 3 4 5; do
      on_screen "$1" || return 0
      sleep 1
    done
    echo "'$1' still on screen, tapping again"
  done
  fail "'$1' stayed on screen"
}

tap_away "I agree"
tap_away "Skip"
tap "Al-Fatihah"
tap "Mark learned" 300   # the ayah row beside ayah 1's mark button plays it

# Android 14 prints "state=PLAYING(3)", older versions "state=3".
playing() { adb shell dumpsys media_session | grep -qE "state=PlaybackState \{state=(3|PLAYING)"; }
for _ in $(seq 1 45); do playing && break; sleep 1; done
playing || fail "recitation never started"
echo "playing"

adb shell input keyevent KEYCODE_HOME
sleep 15
playing || fail "recitation stopped in the background"
adb shell pidof "$app" >/dev/null || fail "the app is not running"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then fail "the app crashed"; fi
echo "release build OK: plays, keeps playing in the background, no crash"
