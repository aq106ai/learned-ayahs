#!/usr/bin/env bash
# Draws samples of "Share as video" on a running emulator or phone, for a person to review:
#
#   bash store/capture-video-samples.sh store/raw/video-maker      (from android-app-quran-player/)
#
# Runs VideoSamples: a contact sheet of every background, previews in each shape, and one real
# video of Al-Mulk 67:1-3. Needs a network connection on the device for the recitation.
set -euo pipefail
out="${1:?usage: capture-video-samples.sh <out-dir>}"
source "$(dirname "$0")/device.sh"
prepare_device
instrument VideoSamples

rm -rf "$out" && mkdir -p "$out"
for file in $(adb exec-out run-as "$app" ls files/video-samples < /dev/null | tr -d '\r'); do
    pull_app_file "video-samples/$file" "$out/$file"
done
ls -la "$out"
grep -q "^OK" instrument.log
