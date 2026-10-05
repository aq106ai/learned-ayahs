# Changelog

Notable changes to the Learned Ayahs Android app and web player. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the Android app uses
`versionName` from `android-app-quran-player/app/build.gradle.kts`.

## [Unreleased]

### Added
- Open-source release: MIT license, contributor docs, code of conduct, security policy,
  third-party notices, issue/PR templates and GitHub Actions CI.
- Web player: `sample_library.json`, so the player can be tried without a library export.

### Changed
- Web player: picks the newest `quran_library*.json` export automatically (or uses `--library`)
  instead of a hard-coded file name.
- `.setup/make_icon.py` finds the font relative to the repository instead of an absolute path.

### Fixed
- Android: the intro screen's last line had a stray "back to you." fragment appended.

## 1.9.1 — current Android release

Earlier milestones, as recorded in the project's design notes:

- **1.9.0**: Added the As-Sudais and Ash-Shuraym reciters, each with validated word timings.
- **1.8.0**: Removed the bundled offline Whisper model (whisper.cpp + NDK). Recite & review now
  uses the platform speech service, and the APK shrank from about 125 MB to 21 MB.
- **1.4.0**: The learned list is now marked in-app only. Removed the personal "default
  supplement", the Downloads scan and all storage permissions. Import/export moved to the Storage
  Access Framework.
- **1.1.0**: File read failures show a friendly message instead of a raw error.
- **1.0.26 and earlier**: See [docs/PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md).
