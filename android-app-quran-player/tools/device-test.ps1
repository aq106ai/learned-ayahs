<#
.SYNOPSIS
Runs instrumented tests on a connected device without Gradle's install/uninstall cycle.

.DESCRIPTION
`gradlew connectedDebugAndroidTest` uninstalls both APKs when it finishes, so every run needs a
fresh install. On MIUI that is the expensive step: the handset revokes adb's install rights and
"Install via USB" has to be re-enabled by hand, in practice allowing about one install per flip.
Iterating on a test therefore costs a trip to the phone each time, which is absurd.

This installs once and then drives `am instrument` directly, so re-running a test — or running a
different one — needs no install at all. Use -Install only when the APKs have actually changed.

.EXAMPLE
  # after changing test or app code (-Install rebuilds first, so this is always current)
  ./tools/device-test.ps1 -Install -Class com.quran.learnedplayer.FullSurahRecitationTest

.EXAMPLE
  # re-run, no install needed
  ./tools/device-test.ps1 -Class com.quran.learnedplayer.RecitationScreenTest

.EXAMPLE
  # several classes in one session
  ./tools/device-test.ps1 -Class "com.quran.learnedplayer.FullSurahRecitationTest,com.quran.learnedplayer.WordByWordTest"
#>
param(
    [string]$Class = "",
    [switch]$Install,
    [switch]$Build,
    # These tests print their measurements to stdout, which `am instrument -r` does not surface
    # usefully. Tailing logcat is how the numbers are actually read.
    [string]$LogTag = "System.out"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
# Globbed, not hardcoded: the APK carries the versionName, so pinning it here means every release
# bump silently breaks this script with a "missing APK" that looks like a build failure.
$appApkDir = Join-Path $root "app/build/outputs/apk/debug"
$testApk = Join-Path $root "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
$runner = "com.quran.learnedplayer.test/androidx.test.runner.AndroidJUnitRunner"

# -Install always rebuilds first. Compiling is not packaging: `compileDebugAndroidTestKotlin`
# leaves the APK untouched, so installing after it silently ships the *previous* build and the
# failures you then debug belong to code you already changed. Ask for an install, get the code you
# have. -Build alone still works for a build with no install.
if ($Build -or $Install) {
    Write-Host "==> building APKs" -ForegroundColor Cyan
    & (Join-Path $root "gradlew.bat") assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
}

if ($Install) {
    $appApk = Get-ChildItem -Path $appApkDir -Filter "*-debug.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $appApk) { throw "no debug APK found in $appApkDir" }
    foreach ($apk in @($appApk, $testApk)) {
        if (-not (Test-Path $apk)) { throw "missing APK: $apk" }
        Write-Host "==> installing $(Split-Path -Leaf $apk)" -ForegroundColor Cyan
        $out = & adb install -r $apk 2>&1 | Out-String
        if ($out -match "INSTALL_FAILED_USER_RESTRICTED") {
            Write-Host $out
            throw "MIUI blocked the install. On the phone: Developer options -> 'Install via USB' ON (and 'USB debugging (Security settings)' ON), then re-run. Nothing in adb can bypass this."
        }
        if ($out -notmatch "Success") { Write-Host $out; throw "install failed" }
    }
}

# Fail early and clearly rather than letting `am instrument` report a confusing missing-runner error.
$installed = & adb shell pm list packages 2>&1 | Out-String
if ($installed -notmatch "com\.quran\.learnedplayer\.test") {
    throw "test APK is not installed - re-run with -Install (Gradle's connectedAndroidTest uninstalls it when it finishes)"
}

& adb logcat -c

# Keep the display on for the run.
#
# When the screen sleeps mid-suite the foreground activity is torn down and tests fail with
# "Activity never becomes requested state [DESTROYED]", a bare timeout, or "Process crashed" —
# reds that name innocent tests and look exactly like a code regression. Developer options'
# "Stay awake" only applies while charging, so an unplugged phone still follows
# screen_off_timeout regardless.
#
# Raise the timeout; never inject `input keyevent KEYCODE_WAKEUP` on a loop to do this. Those
# events enter the same input pipeline the instrumentation uses for its own taps and swipes, and
# tests start losing gestures — that approach was tried on a sibling project and produced 28
# spurious ComposeTimeoutException failures across 15 classes. `settings put system` touches no
# input at all and works on MIUI without WRITE_SECURE_SETTINGS.
$previousTimeout = (& adb shell settings get system screen_off_timeout 2>&1 | Out-String).Trim()
$restoreTimeout = $previousTimeout -match '^\d+$'
if ($restoreTimeout) {
    Write-Host "==> screen_off_timeout $previousTimeout -> 2h for the run" -ForegroundColor Cyan
    & adb shell settings put system screen_off_timeout 7200000 | Out-Null
} else {
    Write-Host "==> could not read screen_off_timeout ('$previousTimeout') - leaving it alone" -ForegroundColor Yellow
}
& adb shell wm dismiss-keyguard | Out-Null

try {
    $args = @("shell", "am", "instrument", "-w")
    if ($Class -ne "") { $args += @("-e", "class", $Class) }
    $args += $runner

    Write-Host "==> am instrument $Class" -ForegroundColor Cyan
    & adb @args
} finally {
    # Restored even when the run throws or is interrupted; leaving a phone on a 2-hour screen
    # timeout is a rude thing to do to someone's device.
    if ($restoreTimeout) {
        & adb shell settings put system screen_off_timeout $previousTimeout | Out-Null
        Write-Host "==> screen_off_timeout restored to $previousTimeout" -ForegroundColor Cyan
    }
}

Write-Host "`n==> measurements from logcat" -ForegroundColor Cyan
& adb logcat -d -s "${LogTag}:I" | Select-String -Pattern "\[(corpus|compare|surah)\]"
