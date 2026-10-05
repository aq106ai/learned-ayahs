# Learned Ayahs: desktop web player

A small Python script plus a single-page HTML player for revising your learned ayahs on a PC.
It reads a Qur'an-app **library export**, builds a playlist of the ayahs in your
"Every Learned Ayah" folders, downloads the recitation (Maher Al Muaiqly, from everyayah.com),
and serves a local, gapless player in your browser.

This was the project's first player. The [Android app](../android-app-quran-player/) has since
grown well beyond it, but the web player is handy at a desk.

## Requirements

- Python **3.9+**. Only the standard library is used, so there is nothing to `pip install`.
- A modern browser.
- An internet connection on the first run, to download audio, text and timings. After that it
  works offline.

## Quick start

```bash
cd web-app-quran-player
python play_learned_ayahs.py
```

That's it. The script will:

1. find your library export (see below), or fall back to the bundled `sample_library.json`;
2. write `learned_ayahs_playlist.json`, `learned_ayahs.m3u` and `player.html`;
3. fetch ayah text (`quran_text.json`) and exact word timings (`word_timings.json`);
4. download the ayah MP3s plus A'udhu billah / Bismillah into `audio/`;
5. start a local server at <http://127.0.0.1:8765/player.html> and open it.

Keep the terminal open while listening, and press Ctrl+C to stop.

Everything in steps 2–4 is generated, personal to your library, and git-ignored.

## Your library export

The player reads the library JSON exported by your Qur'an app, a file named like
`quran_library 28 Jun 2026 07.20.05.json`. It contains `folders`, `items` and `notes`. Every
non-deleted folder whose title contains **"Every Learned Ayah"** contributes its ayahs; item `id`
is the global ayah number (1–6236).

- Put the export in this folder. The **newest** `quran_library*.json` is used automatically,
  judged by the timestamp in its name.
- Or point at it explicitly: `python play_learned_ayahs.py --library path/to/export.json`.
- With no export present, `sample_library.json` is used. It contains Al-Fatihah, Ayat al-Kursi
  and Al-Ikhlas, so you can try the player out.

> **Note:** the player currently also merges a fixed set of "default supplement" surahs
> (36, 55, 56, 61, 62, 67, 70–114 and 69:1–43) into every playlist. This is a leftover from the
> original author's personal setup. The Android app dropped it in v1.4.0, and the web player
> should follow (contributions welcome).

## Daily use

After the first run, start the player without re-processing anything:

```bash
python play_learned_ayahs.py --serve-only
```

After exporting a new library, regenerate the playlist but skip what is already downloaded:

```bash
python play_learned_ayahs.py --skip-download --skip-text --skip-timings
```

Missing audio is downloaded on a normal run. Files that already exist are never fetched again.

### Options

| Flag | Effect |
|---|---|
| `--library PATH` | Library export to read |
| `--serve-only` | Just start the server for the existing `player.html` |
| `--skip-download` | Don't download ayah MP3s |
| `--skip-text` | Don't fetch `quran_text.json` |
| `--skip-timings` | Don't fetch `word_timings.json` (word highlight sync) |
| `--text-all-surah-ayahs` | Also cache text for every ayah of your learned surahs (for Surah loop offline) |
| `--file-only` | Open `player.html` via `file://` instead of the local server |
| `--no-open` | Don't open a browser |
| `--port N` | Server port (default 8765) |
| `--workers N` | Parallel downloads (default 8) |

## Player features

| Mode | Behaviour |
|---|---|
| **Continuous** | All learned ayahs play in order |
| **Revision** | The current ayah repeats. Click the panel or Next to advance |
| **Surah loop** | The whole surah loops (streams online). Next/Prev jump surahs |
| **Surah learned** | Only this surah's learned ayahs loop. Next/Prev jump surahs |

- Gapless playback (two alternating audio elements).
- Ayah text with word-by-word highlight, using exact timings when available.
- A'udhu billah + Bismillah before ayah 1 of each surah (no Bismillah for surah 9).
- Playlist grouped by surah with quick surah jumping, plus a fullscreen ayah view.

## Windows one-click launch

See [QUICK_START.md](QUICK_START.md):

- `Start Player.bat` starts the server and opens the player;
- `Start Quran Player (background).vbs` does the same without a console window;
- `Setup Bookmark.bat` registers a `quranplayer:open` browser bookmark that launches the player.

## Files

| File | Committed? | Purpose |
|---|---|---|
| `play_learned_ayahs.py` | ✅ | Parses the library, downloads audio/text/timings, generates and serves the player |
| `player_template.html` | ✅ | Source of the player UI. The playlist is injected at `__PLAYLIST__` |
| `sample_library.json` | ✅ | Small example export |
| `*.bat`, `*.vbs` | ✅ | Windows launchers |
| `player.html`, `learned_ayahs_playlist.json`, `learned_ayahs.m3u` | ❌ generated | Your playlist |
| `quran_text.json`, `word_timings.json`, `audio/` | ❌ generated | Cached data and audio |
| `quran_library*.json`, `launcher.log` | ❌ personal | Your export, and the launcher's log |

## Notes

- The server binds to `127.0.0.1` only, so it is not reachable from other devices.
- Word timings come from QUL recitation 13 (Maher Al Muaiqly, matching everyayah's 128 kbps
  files). A few ayahs may not have timings. Those fall back to approximate highlighting.
