# Learned Ayahs: Android app

A standalone Android app for revising the ayahs **you** have memorised. Browse the 114 surahs,
mark the ayahs you know, and the app plays them back so you can listen, repeat and follow word
by word.

- **Package:** `com.quran.learnedplayer` · **Current version:** 1.9.2
- **Requires:** Android 8.0 (API 26) or newer.
- **Built with:** Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSession

## Features

### Mark what you have learned

- Open any surah and tap the circle beside each ayah you have memorised.
- **Add by description** lets you add many at once with references such as `2:255`, `36:1-83`,
  `112`, `78-114` or `Al-Baqarah 255`. It can also copy a prompt you can give to any AI
  assistant to turn a description ("the last two pages of Al-Baqarah") into references to paste
  back. The app itself never calls an AI service.
- **Export / Import** your list as a small JSON file through the system file picker, for backup
  or moving to a new phone. No storage permissions are needed.
- Nothing is ever marked for you: a new install starts with an empty list.

### Revise

| Mode | What plays |
|---|---|
| **Learned ayahs** | Your marked ayahs, in mushaf order |
| **Full surah** | Every ayah of the current surah, learned or not |

**Word by Word / View Full Ayah** in the three-dot menu changes the reader without changing the
queue or the audio. In the expanded player controls, **Repeat** is one choice of Ayah, Surah,
Word or Off: Word loops the word being recited until Next, with either view on screen. **Word
clips** plays each word from Quran.com's own recording, one at a time, in place of the chosen
reciter. Without clips, repeating a word needs validated timings for that reciter.

Each queue choice combines with a **repeat** setting: **Off**, **Surah** (loop the surah) or **Ayah**
(loop the current ayah, with an optional pause between repeats so you can recite it yourself).

- A'udhu billah and Bismillah play before ayah 1 of each surah. Surah 9 has no Bismillah.
- **Word highlighting** follows the recitation using real per-word timings for the selected
  reciter. Long ayahs auto-scroll or shrink to fit, with scrolling retained when the minimum
  readable size still exceeds the available space.
- **Reciters:** Maher Al Muaiqly (default), Abdul Basit (Murattal), Mahmoud Khalil Al-Husary,
  Abdurrahman As-Sudais, Saud Ash-Shuraym.
- The app remembers where you left off, even across restarts.

### Recite & review has been removed

The practice coach that listened while you recited is no longer in this app, and the app no
longer asks for the microphone at all. It depended on the phone's general-purpose Arabic speech
recognition, which does not reliably return short isolated words of Qur'anic Arabic, so it could
report a mistake on a word you had recited correctly — and keep reporting it.

The code is kept on the `archive/recite-review-word-by-word` branch. A fresh version is being
built in the web app first, where recognition can actually be observed and measured.

### Controls and offline use

- Play/pause/next/previous from the **lock screen, notification, Bluetooth headsets and car
  systems**. These go through the same logic as the on-screen buttons. With **Repeat: Ayah** on,
  Next always advances, so a headset button moves you to the next ayah.
- Optional **swipe to navigate** and **tap to advance** gestures.
- **Settings → Offline** saves your learned ayahs' audio (and word clips) to the phone. The full
  Qur'an text, English word meanings and word timings are built into the app. Other word-meaning
  languages download by surah and remain available offline; missing meanings fall back to English.
- Settings and onboarding have separate switches for word meanings and full-ayah translation.
  Both may be off. Word meanings support English, Urdu, Indonesian, Bengali, Persian, Hindi,
  Tamil, Turkish and Ingush, with coverage varying by language.
- Dark and light themes.

## Install

There is no store release yet. Build the APK yourself (below) or download one from the
repository's [Releases](https://github.com/aq106ai/learned-ayahs/releases) page when available.
After installing, check **Settings → About** for the version.

### First use

1. Open the app. A short intro explains the basics.
2. Open a surah and mark the ayahs you know, or use **Add by description**, or **Import** a
   previous export.
