# Changelog

Notable changes to the Learned Ayahs Android app and web app. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the Android app uses
`versionName` from `android-app-quran-player/app/build.gradle.kts`.

## [Unreleased]

### Added
- **Web app** (`web-app/`): the Android app's features in the browser.
  - Revision, Full surah and Word by word, with every repeat mode and the pause before repeat.
  - The intro rules, the five reciters and validated word highlighting.
  - The word-by-word reader, the surah and playlist panels, and gestures.
  - Media Session controls, Wake Lock and offline audio.
  - Recite & review (beta).
  - It adds accounts with sync across devices, user management for administrators, and
    import/export in the Android app's file format.
  - Runs from one standard-library Python file, or as a static build without accounts.
- Open-source release: MIT license, contributor docs, code of conduct, security policy,
  third-party notices, issue/PR templates and GitHub Actions CI.

### Changed
- `.setup/make_icon.py` finds the font relative to the repository instead of an absolute path.

### Fixed
- Android: the intro screen's last line had a stray "back to you." fragment appended.

### Removed
- The original desktop web player (`web-app-quran-player/`). The web app does everything it
  did, and can still import its `quran_library` exports.

## 1.9.2 — Android

### Fixed
- **A crash with a flood of notifications during playback.** Media3 and the app both ran the
  playback notification under the same id. Media3 also re-started the service on every player
  event, so a session (word-by-word above all) posted thousands of notifications. Media3 also
  dropped the foreground service on every pause, so the next promotion from the background, for
  example after a "pause before repeat" gap with the screen off, crashed the app on Android 12+.
  The app now owns its one notification and its foreground state, posts only when the content
  changes, never crashes entering the foreground, and no longer restarts itself in a crash loop.
- **On phones set to Arabic, Persian or Bengali, no audio could play.** File names were written with
  the phone's own digits ("٠٠٢٢٥٥.mp3"), so every recitation URL failed.
- Word by word: words are cut at the next word's start, so very short words are no longer skipped
  or looped silently under Repeat: Word; an ayah without timings plays once, not once per word.
- Ayah text in the word reader could stay on "Loading ayah text…". Changing reciter now reloads
  the word timings.
- An interrupted download could leave a truncated MP3 that was treated as saved. Downloads now
  complete into place and must be audio.
- Auto Backup stopped once saved audio pushed the app past Android's 25 MB limit. It now backs up
  only your learned ayahs, bookmarks and settings.
- 2:181: Recite & review can finish the ayah (its number was stored as a word), and its word
  highlighting is aligned. 8:6 and 13:37 had misaligned highlighting and are unhighlighted until
  their timings are regenerated.
- The notification and lock screen named Maher Al Muaiqly whatever the chosen reciter.
- With nothing marked yet, tapping an ayah in a surah did not play the surah; the player went
  back to "No ayahs marked yet". Full surah now plays without a learned list.
- With swiping turned off, a swipe across the reader still stepped it like a tap (tap to
  advance). A swipe is no longer a tap.

### Added
- Audio focus (pauses for calls and other apps), pausing when headphones are unplugged, and a
  wake lock for streaming with the screen off.
- The crash screen explains what happened and has Copy and Share buttons for the report.

### Changed
- Targets Android 16 (API 36), built with AGP 8.10, Gradle 8.11 and Kotlin 2.0. Screens stay inside
  the safe area under Android 15+'s edge-to-edge drawing.

### Removed
- Cleartext (HTTP) traffic permission; every source is HTTPS.

## 1.9.1

Earlier milestones, as recorded in the project's design notes:

- **1.9.0**: Added the As-Sudais and Ash-Shuraym reciters, each with validated word timings.
- **1.8.0**: Removed the bundled offline Whisper model (whisper.cpp + NDK). Recite & review now
  uses the platform speech service, and the APK shrank from about 125 MB to 21 MB.
- **1.4.0**: The learned list is now marked in-app only. Removed the personal "default
  supplement", the Downloads scan and all storage permissions. Import/export moved to the Storage
  Access Framework.
- **1.1.0**: File read failures show a friendly message instead of a raw error.
- **1.0.26 and earlier**: See [docs/PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md).
