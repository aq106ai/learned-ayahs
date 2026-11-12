param([string]$Serial = "emulator-5554")
$ErrorActionPreference = "Stop"
if ($Serial -notmatch '^emulator-\d+$') { throw "This matrix only changes emulator displays." }
$env:ANDROID_SERIAL = $Serial
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build/reader-validation'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$oldSize = (& adb shell wm size | Select-String 'Override size: (.*)').Matches.Groups
$oldDensity = (& adb shell wm density | Select-String 'Override density: (.*)').Matches.Groups
$oldScale = (& adb shell settings get system font_scale).Trim()
$cases = @(
    @(320,640,'1.0','en'), @(360,740,'1.3','ur'), @(411,891,'2.0','en'),
    @(600,800,'2.0','ar'), @(840,600,'1.0','en'), @(1024,768,'1.0','en'), @(740,360,'1.0','en')
)
$results = @()
try {
    & adb shell settings put secure immersive_mode_confirmations confirmed
    & adb shell wm density 160
    foreach ($case in $cases) {
        $label = "$($case[0])x$($case[1])-$($case[2])-$($case[3])"
        & adb shell wm size "$($case[0])x$($case[1])"
        & adb shell settings put system font_scale $case[2]
        try {
            & "$PSScriptRoot/device-test.ps1" -Serial $Serial -TestLocale $case[3] -Class com.quran.learnedplayer.ReaderLayoutTest *> (Join-Path $out "$label.log")
            $results += [pscustomobject]@{Case=$label;Result='PASS'}
        } catch {
            $results += [pscustomobject]@{Case=$label;Result='FAIL'}
        }
        $results[-1] | Format-Table
        $results | Export-Csv -NoTypeInformation (Join-Path $out 'matrix.csv')
    }
} finally {
    if ($oldSize.Count -gt 1) { & adb shell wm size $oldSize[1].Value } else { & adb shell wm size reset }
    if ($oldDensity.Count -gt 1) { & adb shell wm density $oldDensity[1].Value } else { & adb shell wm density reset }
    & adb shell settings put system font_scale $oldScale
    & adb pull /sdcard/Android/data/com.quran.learnedayahs/files $out
}
if ($results.Result -contains 'FAIL') { throw "Reader matrix has failures; see build/reader-validation." }
