#!/usr/bin/env bash
# Records the video the Play Console asks for with the foreground-service declaration, from the
# real app on a running emulator or phone:
#
#   bash store/capture-video.sh store/raw/video      (from android-app-quran-player/)
#
# Runs PlayStoreFgsVideo, which starts recitation and screen-records it continuing in the
# background, from the notification, and with the screen off. Pulls the recording, the playback
# log and the clips it names; `node store/render/render-video.cjs` turns them into the video.
# Needs a network connection on the device: recitation streams from everyayah.com.
set -euo pipefail
out="${1:?usage: capture-video.sh <out-dir>}"
source "$(dirname "$0")/device.sh"
prepare_device

adb shell rm -f /data/local/tmp/fgs.mp4
adb shell run-as "$app" rm -rf files/video || true
instrument PlayStoreFgsVideo

rm -rf "$out" && mkdir -p "$out/audio"
adb pull /data/local/tmp/fgs.mp4 "$out/screen.mp4"
pull_app_file video/timeline.json "$out/timeline.json"
python3 -c 'import json,sys; [print(p) for p in sorted(set(json.load(open(sys.argv[1]))["clips"].values()))]' \
    "$out/timeline.json" | while read -r clip; do
    pull_app_file "$clip" "$out/audio/$(basename "$clip")"
done
ls -la "$out" "$out/audio"
