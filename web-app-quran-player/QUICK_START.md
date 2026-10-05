# Quick start (no Python command every time)

## One click: a browser bookmark

Bookmark this address in Chrome (or any browser):

```
quranplayer:open
```

Clicking it runs `launch_quran_player.vbs`, which:

- starts the local server silently (no console window) if it isn't already running, then
- opens `http://127.0.0.1:8765/player.html` in your browser.

Click it again anytime — if the server is already up it just opens the tab (it never starts a second copy). The first time you click from a browser, you'll get a one-time "Open wscript?" confirmation — allow it (tick "always allow").

**Setup (once per Windows account):** double-click **`Setup Bookmark.bat`**. It
registers the `quranplayer:` protocol pointing at this folder's current location
(no admin needed). Do this again after moving the folder or on a new computer.

---

## Easiest: double-click a batch file

| File | When to use |
|------|-------------|
| **`Start Player.bat`** | Daily listening — opens browser in ~1 second |

Pin **`Start Player.bat`** to your taskbar or desktop for one-click launch.

The server window must stay open while you listen (close it or press Ctrl+C when done).

---

## What each mode does

### Daily use (`Start Player.bat`)

Runs:

```powershell
python play_learned_ayahs.py --serve-only
```

- Does **not** re-parse JSON, re-download MP3s, or rebuild text/timings
- Only starts `http://127.0.0.1:8765/player.html` and opens your browser

### After new library export

Save the new `quran_library … .json` in this folder, then run:

```powershell
python play_learned_ayahs.py --skip-download --skip-text --skip-timings
```

- Re-reads the latest `quran_library*.json` and regenerates `player.html`
- Still skips re-downloading audio/text/timings if already on disk

---

## Other options

### Android app

For phone use without Python, use the Android app — see
[`android-app-quran-player/README.md`](../android-app-quran-player/README.md).

### Open HTML without server (optional)

If everything is already downloaded:

```powershell
python play_learned_ayahs.py --serve-only --file-only
```

Opens `player.html` directly. The HTTP server method is more reliable for audio in most browsers.

### First-time setup (once)

```powershell
cd web-app-quran-player
python play_learned_ayahs.py
```

Downloads all ayah MP3s and builds text/timings. After that, use **`Start Player.bat`** only.

---

## Bookmark setup / removal

**Register (or re-register after moving the folder):** double-click
**`Setup Bookmark.bat`**. It points the `quranplayer:` protocol at this folder's
current location automatically.

**Move to a new computer:** install Python, copy this whole folder over (cached
audio/text come with it), double-click `Setup Bookmark.bat`, then bookmark
`quranplayer:open`. The launcher finds Python on its own — no path editing needed.

**Remove it completely** (PowerShell):

```powershell
Remove-Item "HKCU:\Software\Classes\quranplayer" -Recurse -Force
```

`launcher.log` (next to the script) records each launch — handy if a click does nothing.
