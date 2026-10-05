# Contributing to Learned Ayahs

Jazakum Allahu khayran for wanting to help! This project exists to help people keep the Qur'an
they have memorised, and every contribution counts:

- reporting bugs, especially with the phone model and Android version;
- testing on your device, particularly **Recite & review** with real recitation;
- fixing bugs and building features;
- improving the docs, the UI wording or accessibility;
- adding translations.

Please be respectful and patient with one another. All participation is covered by our
[Code of Conduct](CODE_OF_CONDUCT.md).

## Ground rules

These rules come from real bugs in this project's history. The reasoning behind each is in
[`android-app-quran-player/CLAUDE.md`](android-app-quran-player/CLAUDE.md).

1. **The Qur'anic text and data must stay exact.** Never hand-edit `quran_text.json`,
   `word_translations.json` or any `word_timings_*.json`. They are generated (see
   [Regenerating assets](#regenerating-assets)) and verified by tests. If you think the data is
   wrong, open an issue with the surah:ayah and the source you are comparing against.
2. **No estimated word timings.** Only add a reciter if QUL has segment data for that exact
   recording *and* everyayah's audio matches it. Highlighting a word at a guessed time teaches
   the wrong thing.
3. **The learned list belongs to the user.** Never mark ayahs on the user's behalf, and never
   ship anyone's personal list as a default.
4. **No storage permissions.** Import and export go through the Storage Access Framework. Do
   not add `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` or similar.
5. **Respect the comments in `PlaybackService`.** They document ExoPlayer timing races that were
   fixed on purpose. Don't "simplify" them away.
6. **Recite & review stays opt-in** and must only interrupt for a mistake that is certainly the
   reciter's. Don't loosen the matching to make tests pass (CLAUDE.md explains why).
7. **Never commit personal data**: your library exports, generated playlists, logs, API keys or
   signing keys. `.gitignore` covers the usual ones, but please check your diff.

## Development setup

### Android app

Requirements: **JDK 17+**, the **Android SDK** (platform 34) and, ideally, Android Studio.

```bash
cd android-app-quran-player
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # Android Studio writes this for you
./gradlew assembleDebug
```

Stack: Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSession, coroutines/Flow.
`minSdk 26`, `targetSdk 34`.

Start with **`android-app-quran-player/CLAUDE.md`**. It is the architecture guide and it records
decisions that are easy to undo by accident.

### Web player

Requirements: **Python 3.9+**. It uses only the standard library.

```bash
cd web-app-quran-player
python play_learned_ayahs.py --help
```

`player_template.html` is the source; `player.html` is generated from it and is not committed.

## Tests

| Command (from `android-app-quran-player/`) | What it covers | Needs |
|---|---|---|
| `./gradlew testDebugUnitTest` | Domain logic, stores, asset integrity, the whole Recite & review flow driven by transcripts | Nothing (JVM + Robolectric) |
| `./gradlew connectedDebugAndroidTest` | Compose UI end-to-end: browsing, marking, settings, word-by-word reader, recitation | A device or emulator |
| `./tools/device-test.ps1 -Install -Class <test class>` | Same instrumented tests, but installs once and re-runs cheaply | A device, PowerShell |

Please run `./gradlew testDebugUnitTest` before opening a pull request. CI runs it on every push
and pull request, along with a debug build.

If you change UI flows, also run the instrumented tests on a real device if you can. Some of
them (`FullSurahRecitationTest`) play recitation audio aloud into the microphone, so run those
in a quiet room.

## Regenerating assets

The scripts in `android-app-quran-player/.setup/` produce committed assets. They are not part of
the Gradle build. See [`.setup/README.md`](android-app-quran-player/.setup/README.md).

```bash
pip install uharfbuzz fonttools matplotlib soundfile numpy scipy
python .setup/make_timings.py                 # word_timings_<reciter>.json from QUL
python .setup/make_recitation_corpus.py       # androidTest recitation audio
python .setup/make_icon.py                    # launcher icon vector drawables
```

Commit the regenerated output together with the change that required it, and make sure
`TimingDataIntegrityTest` still passes.

## Branches

| Branch | Purpose |
|---|---|
| `main` | Stable. Releases are cut from here, and it only changes by merging `dev` |
| `dev` | Integration branch. **Open pull requests against `dev`** |

Every push and pull request runs CI (unit tests, debug build, web player check). Merging into
`main` also runs the slower **Android extended checks**: instrumented tests on an emulator, plus
an Android Lint report. You can start those by hand from the Actions tab on any branch.

## Making a change

1. **Open an issue first** for anything bigger than a small fix, so we can agree on the approach.
2. Fork the repo and create a branch from `dev`, for example `fix/word-highlight-2-255` or
   `feat/spaced-repetition`, and open your pull request against `dev`.
3. Keep pull requests focused. One logical change per PR is much easier to review.
4. Add or update tests for behaviour changes. The JVM tests in `app/src/test` are the cheapest
   place to pin down logic.
5. Match the surrounding code style: Kotlin official code style (`kotlin.code.style=official`),
   and comments that explain *why*, not *what*.
6. For a user-visible release, bump `versionCode` **and** `versionName` in
   `app/build.gradle.kts` and add an entry to [CHANGELOG.md](CHANGELOG.md).
7. Fill in the pull request template, including screenshots or a short screen recording for UI
   changes.

### Commit messages

Use short, imperative subjects ("Fix highlight drift on 2:21", "Add Sudais timings") and explain
the reason in the body when it isn't obvious.

## Reporting bugs

Use the [bug report template](https://github.com/aq106ai/learned-ayahs/issues/new/choose) and
include:

- the app version (shown in Settings → About) and your phone model / Android version;
- the reciter, playback mode and repeat mode;
- the surah:ayah involved, if any;
- steps to reproduce, what you expected, and what happened;
- whether it happened online or offline.

## License

By contributing, you agree that your contributions will be licensed under the project's
[MIT License](LICENSE).
