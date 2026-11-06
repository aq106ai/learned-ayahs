# CLAUDE.md — web app

Guidance for `web-app/` (vanilla JS served by `server.py`, or exported static). The Android app's
`android-app-quran-player/CLAUDE.md` explains the shared rules (timings, the learned list,
sources); most web modules are ports of an Android file named in their header comment.

## Tests

```bash
npm test                 # node:test unit tests (tests/unit)
npm run test:server      # server.py
npm run test:e2e         # Playwright, against server.py with seeded audio; no network
```

- **A hash link only queues `hashchange`.** After clicking a nav link, wait for the new view
  (`document.title`, a selector, or `location.hash`) before asserting on what the router changes.
- Stub providers with `openApp(..., { setup: (context) => context.route(...) })`; pass
  `context` to reopen in the same browser profile (Cache Storage included), as
  `tests/e2e/content.test.js` does to check offline reuse.

## Translations, word meanings and tafsir

- `core/catalog.js` (pure, unit-tested) reads `data/content_catalog.json`, the same file and rules
  as Android's `data/ContentCatalog.kt`. Settings hold ids: `translationEdition`,
  `wordMeaningLanguage`, `tafsirEdition`; style is `showWordTranslations` × `showAyahTranslation`.
- `services/content.js` loads editions (bundled Saheeh from `data/`, others from
  `api.quran.com/api/v4`, cleaned and kept in Cache Storage `la-content-v1`) and word meanings a
  surah at a time. **`sw.js` must list `la-content-v1` in its keep set**, or every service-worker
  update deletes the user's downloads.
- `server.py` only serves data files matching `DATA_FILES`, and its CSP `connect-src` must allow
  `https://api.quran.com`.
- The engine caches each ayah's words: when meanings arrive, `engine.refreshWords()` (wired in
  `main.js` via `onContentLoaded`) swaps them in and re-emits `track`.
- `views/contentChoices.js` is the picker the intro and Settings share (Android:
  `ui/settings/ContentChoices.kt`).
