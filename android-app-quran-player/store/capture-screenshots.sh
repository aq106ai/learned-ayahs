#!/usr/bin/env bash
# Captures the Play Store screenshots from the real app on a running emulator or phone:
#
#   bash store/capture-screenshots.sh store/raw/phone      (from android-app-quran-player/)
#
# Installs the debug app and its tests, tidies the status bar with System UI demo mode, runs
# PlayStoreScreenshots through `am instrument` and pulls the PNGs it saved. Not
# connectedDebugAndroidTest: Gradle uninstalls the app when that finishes, taking the
# screenshots with it. A shot that fails to reach its screen is reported and skipped.
set -euo pipefail
out="${1:?usage: capture-screenshots.sh <out-dir>}"
# The installed app is the applicationId (the Play Store id); the test classes keep the code's
# package, which is the namespace.
app=$(sed -n 's/^ *applicationId = "\(.*\)"/\1/p' app/build.gradle.kts)
code=$(sed -n 's/^ *namespace = "\(.*\)"/\1/p' app/build.gradle.kts)
test -n "$app" && test -n "$code"

# First, before anything else loads the device: an emulator's launcher that is starved of CPU
# just after boot shows "isn't responding" over the app, and the first immersive screen shows a
# "Viewing full screen" hint. Turn both off.
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell settings put system screen_off_timeout 2147483647
adb shell svc power stayon true
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true

# Build (CI builds before the emulator starts and sets SKIP_BUILD=1), then let the device settle.
if [ "${SKIP_BUILD:-0}" != 1 ]; then
    ./gradlew --no-daemon assembleDebug assembleDebugAndroidTest
fi
adb shell input keyevent KEYCODE_HOME
sleep 30

adb install -r -t "$(ls app/build/outputs/apk/debug/*.apk | head -1)"
adb install -r -t "$(ls app/build/outputs/apk/androidTest/debug/*.apk | head -1)"

# A fixed clock, full battery and signal, and no notification icons.
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" > /dev/null; }
adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 1000
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile show -e datatype none -e level 4
demo notifications -e visible false
sleep 3

adb shell run-as "$app" rm -rf files/screenshots || true
adb shell am instrument -w -e class "$code.PlayStoreScreenshots" \
    "$app.test/androidx.test.runner.AndroidJUnitRunner" | tee instrument.log
grep -E "^(OK|FAILURES|Tests run)" instrument.log || true

# Replace the previous set, so a shot that failed this time cannot leave an old one behind.
rm -rf "$out" && mkdir -p "$out"
shots=$(adb shell run-as "$app" ls files/screenshots 2> /dev/null | tr -d '\r' || true)
for shot in $shots; do
    adb exec-out run-as "$app" cat "files/screenshots/$shot" > "$out/$shot"
done
echo "Pulled $(echo "$shots" | grep -c png || true) screenshots into $out"
test -n "$shots"
