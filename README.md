# Learned Ayahs

**An open-source Qur'an app for memorising, revising and keeping the ayahs you have learned.**

> تَعَاهَدُوا هَذَا الْقُرْآنَ، فَوَالَّذِي نَفْسُ مُحَمَّدٍ بِيَدِهِ لَهُوَ أَشَدُّ تَفَلُّتًا مِنَ الْإِبِلِ فِي عُقُلِهَا
>
> "Keep refreshing your knowledge of the Qur'an, for by Him in Whose Hand my soul is, it is more
> liable to escape than camels which are tied." — *Sahih al-Bukhari 5033 · Sahih Muslim 791*

Most Qur'an apps are built for reading. Learned Ayahs is built for **revision**: you mark the
ayahs you have memorised, and the app turns them into a playlist you can listen to, repeat,
follow word by word, and recite back, so what you have learned stays with you.

[![CI](https://github.com/aq106ai/learned-ayahs/actions/workflows/ci.yml/badge.svg)](https://github.com/aq106ai/learned-ayahs/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

---

## Features

**Android app** (`android-app-quran-player/`, v1.9.1)

- **Mark what you know.** Browse all 114 surahs and tap the ayahs you have memorised. You can
  also add many at once by typing references such as `2:255`, `36:1-83`, `112` or
  `Al-Baqarah 255`, or by pasting a list. Nothing is ever marked for you. A new install starts
  empty.
- **Revise by listening.** Your learned ayahs play in mushaf order, with A'udhu billah and
  Bismillah before each new surah.
  - **Repeat ayah** loops the current ayah, with an optional pause, until you move on.
  - **Repeat surah** loops the surah.
  - **Full surah** plays every ayah of the surah, learned or not.
- **Five reciters.** Maher Al Muaiqly (default), Abdul Basit (Murattal), Mahmoud Khalil
  Al-Husary, Abdurrahman As-Sudais and Saud Ash-Shuraym.
- **Word-by-word highlighting.** It uses real per-word timings for each reciter's exact
  recording. There are no estimated timings: if an ayah has no validated timings, it simply
  isn't highlighted.
- **Word-by-word mode.** One word at a time with its English meaning, in your reciter's voice
  or as isolated word-pronunciation clips. Next/Previous, including on headsets, step word by
  word.
- **Recite & review (opt-in beta, Android 13+).** Recite from memory while the app listens
  through your phone's speech service. It plays the correct word only when you really say a
  wrong one, then gives a recap at the end of the surah.
- **Works away from the screen.** Lock screen, notification, Bluetooth headset and car
  controls all go through the same logic as the on-screen buttons.
- **Offline.** Save your learned ayahs, and the word clips for Recite & review, to the device.
  The full Uthmani text, word-by-word translation and word timings ship inside the app.
- **Back up and sync** your learned list as a small, human-readable JSON file through the system
  file picker. The app needs **no storage permissions**.
- Dark and light themes, swipe/tap gestures, and resuming where you left off.

**Desktop web player** (`web-app-quran-player/`)

A lightweight Python + HTML player for a PC. It reads a Qur'an-app library export, downloads the
ayah audio, and serves a local gapless player. It supports continuous, revision, surah-loop and
surah-learned modes, plus ayah text with word highlighting. See its
[README](web-app-quran-player/README.md).

---

## Repository layout

```
learned-ayahs/
├── android-app-quran-player/   Android app (Kotlin, Jetpack Compose, Media3)
│   ├── app/src/main/           App source, bundled Qur'an data assets, resources
│   ├── app/src/test/           JVM unit tests (Robolectric), no device needed
│   ├── app/src/androidTest/    On-device end-to-end tests + recitation audio corpus
│   ├── .setup/                 Scripts that (re)generate the bundled assets
│   ├── tools/                  Device test runner
│   └── CLAUDE.md               In-depth architecture & design notes, read before big changes
├── web-app-quran-player/       Desktop web player (Python + single-page HTML)
├── docs/                       Project history and background
└── .github/                    CI, issue & PR templates
```

---

## Getting started

### Android: build from source

Requirements: **JDK 17+** and the **Android SDK** (Android Studio installs both).

```bash
git clone https://github.com/aq106ai/learned-ayahs.git
cd learned-ayahs/android-app-quran-player

# Point Gradle at your SDK (Android Studio does this for you when you open the project)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
```

The APK is written to
`app/build/outputs/apk/debug/LearnedAyahsPlayer-v<version>-debug.apk`. Copy it to your phone and
install it. You may need to allow "Install unknown apps".

On Linux you can also run `bash .setup/build.sh`, which installs the Android SDK command-line
tools if they are missing and builds the debug APK in one step.

See the [Android README](android-app-quran-player/README.md) for usage, tests and
troubleshooting.

### Desktop web player

```bash
cd learned-ayahs/web-app-quran-player
python play_learned_ayahs.py          # uses the bundled sample library if you have no export
```

Then open <http://127.0.0.1:8765/player.html>. See the
[web player README](web-app-quran-player/README.md).

---

## How it works (short version)

- Ayahs are identified by a **global id** from 1 to 6236 across the whole Qur'an. Audio files
  follow everyayah.com's `SSSAAA.mp3` naming.
- Playback state lives in a Media3 `MediaSessionService`. The UI is a thin reflection of a single
  shared `StateFlow`, so external controllers and on-screen buttons share one code path.
- Word highlighting uses per-reciter timing files. `.setup/make_timings.py` generates them from
  [QUL](https://qul.tarteel.ai/), cleans them and validates them, and a unit test re-checks them.
- Recite & review compares what the speech recogniser heard against the Uthmani text. Spelling
  and word-boundary differences are handled in `ArabicTextNormalizer` and `ArabicWordAligner`.
  Several gates make sure the coach interrupts only for a mistake that is really yours.

The full design notes are in
[`android-app-quran-player/CLAUDE.md`](android-app-quran-player/CLAUDE.md). They record what was
tried, what failed, and why things are the way they are. Read them before changing playback,
timings or recitation.

---

## Contributing

Contributions are very welcome: bug reports, testing on different phones, translations, UI
polish, new features and documentation. Please read **[CONTRIBUTING.md](CONTRIBUTING.md)** first.
It covers the dev setup, the tests, and a few project rules, especially around the integrity of
the Qur'anic text and timings.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). To report a
security issue, see [SECURITY.md](SECURITY.md).

## Roadmap ideas

- Spaced-repetition scheduling: surface the ayahs you are most likely to forget.
- More reciters, once QUL has validated word timings for their exact recordings.
- More word-by-word translation languages.
- Publish signed releases on GitHub (and possibly F-Droid).
- Let the web player read the Android app's export format.

Have an idea? [Open a feature request](https://github.com/aq106ai/learned-ayahs/issues/new/choose).

## Credits & data sources

This project stands on the generous work of others:

- **Recitation audio:** [EveryAyah.com](https://everyayah.com/)
- **Word-by-word audio, Uthmani text and English word-by-word translation:**
  [Quran.com](https://quran.com/) API & CDN
- **Word timing segments:** [QUL, Quranic Universal Library](https://qul.tarteel.ai/) by Tarteel
- **Arabic font:** [Scheherazade New](https://software.sil.org/scheherazade/) by SIL
  International, under the SIL Open Font License 1.1

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details. These resources keep their own
terms. The MIT license below covers this project's source code.

## License

The source code is released under the [MIT License](LICENSE).

*May Allah make the Qur'an easy for us to learn, keep and act upon.*
