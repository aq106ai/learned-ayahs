# Learned Ayahs: Android app

A standalone Android app for revising the ayahs **you** have memorised. Browse the 114 surahs,
mark the ayahs you know, and the app plays them back so you can listen, repeat, follow word by
word, and recite from memory.

- **Package:** `com.quran.learnedplayer` · **Current version:** 1.9.2
- **Requires:** Android 8.0 (API 26) or newer. Recite & review needs Android 13 (API 33).
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
| **Word by word** | Your marked ayahs, one word at a time, with the English meaning of each word |

Each mode combines with a **repeat** setting: **Off**, **Surah** (loop the surah) or **Ayah**
(loop the current ayah, with an optional pause between repeats so you can recite it yourself).

- A'udhu billah and Bismillah play before ayah 1 of each surah. Surah 9 has no Bismillah.
- **Word highlighting** follows the recitation using real per-word timings for the selected
  reciter. Long ayahs auto-scroll or shrink to fit.
- **Reciters:** Maher Al Muaiqly (default), Abdul Basit (Murattal), Mahmoud Khalil Al-Husary,
  Abdurrahman As-Sudais, Saud Ash-Shuraym.
- The app remembers where you left off, even across restarts.

### Recite & review (beta, opt-in)

Turn it on in **Settings → Recitation & AI Review**. Then recite a learned ayah from memory, and
the app listens through your phone's own Arabic speech recognition:

- correctly recited words light up as you go;
- if you really say a wrong word or skip one, the app plays the correct word from the reciter
  and lets you continue;
- at the end of the surah you get a recap of the words to work on, and can tap them to hear them
  again.

Speech recognition is built for everyday Arabic, not tajwid-precise recitation. Treat this as
practice help, not as an authority on whether your recitation is correct. The microphone is used
only while the Recite screen is open. On supported phones you can download the offline Arabic
speech pack from Settings.

### Controls and offline use

- Play/pause/next/previous from the **lock screen, notification, Bluetooth headsets and car
  systems**. These go through the same logic as the on-screen buttons. With **Repeat: Ayah** on,
  Next always advances, so a headset button moves you to the next ayah.
- Optional **swipe to navigate** and **tap to advance** gestures.
- **Settings → Offline** saves your learned ayahs' audio (and word clips) to the phone. The full
  Qur'an text, the word-by-word translation and the word timings are built into the app.
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

```bash
./gradlew assembleRelease
```

Sign it with your own keystore. Never commit keystores or signing passwords. `*.keystore` is
git-ignored.

## Tests

```bash
./gradlew testDebugUnitTest            # JVM unit tests, no device needed
./gradlew connectedDebugAndroidTest    # end-to-end UI tests, needs a device or emulator
```

- **Unit tests** (`app/src/test`) use Robolectric. They cover the reference parser, queue
  building, the learned-list store, asset integrity (every timing file is re-validated), the
  Arabic matching rules, and a full Recite & review run driven by transcripts for every
  reciter.
- **Instrumented tests** (`app/src/androidTest`) cover browsing, marking, settings, the
  word-by-word reader and the recitation screen. `FullSurahRecitationTest` plays the recordings
  in `androidTest/assets/recitation/` aloud into the microphone, so run it in a quiet room.
- `tools/device-test.ps1` installs once and re-runs instrumented tests via `am instrument`. It is
  much faster when iterating on a phone, and it keeps the screen awake during the run.

## Project structure

```
app/src/main/java/com/quran/learnedplayer/
├── MainActivity.kt, LearnedAyahsApp.kt
├── service/     PlaybackService (Media3 session + ExoPlayer), PlayerStateHolder (shared state)
├── player/      PlayerViewModel, PlayerSettings, RecitationViewModel, speech + word audio players
├── data/        Learned list & bookmarks, reference parser, export format, Qur'an data,
│                reciters, downloads, recitation evaluation (normaliser, aligner, tuning)
└── ui/          Compose screens: home (surah list), surah detail, player, playlist,
                 word-by-word, recitation, settings, theme
app/src/main/assets/   quran_text.json, word_translations.json, word_timings_<reciter>.json
.setup/                Generators for the bundled assets (see .setup/README.md)
tools/                 device-test.ps1
CLAUDE.md              Detailed architecture and design decisions
```

Read [CLAUDE.md](CLAUDE.md) before working on playback, word timings or Recite & review. It
explains the non-obvious decisions and the bugs they prevent.

## Troubleshooting

- **The player is empty.** Mark some ayahs first, or switch to **Full surah** mode, which works
  with nothing marked.
- **Audio fails to load.** Ayah audio streams from everyayah.com. Check your connection, or save
  your learned ayahs for offline use in Settings.
- **Playback stops in the background.** Disable battery optimisation for the app. Some vendors,
  such as Xiaomi/MIUI and Samsung, kill background audio aggressively.
- **Recite & review is missing.** Turn it on in Settings. It requires Android 13+ and a speech
  service that supports Arabic. Settings explains what your phone supports.
- **A word isn't highlighted.** A few ayahs have no validated timings for some reciters. These
  are left unhighlighted rather than guessed.