3. Go to the player and press **Play**.

## Build

Requirements: **JDK 17+** and the **Android SDK** with platform 36. Installing
[Android Studio](https://developer.android.com/studio) gives you both. Open this folder in
Android Studio, or build from the command line:

```bash
# macOS / Linux
echo "sdk.dir=$HOME/Android/Sdk" > local.properties        # macOS: $HOME/Library/Android/sdk
./gradlew assembleDebug
```

```powershell
# Windows
echo "sdk.dir=C\:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk" > local.properties
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/LearnedAyahsPlayer-v<version>-debug.apk`.

On Linux, `bash .setup/build.sh` installs the Android SDK command-line tools (into
`$ANDROID_HOME`, or `~/android-sdk` if that isn't set) and builds the debug APK in one go.

### Release build

Release builds are signed from `keystore.properties` next to `settings.gradle.kts` (git-ignored):

```properties
storeFile=../upload-key.keystore
storePassword=…
keyAlias=upload
keyPassword=…
```

`storeFile` is relative to `app/`. Then:

```bash
./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab, for Google Play
./gradlew assembleRelease   # a signed APK, for sideloading
```

Never commit keystores or signing passwords: `*.keystore` and `keystore.properties` are
git-ignored.

### Google Play listing

[`store/README.md`](store/README.md) lists everything the Play Console asks for and where it is:
the listing text and graphics in `fastlane/metadata/android/en-US/`, the privacy policy
([`PRIVACY.md`](../PRIVACY.md)), and the answers for Data safety, content rating and the
foreground-service declaration. The screenshots are captured from the real app by the **Store
screenshots** workflow.

## Tests

```bash
./gradlew testDebugUnitTest            # JVM unit tests, no device needed
./gradlew connectedDebugAndroidTest    # end-to-end UI tests, needs a device or emulator
```

- **Unit tests** (`app/src/test`) use Robolectric. They cover the reference parser, queue
  building, the learned-list store, asset integrity (every timing file is re-validated), the
  Arabic matching rules.
- **Instrumented tests** (`app/src/androidTest`) cover browsing, marking, settings and the
  word-by-word reader.
- `tools/device-test.ps1` installs once and re-runs instrumented tests via `am instrument`. It is
  much faster when iterating on a phone, and it keeps the screen awake during the run.

## Project structure

```
app/src/main/java/com/quran/learnedplayer/
├── MainActivity.kt, LearnedAyahsApp.kt
├── service/     PlaybackService (Media3 session + ExoPlayer), PlayerStateHolder (shared state)
├── player/      PlayerViewModel, PlayerSettings
├── data/        Learned list & bookmarks, reference parser, export format, Qur'an data,
│                reciters, downloads
└── ui/          Compose screens: home (surah list), surah detail, player, playlist,
                 word-by-word, settings, theme
app/src/main/assets/   quran_text.json, word_translations.json, word_timings_<reciter>.json
.setup/                Generators for the bundled assets (see .setup/README.md)
tools/                 device-test.ps1
CLAUDE.md              Detailed architecture and design decisions
```

Read [CLAUDE.md](CLAUDE.md) before working on playback or word timings. It
explains the non-obvious decisions and the bugs they prevent.

## Troubleshooting

- **The player is empty.** Mark some ayahs first, or switch to **Full surah** mode, which works
  with nothing marked.
- **Audio fails to load.** Ayah audio streams from everyayah.com. Check your connection, or save
  your learned ayahs for offline use in Settings.
- **Playback stops in the background.** Disable battery optimisation for the app. Some vendors,
  such as Xiaomi/MIUI and Samsung, kill background audio aggressively.
- **A word isn't highlighted.** A few ayahs have no validated timings for some reciters. These
  are left unhighlighted rather than guessed.

The player’s **Actions** menu contains **Mode** (Learned ayahs or Full surah), Tafsir, Share, Background and Settings; playback controls remain in the footer. Juz queues and multi-ayah pages are not offered yet.
