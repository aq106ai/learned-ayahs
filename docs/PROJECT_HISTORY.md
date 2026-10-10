# Learned Ayahs Player — Project History

> **Historical document.** This is the original development log, covering the web player and
> Android app up to roughly v1.0.26 (June–July 2026). Much of it has since changed: the app is now
> at v1.9.2, the learned list is marked in-app instead of read from a library export, the
> Downloads scan and the personal "default supplement" were removed in v1.4.0, and word-by-word
> highlighting is back. For the current design see
> [`android-app-quran-player/CLAUDE.md`](../android-app-quran-player/CLAUDE.md) and the
> [CHANGELOG](../CHANGELOG.md). The original desktop web player described below
> (`quran-player/`, later `web-app-quran-player/`) has been removed; the [web app](../web-app/)
> replaces it and can still import its library exports.

Complete log of features requested, implemented, fixed, and abandoned across the web player and Android app.  
Compiled from development chat sessions (Jun 28 – Jul 6, 2026).

**Latest Android APK:** `LearnedAyahsPlayer-v1.0.25-debug.apk`  
**Latest web player:** `quran-player/player.html` (v2.5 template)  
**Reciter:** Maher Al Muaiqly — `MaherAlMuaiqly128kbps` from everyayah.com  
**Library source:** `quran_library 28 Jun 2026 07.20.05.json` (Every Learned Ayah collections)

---

## Table of contents

