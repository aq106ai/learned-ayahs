# Shared by capture-screenshots.sh and capture-video.sh (sourced, run from android-app-quran-player/).
#
# The installed app is the applicationId (the Play Store id); the test classes keep the code's
# package, which is the namespace.
app=$(sed -n 's/^ *applicationId = "\(.*\)"/\1/p' app/build.gradle.kts)
code=$(sed -n 's/^ *namespace = "\(.*\)"/\1/p' app/build.gradle.kts)
test -n "$app" && test -n "$code"

# Readies the device, then installs the debug app and its tests.
prepare_device() {
    # First, before anything else loads the device: an emulator's launcher that is starved of
    # CPU just after boot shows "isn't responding" over the app, and the first immersive screen
    # shows a "Viewing full screen" hint. Turn both off.
    adb shell settings put global hide_error_dialogs 1
    adb shell settings put secure immersive_mode_confirmations confirmed
    adb shell settings put system screen_off_timeout 2147483647
    adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true

    # Build (CI builds before the emulator starts and sets SKIP_BUILD=1), then let it settle.
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
}

# Runs one class of the androidTest APK through `am instrument`, printing its result.
instrument() {
    adb shell am instrument -w -e class "$code.$1" "$app.test/androidx.test.runner.AndroidJUnitRunner" \
        | tee instrument.log
    grep -E "^(OK|FAILURES|Tests run)" instrument.log || true
}

# Copies a file out of the app's private files dir: pull_app_file <path under files/> <dest>.
pull_app_file() {
    mkdir -p "$(dirname "$2")"
    # </dev/null: adb would otherwise read the caller's stdin, such as a `while read` loop's list.
    adb exec-out run-as "$app" cat "files/$1" < /dev/null > "$2"
}
