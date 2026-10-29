#!/usr/bin/env bash
# Draws samples of "Share as video" on a running emulator or phone, for a person to review:
#
#   bash store/capture-video-samples.sh store/raw/video-maker      (from android-app-quran-player/)
#
# Runs VideoSamples: a contact sheet of every background, previews in each shape, and one real
# video of Al-Mulk 67:1-3; then PlayerBackgroundShots: the player with each kind of background. Needs a network connection on the device for the recitation.
set -euo pipefail
out="${1:?usage: capture-video-samples.sh <out-dir>}"
source "$(dirname "$0")/device.sh"
prepare_device

# The nature photos download from the repository's main branch, which may not have them yet;
# put this checkout's photos where the app keeps downloaded ones instead.
adb shell run-as "$app" mkdir -p files/video-photos
for photo in ../backgrounds/nature/*.jpg; do
    adb exec-in run-as "$app" sh -c "cat > files/video-photos/$(basename "$photo")" < "$photo"
done

instrument VideoSamples
grep -q "^OK" instrument.log
# Screenshots of the player's backgrounds and the shared image, into the same folder.
instrument PlayerBackgroundShots

rm -rf "$out" && mkdir -p "$out"
for file in $(adb exec-out run-as "$app" ls files/video-samples < /dev/null | tr -d '\r'); do
    pull_app_file "video-samples/$file" "$out/$file"
done
ls -la "$out"
grep -q "^OK" instrument.log