1. [Project overview](#project-overview)
2. [Chronological request log](#chronological-request-log)
3. [Web player (`quran-player/`)](#web-player-quran-player)
4. [Android app (`android-app-quran-player/`)](#android-app-android-app-quran-player)
5. [Bugs encountered and fixes](#bugs-encountered-and-fixes)
6. [Features that failed or were removed](#features-that-failed-or-were-removed)
7. [APK version history](#apk-version-history)
8. [How to run](#how-to-run)
9. [Known limitations](#known-limitations)

---

## Project overview

**Goal:** Download and play all bookmarked “Every Learned Ayah” verses in correct Quran order, gapless or with revision/loop modes, using Maher Al Muaiqly recitation.

**Initial scope:** 673 unique learned ayahs from 5 “Every Learned Ayah” folders in the library JSON.  
**Expanded scope:** Default supplement surahs added (36, 55, 56, 61, 62, 67, 70–114, and 69:1–43) → ~1841 ayahs total in playlist.

---

## Chronological request log

| Date (approx) | Request | Outcome |
|---------------|---------|---------|
| Start | Download & play learned ayahs gapless from JSON | ✅ Web player + downloader built |
| Start | `Failed to fetch` opening HTML | ✅ Fixed — embedded playlist + local HTTP server |
| Start | Continuous playback stopping after each ayah | ✅ Fixed — `ended` listener on both audio elements |
| Start | Revision mode (repeat ayah until advance) | ✅ Added (web + Android) |
| Jun 28 | Standalone Android APK | ✅ Built `android-app-quran-player` |
| Jun 28 | App crashes on first start (Samsung A34, Android 16) | ✅ Multiple fixes — Compose slot table, permissions, crash screen |
| Jun 28 | Auto-scan Downloads not finding JSON | ⚠️ Partial — Pick file + all-files access; improved again in v1.0.25 |
| Jun 28 | Two progress sliders | ✅ Fixed — single seek bar |
| Jun 30 | Stale playlist after new JSON export | ✅ Fixed — picks newest file by timestamp |
| Jun 30 | Surah loop mode + Surah learned mode | ✅ Added (4 modes total) |
| Jun 30 | Dark green theme + offline download | ✅ Added |
| Jun 30 | Versioned APK filenames | ✅ `LearnedAyahsPlayer-vX.Y.Z-debug.apk` |
| Jun 30 | Expandable playlist UI | ✅ Added (web); Android reworked in v1.0.22+ |
| Jul 5 | Port Android features to web + word tracking | ✅ Web v2.x with ayah text + word highlight |
| Jul 5 | Quran text stuck at 50/673 | ✅ Fixed — batch download / skip logic |
| Jul 5 | Playlist not showing after pick | ✅ Fixed |
| Jul 5 | Pause button in revision mode (web) | ✅ Fixed |
| Jul 5 | Default supplement surahs in playlist | ✅ Added (web + Android) |
| Jul 5 | Exact word-by-word sync (not approximate) | ✅ Web — `word_timings.json` from QUL API |
| Jul 5 | Skip re-download if files exist | ✅ `--skip-download`, cached text/timings |
| Jul 5 | Fullscreen ayah text view | ✅ Web + Android |
| Jul 5 | Prev/Next button order (LTR) | ✅ Fixed |
| Jul 5 | First Play stuck after refresh | ✅ Fixed (web) |
| Jul 5 | Fullscreen surah/ayah meta | ✅ Added |
| Jul 5 | A'udhu Billah on first play | ✅ Evolved → A'udhu + Bismillah on every new surah ayah 1 |
| Jul 5 | Grouped playlist + surah jump strip | ✅ Web + Android |
| Jul 5 | Surah names with numbers | ✅ `SurahNames.kt` / web `SURAH_NAMES` |
| Jul 5 | Cache A'udhu + Bismillah offline | ✅ Web download script + Android `AudioDownloader` |
| Jul 5 | Android scroll to playlist / RTL jumbled text / word sync wrong | ✅ v1.0.20–21 fixes attempted |
| Jul 5 | No word tracking visible on Android | ❌ Removed in v1.0.22 — kept plain ayah text only |
| Jul 5 | Fullscreen tap-to-advance + exit button (Revision) | ✅ Android v1.0.19+ |
| Jul 5 | Playlist too long on main screen | ✅ v1.0.22 — separate playlist page + preview |
| Jul 5 | Slow scroll to last surah in playlist | ✅ v1.0.23 — one surah at a time; v1.0.24 — surah picker sheet |
| Jul 5 | Keep screen on while playing | ✅ v1.0.23 |
| Jul 5 | Version label showed v1.0.20 while APK was newer | ✅ Fixed — uses `BuildConfig.VERSION_NAME` |
| Jul 6 | Refresh cannot find latest JSON in Downloads | ✅ v1.0.25 — broader scan + permission + saved URI fallback |
| Jul 6 | Export complete chat history | ✅ This document |

---

## Web player (`quran-player/`)

### Core (working)

- **`play_learned_ayahs.py`** — Parses library JSON, merges learned + default supplement ayahs, downloads MP3s from everyayah.com, generates playlist/M3U/player HTML.
- **Gapless playback** — Dual `<audio>` elements with preload/swap.
- **Local HTTP server** — Default `http://127.0.0.1:8765/player.html` (avoids `file://` fetch/CORS issues).
- **Embedded playlist** — JSON inlined in HTML for offline file open (`--file-only`).

### Playback modes (working)

| Mode | Behavior |
|------|----------|
| **Continuous** | All ayahs in order, auto-advance |
| **Revision** | Repeat current ayah; Next / tap / forward advances |
| **Surah loop** | Whole surah repeats; Next/Prev = next/prev surah |
| **Surah learned** | Learned ayahs of current surah loop; Next/Prev = surah jump |

### UI features (working)

- Seek bar with time display
- Expand playlist (compact player bar when expanded)
- Grouped playlist by surah (Continuous/Revision) with horizontal surah jump chips
- Surah names everywhere
- Fullscreen ayah text with Prev / Pause / Next
- Fullscreen meta: surah name + ayah number
- Exit fullscreen button
- Tap body to advance in Revision (fullscreen)
- Compact player bar when playlist expanded

### Ayah text & word sync (working on web)

- **`quran_text.json`** — Bundled/cached Uthmani word text per ayah
- **`word_timings.json`** — Exact timings from QUL API (recitation ID 13, matches everyayah Maher)
- Word highlight during recitation (exact when timings available, approximate fallback)
- Intro clips: **`audhubillah.mp3`** + **`bismillah.mp3`** from everyayah.com root (not reciter folder)
- Plays A'udhu → Bismillah before ayah 1 of each surah (skip Bismillah for surah 9)

### Default supplement ayahs (always merged)

- Full surahs: **36, 55, 56, 61, 62, 67, 70–114**
- Partial: **69:1–43**
- Survive JSON refresh (merged, not deleted)

### CLI flags (useful)

```powershell
python play_learned_ayahs.py --skip-download      # Don't re-download MP3s
python play_learned_ayahs.py --skip-text          # Skip quran_text.json fetch
python play_learned_ayahs.py --skip-timings       # Skip word_timings.json fetch
python play_learned_ayahs.py --no-open            # Don't open browser
python play_learned_ayahs.py --file-only          # Generate HTML only, no server
```

---

## Android app (`android-app-quran-player/`)

### Core (working)

- Scans **Downloads** for latest `quran_library*.json` (or uses **Pick file** / saved URI)
- Same playlist logic as web (learned folders + default supplement → ~1841 ayahs)
- **ExoPlayer** + foreground **PlaybackService** + MediaSession
- Bluetooth / lock screen: play, pause, next, previous
- Offline: download learned ayahs + A'udhu + Bismillah intro clips
- Bundled **`quran_text.json`** for ayah text offline (no word timings in app after v1.0.22)
- Dark green theme
- Version in header via **`BuildConfig.VERSION_NAME`**

### Playback modes (working)

Same four modes as web (Continuous, Revision, Surah loop, Surah learned).

### UI features (working)

- Main screen: player, ayah text panel, **current surah / nearby ayahs preview**
- **Open playlist** → full-screen playlist page
- Playlist shows **one surah at a time** (fast switching)
- **Go to surah** bottom sheet: type surah number (1–114) or pick from learned surah list
- Fullscreen ayah text; tap to advance in Revision; exit fullscreen button
- Surah intro chain on ayah 1: A'udhu → Bismillah (no Bismillah for surah 9)
- **Keep screen on** while playing
- Crash log screen on startup failure (for debugging on device)

### Removed / not shipped on Android

- **Word-by-word highlight sync** — Attempted v1.0.14–21; unreliable on Compose (SpanStyle/FlowRow/RTL issues). Removed in v1.0.22. **Re-implemented in v1.0.26** using the web player's approach: single RTL `AnnotatedString` with per-word color spans (keeps Arabic shaping), exact segments from bundled `word_timings.json` (QUL fallback online, approximate character-weight fallback offline), position interpolated client-side between service ticks.
- **`word_timings.json` in APK assets** — Removed with word sync; **restored in v1.0.26**.

---

## Bugs encountered and fixes

### Web

| Bug | Fix |
|-----|-----|
| `TypeError: Failed to fetch` | Embedded playlist + HTTP server |
| Stops after every other ayah | `ended` on both audio A/B |
| Quran text download stuck ~50/673 | Improved batching / resume |
| Playlist empty after file pick | Parser / load path fix |
| Pause broken in revision | Handler fix |
| First Play stuck after refresh | Ready-state / load fix |
| Re-downloads everything every run | `--skip-download`, file existence checks |
| Re-fetches quran text every run | Cache `quran_text.json` on disk |

### Android

| Bug | Fix |
|-----|-----|
| Immediate crash on launch (Android 16) | Compose early-return in `PlayerPanel`; crash handler |
| `ArrayIndexOutOfBoundsException` Compose slot table | Removed invalid `return@Column` patterns |
| Notification permission then crash | Permission flow ordering |
| Downloads scan not finding JSON | Pick file + persisted URI; v1.0.25 broader MediaStore scan |
| Two seek sliders | Removed duplicate |
| Stale library after new export | Newest file by filename timestamp |
| Heading not readable (dark on dark) | Theme text colors |
| Could not scroll to playlist | Single LazyColumn layout (later: separate playlist screen) |
| Arabic text jumbled | RTL single `Text` (later: plain text without highlight) |
| Word sync from Bismillah / last word | Intro suppresses highlight; timing alignment (then feature removed) |
| App opens scrolled to playlist | Scroll only while playing; scroll to top on load |
| Full playlist in main scroll (1841 items) | Preview + separate playlist page (v1.0.22) |
| Slow surah chip scroll | One-surah view + surah picker sheet (v1.0.23–24) |
| Refresh not finding JSON | v1.0.25 scan + storage permission + saved file fallback |
| Version label wrong | `BuildConfig.VERSION_NAME` |

---

## Features that failed or were removed

| Feature | Platform | Status | Notes |
|---------|----------|--------|-------|
| Word-by-word exact highlight | Android | **Removed v1.0.22** | FlowRow/AnnotatedString/ExoPlayer timing never reliable enough |
| Word timings bundled in APK | Android | **Removed** | ~230 KB; unused after highlight removed |
| Horizontal surah chip strip (full list) | Android playlist | **Replaced** | Too slow to scroll to surah 114; replaced by picker sheet |
| Expand-in-place playlist (1841 rows) | Android main screen | **Removed** | Caused enormous scroll; split into preview + full page |
| Open web player on phone via PC Wi‑Fi | Web | **Not implemented** | Server binds `127.0.0.1` only; user directed to VLC M3U or Android app |
| A'udhu on first play only | Web/Android | **Superseded** | Changed to A'udhu + Bismillah on every surah start (ayah 1) |

---

## APK version history

| Version | Highlights |
|---------|------------|
| v1.0.5–12 | Initial Android app, revision mode, MediaSession |
| v1.0.13–15 | Crash fixes, Downloads scan, theme, offline download |
| v1.0.16–18 | Four modes, supplement surahs, UI polish |
| v1.0.19 | Fullscreen tap-advance, exit button, intro chain |
| v1.0.20 | RTL text, word sync attempt, scroll-on-play-only |
| v1.0.21 | AnnotatedString highlight, faster progress ticks |
| v1.0.22 | **Removed word sync**; playlist preview + full playlist page; version string fix |
| v1.0.23 | One surah per playlist view; keep screen on |
| v1.0.24 | Surah picker sheet (number input + list) |
| v1.0.25 | Downloads Refresh fix (MediaStore + permission + saved URI fallback) |
| v1.0.26 | Mode-switch fixes (intro state, position kept on Continuous↔Revision, nearest-ayah on surah-mode exit); collapsible all-surah playlist; word-by-word highlight (exact `word_timings.json` + approximate fallback); smooth seek bar; Revision-only tap-to-advance |

APK naming: `LearnedAyahsPlayer-v{versionName}-debug.apk`  
Project root copies provided for easy sideloading.

---

## How to run

### Web player (PC)

```powershell
cd quran-player
python play_learned_ayahs.py --skip-download --skip-text --skip-timings
```

Keep terminal open. Open `http://127.0.0.1:8765/player.html`.

### Android

1. Install latest APK from project root.
2. Export `quran_library … .json` to phone **Downloads**.
3. Open app → **Scan Downloads** or **Pick file** → **Play**.
4. If scan fails: enable **all-files access** (link in app) or pick file once (remembered for Refresh).

### Build Android APK

```powershell
cd android-app-quran-player
.\gradlew.bat assembleDebug
# Output: app\build\outputs\apk\debug\LearnedAyahsPlayer-v1.0.25-debug.apk
```

---

## Known limitations

1. **Android word tracking** — Not supported; use web player for word highlight.
2. **Android Downloads scan on Android 11+** — May require all-files access or one-time Pick file; Refresh falls back to last picked file.
3. **Web on phone** — Not served over LAN without changing server bind address.
4. **Surah 9** — Bismillah skipped in intro chain (standard recitation practice).
5. **Intro MP3 URLs** — `audhubillah.mp3` / `bismillah.mp3` live at `https://everyayah.com/data/` (not in reciter subfolder).
6. **Exact word timings** — QUL recitation ID 13 matched to everyayah Maher Al Muaiqly 128kbps folder; small ayah/word count mismatches possible on some verses.
7. **Playlist size** — ~1841 ayahs after default supplement merge; full list only on dedicated playlist screen.

---

## Key files reference

| Path | Purpose |
|------|---------|
| `quran-player/play_learned_ayahs.py` | Download, merge, generate player |
| `quran-player/player_template.html` | Web player source (regenerates `player.html`) |
| `quran-player/quran_text.json` | Ayah words (web + was Android) |
| `quran-player/word_timings.json` | Exact word timings (web only) |
| `quran-player/quran_library 28 Jun 2026 07.20.05.json` | Source library export |
| `android-app-quran-player/app/src/main/java/.../PlaybackService.kt` | Audio + intro state machine |
| `android-app-quran-player/app/src/main/java/.../DownloadsScanner.kt` | Downloads JSON discovery |
| `android-app-quran-player/app/src/main/java/.../SurahJumpSheet.kt` | Surah number picker UI |
| `android-app-quran-player/app/src/main/assets/quran_text.json` | Offline ayah text (Android) |

---

## Recitation & timing alignment

- **Audio:** `https://everyayah.com/data/MaherAlMuaiqly128kbps/{SSSAAA}.mp3`
- **Word timings:** QUL API `recitation_id=13` — same Maher everyayah-style segments
- **Intro:** `https://everyayah.com/data/audhubillah.mp3`, `bismillah.mp3`

---

*Document generated Jul 6, 2026. Update this file when major features or versions change.*
