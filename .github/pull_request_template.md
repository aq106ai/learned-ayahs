## What does this change?

<!-- A short description of the change and why it's needed. Link the issue it addresses: "Fixes #123". -->

## How was it tested?

- [ ] `./gradlew testDebugUnitTest` passes (Android changes)
- [ ] Tried on a device or emulator (UI / playback changes). Phone model and Android version:
- [ ] Ran the web player locally (web changes)

## Screenshots / recordings

<!-- For UI changes, before and after. -->

## Checklist

- [ ] No hand edits to `quran_text.json`, `word_translations.json` or `word_timings_*.json` (regenerated with `.setup/` scripts if needed)
- [ ] No personal data (library exports, playlists, logs, keys) in the diff
- [ ] Docs updated if behaviour changed (README / CLAUDE.md / CHANGELOG)
- [ ] Version bumped in `app/build.gradle.kts` (only for a release)
