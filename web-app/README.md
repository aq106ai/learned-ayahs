# Learned Ayahs: web app

The web version of Learned Ayahs: mark the ayahs you have memorised, then revise them by
listening, repeating, following word by word and reciting back. It has the Android app's
features, adds accounts so a family, class or masjid can share one server, and reads and writes
the Android app's backup files.

- **No build step and no dependencies.** The server is one Python file (standard library only).
  The app is plain HTML, CSS and JavaScript modules.
- **Two ways to run it.** Use `server.py` for accounts, sync between devices, user management
  and an audio cache. Or export a static copy for any web host, where each browser keeps its own
  list.
- **Shared data.** It uses the same Uthmani text, word-by-word translation and per-reciter word
  timings as the Android app, read straight from `android-app-quran-player/app/src/main/assets/`.

---

## Quick start

Requirements: **Python 3.9+** and a modern browser (Chrome, Edge, Firefox or Safari).

```bash
cd learned-ayahs/web-app
python3 server.py
```

Open <http://localhost:8080> and **create the first account. It becomes the administrator.**
You can also use the app without signing in. Your list is then kept in that browser.

To use it from your phone or other computers on the same network:

```bash
python3 server.py --host 0.0.0.0        # then open http://<this computer's IP>:8080
```

> Microphone access (Recite & review) and offline mode need a **secure context**: either
> `http://localhost` or HTTPS. For other devices, put the server behind HTTPS (see
> [Deploying](#deploying)).

---

## Features

The table compares the web app with the Android app.

| | Android | Web |
|---|---|---|
| Mark ayahs per surah, whole surahs, bookmarks | ✓ | ✓ |
| Add by description (`2:255, 36:1-83, Al-Mulk, 78-114`) + AI prompt | ✓ | ✓ |
| Export / import the learned list | ✓ | ✓ (**same file format, both ways**) |
| Import a Qur'an-app library export (`quran_library*.json`) | | ✓ |
| Revise learned ayahs · Full surah · Word by word | ✓ | ✓ |
| Repeat off / surah / ayah (word), pause before repeat (0/3/5/10 s) | ✓ | ✓ |
| A'udhu + Bismillah before ayah 1 (not 1 or 9, never word by word) | ✓ | ✓ |
| Five reciters, validated per-word highlighting (never estimated) | ✓ | ✓ |
| Word-by-word reader and overlay, word clips or cut from the ayah | ✓ | ✓ |
| Surah navigator and playlist panels | ✓ | ✓ |
| Swipe / tap to navigate, auto-scroll or fit long ayahs | ✓ | ✓ |
| Lock screen, headset and car controls | ✓ (MediaSession) | ✓ (Media Session API) |
| Keep the screen on while revising | ✓ | ✓ (Wake Lock) |
| Offline: save learned ayahs and word clips | ✓ | ✓ (service worker) |
| Recite & review (beta): mistake correction, recap | ✓ (Android 13+) | ✓ (Chrome, Edge, Safari) |
| Dark / light / system theme, first-run introduction | ✓ | ✓ |
| **Accounts, sync across devices, user management** | | ✓ (with `server.py`) |

### Accounts and user management

- **The first account is the administrator.** Administrators open **Users** to:
  - create accounts;
  - promote or demote administrators;
  - disable or re-enable accounts (a disabled account is signed out everywhere);
  - reset passwords (which also signs that user out everywhere);
  - delete accounts;
  - open or close self-registration.
- **Every user's learned ayahs, bookmarks, settings and last position** are saved to their
  account and follow them between devices. A local copy keeps the app working offline. Changes
  made offline are saved when the server is reachable again.
- **Signing in on a browser you used as a guest** offers to add that browser's ayahs to your
  account. A brand-new account simply starts from them.
- **Users manage their own account:** display name, password (changing it signs out their other
  devices), sign out and account deletion.

### Moving your list between the Android app and the web

Both apps read and write the same small JSON file:

```json
{ "format": "learned-ayahs", "version": 1, "exportedAt": "2026-07-17T06:00:00Z",
  "count": 1842, "ayahs": ["1", "2:255", "36:1-83"] }
```

- **Android to web:** in the Android app open **Settings → Export**. Then in the web app open
  **Surahs → Import** (or **Settings → Import**) and choose the file.
- **Web to Android:** in the web app use **Export**. In the Android app use **Import**.

Importing only **adds** ayahs and never removes any. The web export also includes your
`bookmarks`. The Android app ignores that extra field.

---

## Running options

| Flag | Environment | Default | |
|---|---|---|---|
| `--host` | `LA_HOST` | `127.0.0.1` | interface to bind; `0.0.0.0` for your network |
| `--port` | `LA_PORT` | `8080` | |
| `--db` | `LA_DB` | `data/learned_ayahs.db` | SQLite database (accounts and their data) |
| `--audio-cache` | `LA_AUDIO_CACHE` | `data/audio-cache` | downloaded recitations |
| `--assets` | `LA_ASSETS` | the Android app's `assets/` | Qur'an text, translations, timings |
| `--registration open\|closed` | `LA_REGISTRATION` | `open` | initial sign-up policy; changeable in the app |
| `--no-fetch-audio` | | | serve only audio already in the cache |
| `--secure-cookies` | | | mark the session cookie `Secure` (behind HTTPS) |
| `--quiet` | | | don't log requests |
| `--export-static DIR` | | | write a static copy of the app to `DIR` and exit |

**Audio.** With `server.py`, audio is fetched once from everyayah.com and Quran.com and then
served from the server's cache to every user, with range requests so seeking works. If the server
has no internet access, each browser falls back to the original source. You can also turn on
**Settings → Stream directly**.

### Static hosting (no accounts)

```bash
python3 server.py --export-static ../site    # app + Qur'an data + font, ready to upload
```

Upload the folder to GitHub Pages, Netlify or any web server. The app detects that there is no
server behind it. It then hides accounts, keeps each browser's list locally, and streams audio
straight from its sources. **Export** and **Import** still move lists between devices.

### Deploying

`server.py` uses Python's threaded HTTP server, which suits a family, a class or a masjid.

- **Put it behind a reverse proxy that terminates HTTPS**, such as Caddy or nginx, and run it
  with `--secure-cookies`. For example, with Caddy:

  ```
  quran.example.org {
      reverse_proxy 127.0.0.1:8080
  }
  ```

- **Back up `data/learned_ayahs.db`**: it holds every account. `data/audio-cache/` can be
  re-downloaded at any time.
- **Close registration** once everyone has an account: **Users → Open registration**, or start
  with `--registration closed`.

### Security notes

- **Passwords:** PBKDF2-SHA256, 200,000 iterations, random salt.
- **Sessions:** random tokens, stored only as SHA-256 hashes, in an `HttpOnly`, `SameSite=Lax`
  cookie. Sessions expire after 30 days.
- **Requests that change data** must carry a custom header, which blocks cross-site form posts.
- **Sign-in throttling:** sign-ins are throttled per user and per IP address.
- **Response headers:** a strict Content-Security-Policy, `X-Content-Type-Options`,
  `Referrer-Policy` and frame denial.
- **Audio proxy:** it fetches only known reciter folders and file names. Responses are checked
  to be audio, then written atomically.

---

## API

All endpoints are JSON under `/api/`. Requests that change data need the header
`X-Requested-With: learned-ayahs`.

| Method & path | |
|---|---|
| `GET /api/health` | server version, whether registration is open |
| `GET /api/auth/me` | the signed-in user, or `null` |
| `POST /api/auth/register` · `POST /api/auth/login` · `POST /api/auth/logout` | `{username, password[, displayName]}` |
| `PATCH /api/account` | `{displayName}` |
| `POST /api/account/password` | `{currentPassword, newPassword}` |
| `DELETE /api/account` | `{password}` |
| `GET /api/data` · `PUT /api/data` | `{learned, bookmarks, settings, progress}`; `PUT` takes any subset |
| `GET /api/admin/users` · `POST /api/admin/users` | list / create `{username, password, role}` |
| `PATCH /api/admin/users/{id}` · `DELETE /api/admin/users/{id}` | `{role, disabled, displayName, password}` |
| `GET /api/admin/settings` · `PATCH /api/admin/settings` | `{registrationOpen}` |

Ayahs are global ids, 1 to 6236 across the whole Qur'an.

---

## Development

```
web-app/
├── server.py                 HTTP server, accounts API, audio cache (stdlib only)
├── static/
│   ├── index.html            app shell
│   ├── sw.js                 service worker (offline shell, data and saved audio)
│   ├── css/app.css           design system: dark/light tokens, layout, components
│   └── js/
│       ├── main.js           boot, router, navigation, mini player, theme
│       ├── core/             pure logic ported from the Android app (unit tested)
│       │   ├── quran.js          surahs, ids, queue building, playback/repeat modes
│       │   ├── reciters.js       reciters and audio URLs
│       │   ├── ayahRef.js        "2:255, 36:1-83, Al-Mulk" ⇄ ids
│       │   ├── learnedExport.js  export/import formats (Android-compatible)
│       │   ├── wordSync.js       word highlighting from validated timings
│       │   └── recite/           normaliser, aligner, evaluator, recitation coach
│       ├── player/           playback engine (PlaybackService port), word clips
│       ├── services/         API client, Qur'an data loader, offline, speech
│       ├── state/store.js    the user's data: local-first, synced to the account
│       └── views/            player, surahs, surah, add, settings, recite, account, admin
└── tests/
    ├── unit/                 node:test, for core/ (no browser)
    ├── test_server.py        unittest, for server.py (real server, temp database)
    └── e2e/                  Playwright, the whole app in Chromium against server.py
```

```bash
npm test                 # core logic (node 18+)
npm run test:server      # server
npm install && npx playwright install chromium
npm run test:e2e         # browser tests: guest, player, accounts/admin, recite, static build
```

The browser tests need no network. They start `server.py` with a temporary database and an
audio cache of short generated clips, and Recite & review is driven through a scripted speech
recogniser. Set `PW_CHROMIUM=/path/to/chrome` to use an already-installed Chromium.

The playback rules, timings policy and recitation gates are ports of the Android app's. Their
reasoning is in [`android-app-quran-player/CLAUDE.md`](../android-app-quran-player/CLAUDE.md).
When you change one, keep the two apps in step.
