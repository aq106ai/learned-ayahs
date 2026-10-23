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
source "$(dirname "$0")/device.sh"
# Unlike the video, the screenshots keep the screen on throughout.
adb shell svc power stayon true
prepare_device

adb shell run-as "$app" rm -rf files/screenshots || true
instrument PlayStoreScreenshots

# Replace the previous set, so a shot that failed this time cannot leave an old one behind.
rm -rf "$out" && mkdir -p "$out"
shots=$(adb shell run-as "$app" ls files/screenshots 2> /dev/null | tr -d '\r' || true)
for shot in $shots; do
    pull_app_file "screenshots/$shot" "$out/$shot"
done
echo "Pulled $(echo "$shots" | grep -c png || true) screenshots into $out"
test -n "$shots"
