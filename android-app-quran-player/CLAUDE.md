# CLAUDE.md

Guidance for working in this repo (the **Learned Ayahs Player** Android app).

## What this app is

A standalone Android app for revising the ayahs **you** have memorised: browse all 114 surahs
(or by juz), mark the ayahs you know, and the app streams/caches those MP3s from everyayah.com
and plays them back with lock-screen / Bluetooth / Android Auto controls.

**The learned list is per-user and is the only source.** Nothing is marked on a user's behalf —
a fresh install starts with an empty playlist (and in Full Surah Mode: `onPlaylistLoaded` switches
an empty list's REVISE to FULL_SURAH and saves it; the web engine does it for the session only),
and browsing plus Surah-loop playback still work
with nothing marked (`QueueBuilder.surahAllAyahs` doesn't read the master — and
`PlaybackService.handleCommand` must accept a full-surah load with an empty master; until 1.9.2 it
didn't, and tapping an ayah with nothing marked played nothing; `finishIntroAndResume` had the
same guard until versionCode 41, so it played A'udhu and then stopped). Until v1.4.0 the app
merged `DefaultSupplement` — the repo owner's 1,216 personal ayahs — into everyone's list; that
class is gone. Don't reintroduce anything that marks ayahs the user did not choose.

**Ayah reciter** is selectable (`data/Reciter.kt`), defaulting to **Maher Al Muaiqly**.
`QuranConstants.RECITER_FOLDER`/`BASE_URL` read `PlayerSettings.reciter` at call time, so every
`AyahTrack.remoteUrl` and the audio cache directory follow the choice — changing reciter must
rebuild the playlist (`PlayerViewModel.setReciter`), or existing tracks keep the old URLs.

**Word reciter** is a separate list (`data/WordReciter.kt`), defaulting to Quran.com isolated
word MP3s (`https://audio.qurancdn.com/wbw/sss_aaa_www.mp3`), streamed or cached under
`filesDir/audio/wbw/<folder>/`. Word-by-word playback plays them only when
*Word clips* (`PlayerSettings.useWordClips`, **off by default**) is on — otherwise it cuts each word
out of the ayah reciter's MP3 (see Playback modes). Do not mix word files with ayah reciters —
they are different recordings. Only add a WordReciter that has a complete word-file set.

**The ayah reciter list is short on purpose, and word timings are QUL's, checked against the
recording** (the one exception, an ayah's last word, is measured and scored — see the timing
audit under `assets/`). Highlighting inside an ayah MP3 is only honest with real per-word segments
for that exact recording, so an
ayah reciter may only be added if QUL has segment data for it *and* everyayah's audio is the audio
those timings describe. Maher, Abdul Basit, Al-Husary, Sudais and Shuraym shipped first; 1.13 adds
Abdul Basit (Mujawwad), Husary (Muallim), Husary (Mujawwad), Al-Ghamdi, Ad-Dussary, Al-Tunaiji,
Al-Minshawi (Murattal), Ash-Shatri and Alafasy,
pinned by `.setup/pin_qul_ids.py` (everyayah's MP3 byte-identical to the audio QUL timed — QUL now
returns `audio_url` — plus one segment per word) and audited like the first five. The pin's end
check is ±250 ms: ffprobe's MP3 duration is that imprecise (shipped Maher reads -177..+7 ms), so
the old "0-50 ms of trailing silence" cannot be re-measured that way. Mujawwad recordings' last-word
estimate is the weakest (p50 560 ms, p90 1.4 s, against Maher's 160/460). Pinned but failed or
not yet audited: see `docs/TODO.md`; Tablawi, Hani Rifai and Minshawi (Mujawwad) don't fit.

**QUL's API does not name its reciters, so an id is confirmed by measurement, not by a label.**
`ayah_segments/{id}` returns segments and nothing else; the check is that the last segment of an
ayah ends where everyayah's MP3 for that reciter ends. Across 1:1, 1:7, 2:255, 112:1 and 112:2
every shipped id leaves 0–50ms of trailing silence and no other id comes close, which is how
Sudais was pinned to QUL 16 and Shuraym to QUL 25. Seeking the ayah audio to a word as the user
moves through the word overlay in REVISE/FULL_SURAH is a Settings toggle (`seekInsideAyahAudio`,
**off by default**); `PlaybackMode.WORD_BY_WORD` ignores it. An ayah with no validated timings
simply doesn't highlight — `WordSync` has no fallback.

**A recitation file can hold more than its ayah.** EveryAyah's Maher Al Muaiqly `002258.mp3` is
93 s: 2:258 (33.6 s) and all of 2:259. `WordSync.ayahEndMs` (web: `core/wordSync.js`) treats a
file that runs more than 5 s and a quarter past its last word timing as ending 400 ms after that
word, and every player of ayah files follows it: the service's ticker (`maybeCutAtAyahEnd`, the
slider's duration, the last word of a word cut from the ayah), the web engine (`cutAtAyahEnd`,
`wordBoundaryHit`) and both video makers (`AudioPipeline.trimToAyah`, `exporter.js trimToAyah`).
Tested with overlong fixtures: web e2e 114:2 (9 s), `AdaptivePlaybackTest` (30 s files).

Single-module Gradle project. Kotlin + Jetpack Compose UI, Media3 (ExoPlayer + MediaSession)
for playback. `minSdk 26`, `targetSdk`/`compileSdk 36`, Java/Kotlin 17, AGP 8.10 on Gradle 8.11,
Kotlin 2.0 (the Compose compiler is the `org.jetbrains.kotlin.plugin.compose` Gradle plugin).
**Targeting 35+ means edge to edge on Android 15+**: `LearnedAyahsTheme` keeps every screen inside
`WindowInsets.safeDrawing`, so a new screen needs no inset handling of its own. Robolectric is pinned
to SDK 34 (`src/test/resources/robolectric.properties`) — it can only emulate levels it ships.

## Build & run

```powershell
# Windows (needs JDK 17+ and Android SDK; point local.properties at the SDK)
echo "sdk.dir=C\:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk" > local.properties
.\gradlew.bat assembleDebug        # -> app\build\outputs\apk\debug\LearnedAyahsPlayer-v<ver>-debug.apk
```

- `bash .setup/build.sh` is a one-shot Linux/CI setup: installs the SDK (platform 36), brings an
  old checkout's Gradle wrapper to 8.11.1, and builds the debug APK. There is no native build:
  whisper.cpp and the NDK dependency went in 1.8.0 with the Recite & review coach that used them.
- APKs are renamed to `LearnedAyahsPlayer-v<versionName>-<buildType>.apk` (see `app/build.gradle.kts`).
- **`preview` build type: a test APK that installs next to the Play Store app.** A release build
  (R8 and all) with `applicationIdSuffix = ".preview"` (`com.quran.learnedayahs.preview`), the
  label "Learned Ayahs (test)" (`src/preview/res`) and its own key, `app/preview.keystore`.
  **That key is committed on purpose, password included**: it can only sign the `.preview`
  package, so it can never update the Play app, and a fixed key lets each preview update the last
  (a CI debug key changes per runner, which would force an uninstall each time). It has its own
  data, so learned ayahs move across by export/import. CI builds it on every push and uploads the
  `preview-apk` artifact. Debug is untouched, so `tools/device-test.ps1`'s package names still hold.
- Bump `versionCode` **and** `versionName` in `app/build.gradle.kts` for each release; the
  README tells users to confirm the version in Settings → About after installing.
- Tests:
  - `.\gradlew.bat testDebugUnitTest` — JVM unit tests (`app/src/test`), no device needed.
    Robolectric supplies SharedPreferences/`org.json`/assets off-device. Covers the pure
    domain logic (`AyahMapping`, `QueueBuilder`, `WordSync`) plus `LearnedAyahsStore` and the
    bundled-asset loading.
  - `.\gradlew.bat connectedDebugAndroidTest` — Espresso/Compose end-to-end tests
    (`app/src/androidTest`), **requires a connected device/emulator**. They cover browsing,
    marking learned ayahs, settings, and the word-by-word reader. They never start playback,
    so they need no network. `BaseAppTest` resets every global (the stores and `PlayerSettings`
    are process-wide singletons) *before* the activity launches, via a `RuleChain` rather than
    `@Before`, and deletes `last_crash.txt` — `MainActivity` swaps the whole UI for a plain-View
    crash report when that file exists, which makes every later test fail with the misleading
    "No compose hierarchies found in the app". Installs preserve app data, so it can arrive from
    a previous session.
  - If Gradle reports "more than one device/emulator", the phone is registered twice (USB/TCP +
    mDNS): `adb disconnect <ip:port>` leaves one.

**Run instrumented tests with `tools/device-test.ps1`, not `connectedDebugAndroidTest`.** Gradle
uninstalls both APKs when it finishes, so every run needs a fresh install — and on MIUI an install
needs *Install via USB* re-enabled by hand, in practice about one install per flip. The script
installs once (`-Install`, which always rebuilds first — compiling is not packaging, and installing
after a bare `compileDebugAndroidTestKotlin` silently ships the previous APK) and then drives
`am instrument`, so re-running a test costs nothing.

**It also raises `screen_off_timeout` for the duration and restores it afterwards, and that is
load-bearing.** If the display sleeps mid-run the foreground activity is destroyed and tests fail
with `Activity never becomes requested state [DESTROYED]`, a bare timeout, or "Process crashed" —
naming innocent tests and looking exactly like a code regression. Developer options' *Stay awake*
only applies while charging, so an unplugged phone still follows the normal timeout. Symptom to
recognise: a lone test failing after many minutes when its siblings passed in seconds.

**Never keep the screen awake by injecting `input keyevent KEYCODE_WAKEUP`.** Those events enter
the same input pipeline the instrumentation uses for its own taps and swipes; on a sibling project
that produced **28 spurious `ComposeTimeoutException` failures across 15 classes** and was mistaken
for a broad UI regression. Changing the timeout setting touches no input at all.

## Architecture

Playback state lives in the **service**, not the ViewModel. The UI is a thin reflection of a
single shared state flow.

- **`service/PlaybackService.kt`** — the heart of the app (~800 lines). A Media3
  `MediaSessionService` owning the `ExoPlayer`. All transport goes through its mode-aware
  methods (`play`/`pause`/`skipNext`/`skipPrevious`/`seekTo`). External controllers (Bluetooth,
  lock screen, Auto) are routed through a `ForwardingPlayer` (`SessionPlayer`) so they hit the
  same code path as on-screen buttons — never raw ExoPlayer calls, which would bypass the
  revision gap and surah-intro logic.
- **`service/PlayerStateHolder.kt`** — a singleton `StateFlow<PlayerUiState>` that is the single
  source of truth bridging service ↔ UI. The service pushes updates; the ViewModel/UI observe.
  `attachService`/`detachService` tracks whether the service is live; `isServiceReady()` is
  true only once it has also *loaded a queue* (`PlaybackService.hasLoadedQueue`). It registers in
  onCreate but loads when its first command arrives, and the launch-time load restores
  `START_FROM_LAST_POSITION`, read when it loads: a Next pressed while the service starts moves
  the preview (persisted at once), and the load then picks that up instead of undoing it.
  `PlayerDefaultScreenTest.the_last_played_ayah_is_remembered_across_a_restart` taps Next twice
  straight after launch and caught this, intermittently, as "Ayah 2".
- **`player/PlayerViewModel.kt`** — `AndroidViewModel` the UI calls. When the service is ready it
  forwards to it via `PlayerStateHolder`; when not, it maintains a **preview** queue locally so the
  UI works before playback starts. It also drives library loading, offline downloads, and loading
  ayah text/timings.
- **`player/PlayerSettings.kt`** — `PlaybackMode` (Learned ayahs / Full surah / Word by word)
  and `RepeatMode` (Off / Surah / Ayah) enums, orthogonal and independently persisted, plus the
  rest of the persisted settings (revision delay, ayah reciter, word reciter, seek-inside-ayah,
  gestures, …). Also defines `PlayerUiState`.
- **`data/`** — domain + IO: `LibraryRepository` (builds the snapshot, reads/writes picked files),
  `LearnedAyahsStore` (the user's marks), `BookmarksStore` (pinned ayahs for quick access — same
  shape as `LearnedAyahsStore`, independent of it), `AyahRef` + `LearnedAyahsExport` (reference
  grammar and sync format), `AudioDownloader` (ayah MP3 cache under `filesDir/audio/<reciter folder>/`),
  `WordAudioDownloader` (word MP3 cache under `filesDir/audio/wbw/<folder>/`),
  `QueueBuilder`/`AyahMapping`/`QuranConstants` (queue construction + global-ayah-id math),
  `QuranDataRepository` (ayah word text, translations, word-timing segments), `Reciter`,
  `WordReciter` + `WordAudio`, `PlaylistStore` (the loaded master list).
- **`ui/`** — Compose screens: `home/SurahListScreen` (the browse-and-mark "picker": all 114
  surahs, search, learned badges, and the always-on "learned ayahs" banner — description, export,
  import), `surah/SurahDetailScreen` (a pure selection screen — mark ayahs learned, no playback
  controls of its own), `settings/SettingsScreen`, `settings/AddByDescriptionScreen`,
  `PlayerScreen` (the immersive reader — see Navigation below), `PlaylistScreen`, `WordByWordView`.
  `ui/AppNav.kt` is the NavHost.
  `MainActivity.kt` hosts the NavHost and owns the notification permission plus the SAF
  import/export pickers (no storage permission — see below).

### Sources and disclaimer

`ui/common/SourcesAndDisclaimer.kt` lists where every piece of Qur'an content comes from and
holds the disclaimer. It is always in Settings (Sources and disclaimer) and a tap away from the
intro's last page (`SourcesDialog`). The web app shows the same text
(`web-app/static/js/views/terms.js`): change both together.

**Nothing has to be agreed to before using the app.** Until 1.12.0 a `TermsDialog` blocked the
app behind "I agree" (re-asked whenever a `TERMS_VERSION` rose). It was removed on purpose: the
content's licences (Tanzil, Quran.com, QUL, SIL OFL; the photos are CC0) require the sources to be
*credited*, which an always-reachable Sources screen does, not accepted; and a click-through gate
in front of a free Qur'an app put a legal-looking wall before the first ayah for no protection
the disclaimer in Settings, the store listing and the repository licence don't already give.
Don't bring it back without a concrete reason (a licence that requires assent, say).

**The intro lets the user choose how the app looks** (`IntroDialog` page 2: theme and player
background, applied as they are picked). Both are ordinary settings, changeable later in Settings
and the player's background sheet. **Page 3 ("Read and listen") picks the content**
(`ui/settings/ContentChoices.kt`, also a Settings section): translation language and edition,
style, word-meaning language, tafsir and reciter (through `PlayerViewModel.setReciter`).

### Translations, word meanings and tafsir (`data/ContentCatalog.kt`, `data/ContentRepository.kt`)

- **The catalog** is `assets/content_catalog.json`, generated by `.setup/make_content_catalog.py`
  and shared with the web (`web-app/static/js/core/catalog.js` holds the same rules; change both).
  **Settings store catalog ids, never names**: `PlayerSettings.translationEdition` (`en-saheeh` or
  `qc-<Quran.com resource id>`), `wordMeaningLanguage` (`en`, `ur`, …), `tafsirEdition` (empty =
  none). An unknown or withdrawn id falls back (`retired`, then the default), so old saved values
  never crash. Reciters kept their enum names, which were already ids.
- **Style** is the two existing switches read as one choice (`TranslationStyle`): words,
  full ayah, or both. There is no third setting to keep in step.
- **Nothing new is bundled.** Quran.com publishes no licence per edition and Tanzil (the bundled
  Saheeh) is non-commercial, so other editions are fetched from `api.quran.com/api/v4` by the
  user's device when chosen, credited by name (`Edition.credit`) in videos and Sources. Don't add
  an edition to the APK without a licence that allows it.
- **Downloads are whole or absent**: cleaned to plain text (`ContentText.plainTranslation` drops
  Quran.com's `<sup foot_note>` markers and all tags), checked (≤60 empty ayahs; every ayah of a
  surah for meanings), then renamed into `filesDir/content/`. When one can't be had, Saheeh
  International shows, and a word with no meaning in the chosen language keeps its English.
- **Word meanings align by `position`**, never by counting: our text marks 2:181's number glyph
  as the end where Quran.com calls it a word. `AyahWord.position` exists for this.
  `QuranDataRepository`'s cache always holds the bundled English; `withChosenMeanings` swaps the
  chosen language in as words are read, and `PlayerViewModel` fetches a surah's meanings as it
  comes up and swaps them in with `PlayerStateHolder.replaceWordMeanings` — **not**
  `updateAyahText`, which clears the word timings and would switch highlighting off.
- **Only languages Quran.com returns (nearly) complete are offered**: en, ur (0.4% missing), id,
  bn, fa. tr/hi/ta came back 9–21% English (`--verify-words`); a mixed-language line under the
  Arabic is worse than English, so they are left out until there's a complete source.
- **Tafsir** (`ui/player/TafsirSheet.kt`, the player header's book icon) reads
  `ContentRepository.ayahTafsir`: Quran.com's JSON for that ayah is kept on disk and only ever
  becomes `TafsirText` blocks (headings, paragraphs, items, quotes, bold/italic runs). **Never
  render provider HTML** — no `AndroidView`/WebView/`Html.fromHtml` for it. An entry often covers
  a group of ayahs (`verses`, e.g. 112:1–4), which the sheet says. The sheet is a bottom sheet,
  so like the dialogs it is tested on a device (`TafsirSheetTest`), not under Robolectric.
- **Mushaf style** (`PlayerSettings.mushafStyle`, catalog `mushafStyles`, `ui/theme/Type.kt`
  `QuranFonts`): `quranArabicStyle` is `@Composable` so every Arabic line follows the choice;
  images and videos take `QuranFonts.fontRes`. A face is only offered if it is licensed for us
  to bundle **and** draws our text correctly: render real ayahs (1:7's صِرَٰطَ, 2:181, 112) and
  check every codepoint of quran_text.json is in its cmap (fontTools). The rejected candidates and
  why are in `make_content_catalog.py` above MUSHAF_STYLES.
- **App language** (`ui/i18n/UiStrings.kt`, `PlayerSettings.uiLanguage`, `ui/settings/UiLanguagePicker.kt`):
  every interface string is `tx("English text", args…)` — the English is the key, `{0}`… are its
  arguments — looked up in `assets/ui_strings_<code>.json`, generated with the web's tables from
  `.setup/ui_strings.json` by `make_ui_strings.py`. Not strings.xml: `stringResource` only works in
  composition, and much text here is built in handlers, effects and view models; one table also
  keeps Android and web word for word the same. **Rules**: wrap new text in `tx(…)` and add it to
  ui_strings.json (CI's `make_ui_strings.py --check` fails otherwise); never wrap data — routes,
  file paths, MediaStore folders, pref keys, enum values — only what is shown; `object` properties
  holding text must be getters (`val x get() =`), since a language change recreates the Activity
  in the same process. `MainActivity.attachBaseContext` applies the locale, so Compose's
  LayoutDirection is RTL for Urdu/Arabic; the reader's header stays LTR on purpose.
  `SurahNames.name` is Arabic script in those languages; parsing and search use `SurahNames.latin`.
- **JVM tests**: `ContentCatalogTest`, `ContentRepositoryTest` (stubbed `ContentRepository.fetcher`,
  no network) and `ContentChoicesTest` (Compose on Robolectric). **Robolectric never settles
  with a Compose `Dialog` open** ("Compose did not get idle"), so the picker's list dialogs are
  driven by the instrumented `IntroAndAddByDescriptionTest`, and the pick logic is a plain
  function (`chooseTranslationLanguage`) tested directly.

### Share as video (`video/`, `ui/video/`)

"Make a video" turns a range of ayahs into an MP4, entirely on the phone: `VideoMakerScreen` (from
the player's Share button → Video, and the film icon on a surah's page) → `VideoExporter`. The pieces:

- **Audio first, and the timeline comes from it.** `AudioPipeline` decodes each clip (the chosen
  reciter's everyayah MP3, cached in the player's own `filesDir/audio/<folder>/`), converts it to
  44.1 kHz stereo (`PcmResampler`) and encodes one AAC track, laying clips end to end with gaps.
  `VideoTimeline` is measured from the decoded audio, so highlighting matches what is heard.
- **Frames are drawn on a Canvas, then go to the encoder through EGL**, not through
  `Surface.lockCanvas`: only `eglPresentationTimeANDROID` lets each frame carry its time in the
  video, and a Canvas post is stamped with the wall clock, so a video rendered faster or slower
  than real time would play at the wrong speed. `VideoEncoder` interleaves the audio samples as
  it muxes. The render loop is not a suspend function on purpose: EGL is bound to one thread.
- **Text** is `AyahLayout` (pure, unit-tested): right-to-left columns with the word meaning under
  each word, shrunk to fit one screen before it ever pages; a page turns when its first word is
  recited. Highlighting uses `WordSync` and the reciter's validated timings, and an ayah without
  them simply isn't highlighted — the same rule as the player.
- **Backgrounds** are drawn, never photographs: `VideoThemes` (53 themes of two or three
  `SceneSpec`s) painted by `BackgroundScenes` (19 scene kinds, a still layer cached as a bitmap
  plus a light moving layer). **Content norms: no people, no animals, no music** — keep it that
  way when adding a scene. Each ayah's theme comes from `assets/video_themes.json`, which
  `.setup/make_video_themes.py` builds by scoring the English meaning against a word list per
  theme, smoothed across neighbouring ayahs; `BackgroundPlan` holds a background at least 12 s.
  `VideoThemesTest` keeps the script's keys and `VideoThemes` equal.
- **Nature photos** (`NaturePhotos`) are the other kind of background: CC0 photos from Wikimedia
  Commons, gathered by `backgrounds/fetch_nature_photos.py` (run by `nature-photos.yml`, since
  this environment can't reach Commons) and **reviewed by eye before they ship** — the script's
  word filter misses people, birds, buildings and watermarks that a look catches; the rejects are
  listed in its docstring. They live in the repository's `backgrounds/nature/`, not the APK, and
  download on first use from `raw.githubusercontent.com/.../main/`, falling back to `dev/` so a
  build reviewed from dev works before the photos reach main. A subject is addressed like a theme, `photo:<subject>`, and "Matched" with
  photos maps each ayah's drawn theme to a subject that lists it. A photo scene is drawn with a
  slow zoom (`BackgroundScenes`).
- **The translation** in videos is Saheeh International (`assets/translation_en_saheeh.json`, from
  Tanzil.net via the `quran-json` npm package, written by the same script). It is named in the
  sources and disclaimer.
- **Credits** (reciter, translation, word meanings, photographer) are off the picture by default:
  the video shows only the surah:ayah reference, and since 1.12.1 a shared video or image goes
  out with **no caption** (the sources stay in Settings → Sources and disclaimer). The "Credits on
  video" toggle (`VideoRequest.credits`) prints them under the card, with "Made with Learned
  Ayahs" at the foot. The words themselves are a separate share (`ui/AyahShareText.kt`: Arabic,
  English or both, then the reference).
- **One word at a time** (`VideoRequest.wordFocus`; web `request.wordFocus`) draws only the word
  being recited, large, with its meaning, "n / count" and the whole translation
  (`FrameRenderer.drawFocusWord`, web `renderer.js drawFocusWord`). Ayahs follow `WordSync`
  timings only, and one the reciter has no validated timings for falls back to the whole-ayah
  layout rather than guessing. **The intros are the exception, and only in this layout.** The
  Bismillah clip becomes the reciter's own Al-Fatihah 1:1 (`bismillahFromReciter`), whose
  validated timings say which word is being said and whose voice matches the ayahs. A'udhu has no
  timings anywhere, so `IntroWordTimings` (web `video/introTimings.js`; change both) estimates
  them from the clip's sound: where the voice starts and ends, then a pause between every pair of
  words if there is one, else the voiced span split by letters with each joint moved to the
  quietest frame within 100 ms. The same estimate covers a Bismillah when the reciter lacks 1:1
  timings. Every other layout leaves the intros unhighlighted, as before. `likeThePlayer` turns it on when
  the player is in the word reader (`PlayerSettings.wordView`).
- **A'udhu and Bismillah carry no label** (`FrameRenderer.reference` is empty for them, and an
  empty reference isn't drawn): the words say what they are. Ayahs keep "Surah · s:a".
- **Bismillah and the end card** are their own options (`VideoRequest.bismillah`, `endCard`).
  `VideoRequest.bismillahAllowed` refuses At-Tawbah and Al-Fatihah 1 (that ayah *is* the
  Bismillah). The end card adds `VideoTimeline.END_CARD_MS` of silence: what the app is (a free,
  open-source tool to revise memorised ayahs), two QR codes side by side (Android on the left,
  `VideoRequest.PLAY_STORE_URL`; the web app on the right, `WEB_APP_URL`) and a one-line note on
  sources. The web app's `video/renderer.js` draws the same card; `VideoExportTest` decodes each half.
- **Saving needs no storage permission**: MediaStore (Movies/Learned Ayahs) on Android 10+, the
  SAF "save as" picker below that; sharing goes through the `FileProvider` (`res/xml/shared_files.xml`,
  cache `videos/` and `images/` only).

**The player shows the same backgrounds** (`ui/PlayerBackground.kt`), opt-in from the background
button in its playback panel (`ui/player/BackgroundSheet.kt`); `PlayerSettings.playerBackground` is
`plain` (the default: the player looks as it always did), `matched`, `photos`, or one theme key.
`LiveBackground` (pure, unit-tested) applies `BackgroundPlan`'s hold and crossfade on a running
clock. The scene is drawn by `BackgroundScenes` at half resolution and scaled up (a full-screen
still is ~10 MB), moves only while playing, and only asks for a photo once it's downloaded. While a
background is chosen, the app's colours come from the scene on screen (`ui/theme/Color.kt`:
`paletteFor`, `applyShownScene`, eased by `animatedPalette`) on every screen, in the dark or the
light version of that scene as the theme says (`paletteFor(scene, light)`); plain brings the green
Dark or Light palette back. Dark/Light is picked in the same sheet ("Theme"), not
in Settings, and the sheet opens from the three-dot menu in the player header, which also holds the
mode switch (left out when the surah on screen is learned in full: both lists are the same), Word
by Word / View Full Ayah, the translation and word-meanings switches, Tafsir, Share and Settings. The web app does the same
(`views/sceneColours.js` puts the palette on the page root and remembers the last scene); only the
picture itself stays in the player. The whole-ayah translation (`showAyahTranslation`, off by
default, turned on by choosing a background) sits under the Arabic in `WholeAyahReader`.
**Share** in the player header offers Video (the maker set up like the player, via
`VideoOptions.likeThePlayer` and the route's `fromPlayer`) or an image (`ui/player/ShareImage.kt`:
`VideoExporter.still` at the current position, cache `images/`, shared on its own), or the ayah as text.

`VideoExportTest` makes a real MP4 from the test suite's own Al-Ikhlas recordings (no network) and
checks its tracks, length and frame count. `VideoSamples` (not a test; `video-samples.yml` runs it)
draws every background and a real video for a person to review, into `store/raw/video-maker/`, and
`PlayerBackgroundShots` adds screenshots of the player with each kind of background.
`PlayerBackgroundTest` covers the sheet, the translation and both Share paths.

### Navigation

`AppNav.kt` picks its start destination once, from `PlayerSettings.seenIntro`: **the player is the
default screen** once onboarding is done; the picker (`Routes.HOME`) is reached deliberately —
either from the player's empty state ("Browse surahs") or the surah panel's "Manage learned ayahs"
button — never as a default. A fresh install still opens on the picker, with the intro dialog,
so a first-time user has somewhere to mark ayahs before the player has a queue to show. This is
computed once at first composition (`remember`), not reactively — dismissing the intro dialog
mid-session does not yank the user off the picker; it only changes what the *next* cold start
opens on. Because of this, `PlayerScreen`'s Close button is conditional: it only renders when
`AppNav` can supply a real `onBack` (i.e. the player was pushed onto an existing stack), since as
the root screen there's nothing to pop back to.

The player also remembers where you left off: `PlayerSettings.lastGlobalId` is written every time
the current track changes (`PlayerViewModel.init`'s track-change collector) and read back by
`preparePlaybackService()` as the `startGlobal` passed to the service — `PlaybackService.loadForMode`
derives surah + queue + index entirely from that id when no surah is given, so this one write/read
pair is what restores position across a restart, in whatever mode/repeat was last active, even if
that ayah isn't in the learned list.
- **`assets/`** — `quran_text.json` (**all 6236 ayahs**, Uthmani word text),
  `word_translations.json` (English word-by-word, aligned to *content* words — the end-of-ayah
  glyph has none), and **one `word_timings_<reciter>.json` per reciter** (`Reciter.timingsAsset`),
  each covering the full Qur'an. All loaded lazily and cached in `QuranDataRepository`.

  **Never hand-edit the timing assets, and never copy QUL's segments verbatim.** They are
  generated by `.setup/make_timings.py` (`python .setup/make_timings.py sudais shuraym` rebuilds
  just those two), which cleans QUL's data and validates it. QUL emits a
  spurious ~70ms leading blip on ~80 ayahs (e.g. 2:21 gives 12 segments for 11 words, the first
  two overlapping); the old asset copied it, which shifted every highlight one word early and
  left the real final segment unused — the "last word never highlights" bug. The generator drops
  blips that overlap what follows, then requires: one segment per content word, strictly ordered,
  non-overlapping. Ayahs that still don't align are **dropped**, not approximated.
  `TimingDataIntegrityTest` re-checks all of this against the shipped assets.

  **The fetch must follow QUL's pagination, and the generator's `from` window is not its page
  size.** A window of 11 ayahs comes back as ten on page 1 and one on page 2; stepping `from` by
  11 without reading page 2 silently drops every 11th ayah. Sudais first generated at 5691/6236
  with 2:11, 2:22, 2:33 … missing in a perfect arithmetic progression, and the only thing that
  noticed was `coverage_is_effectively_complete`. That is what the coverage floor is for.

  **A segment's `end` is not where the word ends.** QUL reports onsets accurately and offsets
  often not at all — Maher's لِلَّهِ and رَبِّ in 1:2 both come back as exactly 100ms. Highlighting is
  unaffected (`WordSync` asks which word is active at time *t*), but anything that *cuts* audio on
  a segment has to use the next word's onset as the boundary. (`.setup/make_recitation_corpus.py`
  did this when splicing a deliberate mistake for the recitation tests; it went with them.)

  **QUL's timings can be well-formed and still wrong for everyayah's recording**, which the
  shape checks above can't see. `.setup/audit_timings.py <reciter>` measures every recording's
  loudness and judges QUL's cleaned timings (`make_timings.generate`, not the asset, so re-running
  it gives the same verdicts) into `.setup/timing_fixes/<reciter>.json`. `timing-audit.yml` runs
  it for all five reciters (this environment can't reach everyayah or QUL), regenerates each
  asset with `make_timings.py`, which applies the verdicts, and commits both; CI fails if an asset
  doesn't carry its verdicts (`apply_timing_fixes.py --check`). What it found, every reciter:

  - **The last word timed in the trailing silence** — the "highlight never reaches the last word"
    bug, ~900 ayahs per reciter. QUL's last segment is a 20-110 ms sliver ~50 ms before the end of
    the file, with the real last word inside the segment before it, so Word by word also played
    the last two words as one. Its start is *estimated*: the span between the word before's onset
    and the measured end of the voice, split by the two words' letters (or, where it scores
    better, the reciter's fitted last-word duration snapped to a loudness dip). **This is the one
    place a word boundary is not QUL's.** Every candidate is scored on the ayahs whose last word
    QUL did time, with that onset hidden, against simply leaving the sliver; the run fails rather
    than write repairs unless the best beats it clearly. Measured: Maher within 160 ms for half,
    460 ms for nine in ten (Husary, the slowest, 320/760 ms) where the sliver is out by ~1.5 s
    (Husary ~4 s). Splitting at loudness dips alone scored *worse* than the letters — stops and
    nasals inside a word dip as deeply as the gap between words — so don't "improve" it that way
    without the scores.
  - **The first word over the opening silence** — QUL starts it at 0, or as a 70 ms blip there,
    or a little after the voice; it is moved to where the voice starts (more than 1.5 s late,
    e.g. Maher's 4:175 or 3:4, means the opening is misaligned, and the ayah is dropped).
  - **A constant offset** — shifted, only when both ends agree.
  - **Timings that don't fit** — a recording of another length (2:220), a word starting deep
    inside a long silence — dropped: 19 (Husary) to 221 (Shuraym) ayahs, which is why the
    coverage floor is 5900.

  Loudness is weak evidence, and the audit's own rules were wrong three times before the counts
  made sense; look at `inspect_timings.py` output for a few dropped ayahs before believing a new
  rule. In particular **QUL's segment ends are not evidence** (see above), a click before the
  recitation is not the voice starting, and a word QUL starts a few hundred ms before the voice
  returns from a pause is slack, not a misfit.

### Recite & review — removed, see `archive/recite-review-word-by-word`

The practice coach (listen to the student recite, correct the word they got wrong) was withdrawn
from this app. Everything it was — `RecitationViewModel`, `RecitationSpeechManager`,
`RecitationEvaluator`, `ArabicWordAligner`, `ArabicTextNormalizer`, `RecitationIntroFilter`,
`WrongAyahDetector`, `DeviceRecognition`, `ui/recitation/`, their tests and the instrumented
`FullSurahRecitationTest` — is preserved on the `archive/recite-review-word-by-word` branch,
along with the design notes that used to live in this section. Read that branch before rebuilding
any of it: the coach logic was sound and well covered, and the notes record a long list of things
that look like improvements and are not.

**Why it went.** The coach could only ever be as good as what the platform speech service
returned, and on short isolated words it did not return them. Reciting Al-Ikhlas 112:1 one word
at a time, Google's Arabic model answers `والله` for قُلْ هُوَ ٱللَّهُ and never transcribes قُلْ at all —
the student says the word correctly, over and over, and the card cannot move. No amount of work
downstream of recognition fixes that, and nothing in the app could measure it either: the only
instrument was a person with a phone reporting what they saw.

**What this removed from the app.** `RECORD_AUDIO` and the `<queries>` block for
`android.speech.RecognitionService` are gone from the manifest, so the app no longer asks for the
microphone at all. The settings that belonged to the coach went with it
(`reciteBetaEnabled`, `reciteWordByWord`, `autoPlayMistakeAudio`, `autoAdvanceSuccess`,
`preferOfflineDeviceSpeech`). `WordAudioPlayer` went too — it existed only to play a correction
clip without disturbing the main queue, and nothing else used it. **The word clips themselves
stayed**: `WordAudioDownloader` and `WordReciter` serve word-by-word *playback* in the player and
`PlaybackService`, which is a different feature and still works. The Settings download button
moved to a "Word audio" section and is labelled for playback now.

**Where it is being rebuilt.** The web app (`web-app/static/js/core/recite/`), where recognition
can be watched in a browser and iterated on in seconds rather than through an APK install.

### Word-by-word reader

`ui/WordByWordView.kt` — one word at a time with its meaning. Several non-obvious rules hold it
together; all are covered by `WordByWordTest`:

- **Ayah boundaries are real pager pages** either side of the words. An overscroll gesture was
  tried first and never fires — the pager's own overscroll effect swallows the leftover drag.
- **`pendingEdge` is declared above the empty-words early return.** Changing ayah blanks
  `ayahWords` while the next loads, tearing the composable down to its loading branch and
  discarding anything remembered after that point — which lost which way the boundary was
  crossed and landed the reader on the wrong word.
- **Playback-follow only runs while playing**, plus a grace window after a swipe. `activeIndex`
  still describes the previous word until the player reports its new position, so without this a
  backward swipe gets yanked forward again.
- **Transport is intercepted in the service**, not the UI: in the REVISE/FULL_SURAH word overlay,
  `WordByWordView` registers `PlayerStateHolder.setWordStepHandler` while composed, and
  `PlaybackService.skipNext/skipPrevious` consult `stepWord` first — so notification, lock screen,
  Bluetooth and Auto step words exactly like the on-screen buttons. The reader's own boundary
  crossing must call the ayah change inside `PlayerStateHolder.skippingWordStep { … }`, or its own
  hook swallows the request and it can never leave the edge page.
  **`PlaybackMode.WORD_BY_WORD` does not register that hook** — the service already steps word
  files, and a pager hook would double-step Next from the notification. Edge pages call
  `skipAyah`. Seek-on-settle inside an ayah MP3 is gated by `seekInsideAyahAudio` (off by default)
  and is never used in word-file mode.
- **That hook is registered above the empty-words early return**, and while the pager doesn't
  exist it claims the step only if the text is still loading. `PlayerViewModel.loadAyahText`
  blanks `ayahWords` on every ayah change, so the reader spends a window on its loading branch
  each time it crosses a boundary. A hook registered below the return lapses there, and a Next
  arriving mid-load falls through to a real ayah skip — which blanks the words again, so
  held/repeated headset presses race through ayahs instead of stepping words. But once the words
  have *settled* empty ("Ayah text unavailable") the hook must decline instead, or every
  controller's Next/Previous is swallowed and the user is trapped on that ayah.
- Reading direction is fixed right-to-left (Quranic Arabic) — there is no LTR mode.

### Playback modes (`PlaybackMode` × `RepeatMode`)

Two independent axes, plus word playback:

- **Word playback is not a mode any more** (the `WORD_BY_WORD` notes below describe how the
  service plays words; the enum value only survives for migrating old settings).
  `PlayerSettings.wordPlayback = useWordClips || repeatWord`, in either mode and **with either
  view on screen**: `wordView` only chooses between `WordByWordView` and the whole ayah, and the
  whole-ayah view lights `currentWordIndex` while words play one at a time. The player footer's
  Repeat row is one choice of four, Ayah, Surah, Word, Off: Word is `repeatWord`
  (`PlayerViewModel.setRepeatWord` sets `repeatMode` to OFF, `setRepeatMode` clears `repeatWord`).
  *Word clips* sits above the reciter picker and hides it when on (the clips are Quran.com's own
  recording). The web engine is the same: `engine.wordPlayback`, `settings.repeatWord`,
  `settings.useWordClips`, `settings.wordView`.
- `PlaybackMode.REVISE` — play through your learned ayahs (whole-ayah MP3s).
  `FULL_SURAH` — stream every ayah of the current surah, learned or not.
  `WORD_BY_WORD` — same learned-ayahs queue as REVISE, one word at a time. By default the
  service plays the ayah reciter's MP3 and cuts each word at the *next* word's onset
  (`loadAyahWordSeek` / `maybeParkAtWordEnd` — QUL's segment ends are too early to cut on); with
  *Word clips* on, ExoPlayer instead holds that ayah's **word files** (`loadWordPlaylist`). An
  ayah without validated timings has nothing to cut on and plays whole. **While cutting words the
  progress ticker wakes at the current word's cut point** (`nextTickDelay`), not every 250 ms,
  and carrying on to the next word takes the word from the audio position, not `index + 1`: with
  a fixed tick a word ran up to a quarter second into the next, and through a run of short words
  the counter fell behind the audio, so the highlight lagged and a remote's Next stepped from a
  stale word ("words stuck in the middle"). The reader is
  `WordByWordView` only (no whole-ayah toggle). Next/Previous step words; running off either end
  of the ayah loads the next/previous ayah's words. Surah intros are skipped.
- `RepeatMode.OFF` / `SURAH` / `AYAH`. Under WORD_BY_WORD, `AYAH` loops that ayah's words
  (with `revisionDelaySeconds` after the last word); `SURAH` wraps the learned-ayahs-of-surah
  queue. ExoPlayer itself stays `REPEAT_MODE_OFF` in word mode — wrapping is done in the service.

`QueueBuilder.buildQueue` for WORD_BY_WORD matches REVISE. **In REVISE/FULL_SURAH, Next always
steps one ayah.** In WORD_BY_WORD it steps one word. Both mode and repeat are persisted and
passed across the service Intent boundary as `.name` strings (`EXTRA_MODE`/`EXTRA_REPEAT`), not
ordinals.

## Domain model & conventions

- **Global ayah id**: ayahs are identified by a 1-based index across the whole Qur'an (1..6236),
  derived from `QuranConstants.VERSE_COUNTS`. `AyahMapping` converts global id ↔ (surah, ayah) and
  builds everyayah.com filenames (`%03d%03d.mp3`) and URLs. This id is the `mediaId` on each
  `MediaItem` and how queues are matched/deduped.
- **Master list** = the full learned playlist in `PlaylistStore.latest`. Active queues are derived
  from it per mode; keep queues ordered and duplicate-free (some logic identifies a queue by its
  first + last global id).
- **Surah intro**: before ayah 1 of a surah, the service inserts A'udhu Billah + Bismillah clips
  (except surah 9, `NO_BISMILLAH_SURAH`). This is a small state machine (`IntroStep`) that
  temporarily swaps the player to a single-item playlist — much of the service's subtlety is
  keeping next/previous, the UI index, and the notification correct *while an intro is playing*.
- **Revision gap**: under `RepeatMode.AYAH` with a delay set, the service pauses ~just before the
  ayah loops (`maybeParkBeforeRevisionRepeat` / `parkForRevisionGap`) rather than after, to avoid a
  sliver of the first word replaying. `revisionGapParked`/`revisionGapPending` guard the resume so
  a manual Play/pause/seek during the gap does the right thing.

When editing `PlaybackService`, respect the extensive `//` comments — they document non-obvious
ExoPlayer timing races that were fixed deliberately. Don't "simplify" them away.

### Notification and foreground service

**The service owns its one notification and its foreground state; Media3's are switched off**
(`onUpdateNotification` is overridden to do nothing). Until 1.9.1 both ran: Media3's
`DefaultMediaNotificationProvider` posts under id 1001 — the same id as ours — and on every player
event it re-started the service with `startForegroundService`, which ran our `onStartCommand`,
which posted ours twice more. Word-by-word mode makes several player events per word, so a session
posted thousands of notifications and hit the system's rate limit. The rules that keep it fixed:

- **`updateNotification` posts only when what the notification shows has changed**
  (`NotificationContent`). Don't put anything in it that changes per word or per tick.
- **The service stays in the foreground for as long as playback is going on, including its own
  pauses** — the revision gap, A'udhu → Bismillah → ayah, word to word (`playbackOngoing`). Android
  12+ refuses to *start* a foreground service from the background
  (`ForegroundServiceStartNotAllowedException`), and Media3 used to drop the foreground on every
  pause, so the next intro with the screen off called `startForeground` from the background and
  took the app down. It leaves the foreground only after a real pause or the end of the queue
  (`scheduleForegroundCheck`), keeping a dismissible notification.
- **`promoteToForeground` never throws.** A resume from the background after a real pause can still
  be refused; playback carries on with the notification updated.
- **The app starts the service with `startService`, never `startForegroundService`**
  (`PlayerViewModel.startServiceCompat`). The latter obliges `startForeground` within seconds even
  for a command that plays nothing. The service enters the foreground itself when playback starts.
- **`START_NOT_STICKY`.** A sticky restart after the process died arrived with a null intent and
  promoted from the background — a crash loop. There is nothing to resume, so it doesn't restart.

ExoPlayer handles audio focus and "becoming noisy" (headphones unplugged) itself; a pause for
either reason cancels a pending revision-gap resume (`onPlayWhenReadyChanged`), or the gap would
restart playback over another app.

## The learned list

Ayahs the user marks, persisted as global ids in `LearnedAyahsStore` (SharedPreferences
`learned_selection`) and turned into the master by `LibraryRepository.buildLocalSnapshot` →
`PlaylistStore.latest` → `PlayerStateHolder.updatePlaylist`. That is the single funnel; the
service and every mode/repeat combination read it and nothing else.

`LearnedAyahsStore` validates ids to 1..6236 at its single `setIds` choke point, and
`buildLocalSnapshot` range-checks again. Both matter: `AyahMapping.globalToSurahAyah` **throws**
above 6236 (one bad id would crash the home screen's badges) and silently returns a nonexistent
"ayah 0" for 0. `buildLocalSnapshot` also sorts by global id and re-indexes from 1 — queues are
identified by their first/last id — which `DefaultSupplement.merge` used to do.

**Ways ayahs get marked** — all of them end at `LearnedAyahsStore.addAll`:
- tapping the circle in `SurahDetailScreen`;
- **Add by description** (`ui/settings/AddByDescriptionScreen`) — free-text refs, or JSON pasted
  from an LLM (the screen copies a prompt; the app itself never calls any API);
- **Import** an export file (`LearnedAyahsExport`), the sync/backup path.

### `AyahRef` — the shared reference grammar

`data/AyahRef.kt` parses `2:255` · `36:1-83` · `112` · `78-114` · `Al-Baqarah 255`, and
`format()` compresses ids back into those refs. It backs the quick-add box, the export file and
the LLM prompt, so changing it changes all three — `AyahRefTest` is thorough on purpose.
`parse` reports `unparsed` tokens; surface them rather than silently dropping input.

### Export format

`LearnedAyahsExport`: `{ "format": "learned-ayahs", "version": 1, "exportedAt", "count",
"ayahs": ["2:255", "36:1-83", "112"] }`. Refs, not raw ids, so the file stays human- and
LLM-writable. Parsing is deliberately lenient (bare id array, bare ref array, or free text).

### No storage permissions

Import/export go through SAF (`OpenDocument` / `CreateDocument`), which grants access to the one
file the user picks. `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` and the whole
Downloads-scan path (`DownloadsScanner`) were removed in 1.4.0 — all-files access is also a Play
Store policy blocker. Don't add them back. Read failures still map to a friendly message rather
than propagating raw, the bug fixed in 1.1.0.
