#!/usr/bin/env bash
# One-shot setup + build for LearnedAyahsPlayer.
# Installs the Android SDK (if missing), then builds the debug APK.
#
# Usage:  bash .setup/build.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "==> Project: $ROOT"
echo "==> Android SDK dir: $SDK_DIR"

# --- 1. Old checkouts pinned Gradle 8.2/8.5, which AGP 8.10 cannot use; bring them to 8.11.1.
if grep -qE 'gradle-8\.(2|5)-bin.zip' "$ROOT/gradle/wrapper/gradle-wrapper.properties"; then
    echo "==> Updating the Gradle wrapper to 8.11.1 (required by AGP 8.10)"
    sed -i -E 's/gradle-8\.(2|5)-bin.zip/gradle-8.11.1-bin.zip/' "$ROOT/gradle/wrapper/gradle-wrapper.properties"
fi
chmod +x "$ROOT/gradlew"

# --- 2. Android SDK command-line tools
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    echo "==> Downloading Android command-line tools..."
    TMP_ZIP="$(mktemp /tmp/cmdline-tools-XXXX.zip)"
    curl -fSLo "$TMP_ZIP" "$CMDLINE_TOOLS_URL"
    mkdir -p "$SDK_DIR/cmdline-tools"
    unzip -q -o "$TMP_ZIP" -d "$SDK_DIR/cmdline-tools"
    # The zip extracts to 'cmdline-tools'; sdkmanager expects it under 'latest'
    rm -rf "$SDK_DIR/cmdline-tools/latest"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -f "$TMP_ZIP"
fi

# --- 3. SDK packages required by compileSdk 36
echo "==> Accepting licenses and installing SDK packages (platform 36, build-tools 35.0.0)..."
yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses > /dev/null || true
"$SDKMANAGER" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-36" "build-tools;35.0.0"

# --- 4. Point the project at the SDK
# (There is no native build any more. Recite used to bundle a Quran-tuned Whisper model behind
# whisper.cpp, which needed the NDK, CMake and a sparse checkout of a third-party repo. It was
# dropped in 1.8.0 — too slow to correct a live reciter even on current hardware — and recognition
# now goes through the phone's own speech service, so a plain SDK is all this needs.)
echo "sdk.dir=$SDK_DIR" > "$ROOT/local.properties"

# --- 5. Build
echo "==> Building debug APK..."
cd "$ROOT"
./gradlew assembleDebug --no-daemon

APK="$(ls "$ROOT"/app/build/outputs/apk/debug/*.apk | head -1)"
echo ""
echo "==> BUILD DONE"
echo "==> APK: $APK"
