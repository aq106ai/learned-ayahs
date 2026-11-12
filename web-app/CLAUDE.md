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
- Tafsir: `views/tafsir.js` (player header's book button) shows `content.ayahTafsir`, whose HTML
  `core/tafsirText.js` has turned into plain blocks; the view builds its own elements and sets only
  textContent. Never insert provider HTML (`innerHTML`, `DOMParser` into the page, …).
- Mushaf style: `main.js` sets `--quran-font` from `content.mushafFamilySync()`; Arabic CSS uses
  `var(--quran-font)`, video frames `request.arabicFont`. Fonts are served from the Android app's
  `res/font` (`QURAN_FONTS` in server.py, also copied by `--export-static`).
- App language (`core/i18n.js`, `views/uiLanguage.js`): every shown string is `tx('English', {name})`.
  The local variable `t` is the current track all over the views, which is why the function is
  `tx`. The language is read synchronously when i18n.js loads (localStorage `la.uiLanguage`, else
  the browser's) so module-level labels are already translated; changing it reloads the page.
  `<html lang dir>` is set at boot, and the CSS uses logical properties (`inset-inline-start`,
  `padding-inline-start`, `text-align: start`) — a physical `left`/`right` breaks right to left
  (a skip link at `left: -999px` widened the page by 999 px). Tables are generated:
  `android-app-quran-player/.setup/make_ui_strings.py`. `surahName` is Arabic script in Urdu and
  Arabic; `surahNameLatin` is for the ref parser and search.
- `views/contentChoices.js` is the picker the intro and Settings share (Android:
  `ui/settings/ContentChoices.kt`).

## Player: views, word playback and colours

- What is on screen and what plays are separate. `settings.wordView` picks the word reader or the
  whole ayah. `engine.wordPlayback` (`settings.useWordClips || settings.repeatWord`) plays one
  word at a time in either view and either mode; there is no word-by-word `PlaybackMode`.
- The footer's Repeat row is one choice of four (Ayah, Surah, Word, Off): `engine.setRepeat`
  clears `repeatWord`, `engine.setRepeatWord` sets repeat to OFF. Word clips hides the reciter.
- `views/sceneColours.js` owns the scene palette: it is set on `<html>` so every page and dialog
  follows the player's background, and the last scene is kept in localStorage `la.sceneColours`.
  The player only adds the `scene` / `light-scene` classes that make its bars see-through.
