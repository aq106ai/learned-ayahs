# Third-Party Notices

Learned Ayahs' **source code** is MIT-licensed (see [LICENSE](LICENSE)). The project also bundles
or downloads data, audio and fonts made by others. Those remain under their owners' terms, which
are summarised below with links to the originals. If you redistribute the app or its assets,
please check the current terms at each source.

If you are a rights holder and something here is attributed incorrectly or should not be
included, please [open an issue](https://github.com/aq106ai/learned-ayahs/issues) and it will be
corrected promptly.

## Bundled in the repository

| What | Where | Source | Terms |
|---|---|---|---|
| **Scheherazade New** Arabic font | `android-app-quran-player/app/src/main/res/font/scheherazade_new.ttf` | [SIL International](https://software.sil.org/scheherazade/) | SIL Open Font License 1.1. Full text in [`OFL_scheherazade_new.txt`](android-app-quran-player/app/src/main/assets/OFL_scheherazade_new.txt) |
| Qur'an text (Uthmani script, word by word, all 6236 ayahs) | `android-app-quran-player/app/src/main/assets/quran_text.json` | [Quran.com](https://quran.com/) API (`api.qurancdn.com`) | See Quran.com's terms |
| English word-by-word translation | `android-app-quran-player/app/src/main/assets/word_translations.json` | [Quran.com](https://quran.com/) API | See Quran.com's terms |
| Per-word timing segments for five reciters | `android-app-quran-player/app/src/main/assets/word_timings_*.json` | [QUL, Quranic Universal Library](https://qul.tarteel.ai/) by Tarteel, cleaned and validated by `.setup/make_timings.py` | See QUL's terms |
| Short recitation clips for automated tests (16 kHz WAV) | `android-app-quran-player/app/src/androidTest/assets/recitation/` | Derived from [EveryAyah.com](https://everyayah.com/) recordings by Maher Al Muaiqly, Abdurrahman As-Sudais and Saud Ash-Shuraym. One clip is spliced to contain a deliberate mistake for testing | Used only by the test suite. Not shipped in the app |

## Downloaded at runtime (not bundled)

| What | Source |
|---|---|
| Ayah recitation MP3s (Maher Al Muaiqly, Abdul Basit, Al-Husary, As-Sudais, Ash-Shuraym) | [EveryAyah.com](https://everyayah.com/data/) |
| A'udhu billah and Bismillah clips | [EveryAyah.com](https://everyayah.com/data/) |
| Isolated word pronunciation MP3s | [Quran.com](https://quran.com/) word audio CDN (`audio.qurancdn.com/wbw`) |
| Web player only: ayah text and word timings | Quran.com API and QUL API |

## Software dependencies

The Android app is built with open-source libraries resolved by Gradle, including AndroidX,
Jetpack Compose, Media3 (ExoPlayer), Kotlin coroutines, and for tests JUnit, Robolectric and
Espresso. These are mostly under the Apache License 2.0 (JUnit is under the Eclipse Public
License 1.0). See `android-app-quran-player/app/build.gradle.kts` for the exact list and
versions.

The Gradle wrapper (`gradlew`, `gradle/wrapper/`) is part of [Gradle](https://gradle.org/) and
is under the Apache License 2.0.
