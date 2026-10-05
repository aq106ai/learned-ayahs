# CLAUDE.md

Guidance for working in this repo (the **Learned Ayahs Player** Android app).

## What this app is

A standalone Android app for revising the ayahs **you** have memorised: browse all 114 surahs
(or by juz), mark the ayahs you know, and the app streams/caches those MP3s from everyayah.com
and plays them back with lock-screen / Bluetooth / Android Auto controls.

**The learned list is per-user and is the only source.** Nothing is marked on a user's behalf —
a fresh install starts with an empty playlist, and browsing plus Surah-loop playback still work
with nothing marked (`QueueBuilder.surahAllAyahs` doesn't read the master — and
`PlaybackService.handleCommand` must accept a full-surah load with an empty master; until 1.9.2 it
didn't, and tapping an ayah with nothing marked played nothing). Until v1.4.0 the app
merged `DefaultSupplement` — the repo owner's 1,216 personal ayahs — into everyone's list; that
class is gone. Don't reintroduce anything that marks ayahs the user did not choose.

**Ayah reciter** is selectable (`data/Reciter.kt`), defaulting to **Maher Al Muaiqly**.
`QuranConstants.RECITER_FOLDER`/`BASE_URL` read `PlayerSettings.reciter` at call time, so every
`AyahTrack.remoteUrl` and the audio cache directory follow the choice — changing reciter must
rebuild the playlist (`PlayerViewModel.setReciter`), or existing tracks keep the old URLs.

**Word reciter** is a separate list (`data/WordReciter.kt`), defaulting to Quran.com isolated
word MP3s (`https://audio.qurancdn.com/wbw/sss_aaa_www.mp3`). Recite & review always plays
those files (stream or `filesDir/audio/wbw/<folder>/`); Word-by-word playback plays them only when
*Word clips* (`PlayerSettings.useWordClips`, **off by default**) is on — otherwise it cuts each word
out of the ayah reciter's MP3 (see Playback modes). Do not mix word files with ayah reciters —
they are different recordings. Only add a WordReciter that has a complete word-file set.

**The ayah reciter list is short on purpose, and there is no estimated word timing.** Highlighting
inside an ayah MP3 is only honest with real per-word segments for that exact recording, so an
ayah reciter may only be added if QUL has segment data for it *and* everyayah's audio is the audio
those timings describe. Five ship: Maher, Abdul Basit, Al-Husary, and — added in 1.9.0 — Sudais
and Shuraym.

**QUL's API does not name its reciters, so an id is confirmed by measurement, not by a label.**
`ayah_segments/{id}` returns segments and nothing else; the check is that the last segment of an
ayah ends where everyayah's MP3 for that reciter ends. Across 1:1, 1:7, 2:255, 112:1 and 112:2
every shipped id leaves 0–50ms of trailing silence and no other id comes close, which is how
Sudais was pinned to QUL 16 and Shuraym to QUL 25. Seeking the ayah audio to a word as the user
moves through the word overlay in REVISE/FULL_SURAH is a Settings toggle (`seekInsideAyahAudio`,
**off by default**); `PlaybackMode.WORD_BY_WORD` ignores it. An ayah with no validated timings
simply doesn't highlight — `WordSync` has no fallback.

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
  old checkout's Gradle wrapper to 8.11.1, and builds the debug APK. There is no native build —
  see **Recite & review** for why whisper.cpp and the NDK dependency were removed in 1.8.0.
- APKs are renamed to `LearnedAyahsPlayer-v<versionName>-<buildType>.apk` (see `app/build.gradle.kts`).
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
  `attachService`/`detachService` tracks whether the service is live (`isServiceReady()`).
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
  `WordReciter` + `WordAudio`, `RecitationEvaluator` + `ArabicTextNormalizer` (spelling) +
  `ArabicWordAligner` (word boundaries),
  `WrongAyahDetector`, `DeviceRecognition` (what the phone's speech service can do with Arabic)
  + `RecitationTuning` (the coach's matching thresholds), `PlaylistStore` (the loaded master list).
- **`player/WordAudioPlayer.kt`** — one-shot ExoPlayer for a single word file. Recite & review
  uses this so it never mutates the main queue, mode, or `lastGlobalId`.
- **`player/RecitationViewModel.kt`** — Recite & review. Streaming eval highlights a matching
  prefix; a stable wrong completed token (or committed result) plays the word clip via
  `WordAudioPlayer`. Wrong-ayah openings skip the current-ayah clip. An ayah is done when every
  word has been correct at least once (`wordsEverCorrect`). See **Recite & review** below.
- **`ui/`** — Compose screens: `home/SurahListScreen` (the browse-and-mark "picker": all 114
  surahs, search, learned badges, and the always-on "learned ayahs" banner — description, export,
  import), `surah/SurahDetailScreen` (a pure selection screen — mark ayahs learned, no playback
  controls of its own), `settings/SettingsScreen`, `settings/AddByDescriptionScreen`,
  `PlayerScreen` (the immersive reader — see Navigation below), `PlaylistScreen`, `WordByWordView`,
  `recitation/RecitationScreen`.
  `ui/AppNav.kt` is the NavHost.
  `MainActivity.kt` hosts the NavHost and owns the notification permission plus the SAF
  import/export pickers (no storage permission — see below).

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
  a segment has to use the next word's onset as the boundary, which is what
  `.setup/make_recitation_corpus.py` does when it splices a deliberate mistake.

### Recite & review

The practice coach: you recite a learned ayah and the app follows along, interrupting with the
reciter's clip only when you actually say a wrong word.

**It is an opt-in beta, hidden until the user turns it on** (`PlayerSettings.reciteBetaEnabled`,
switched on from Settings behind a confirmation dialog). Recognition quality on classical Quranic
Arabic is not good enough to put this in front of someone who did not ask for it, and the dialog
says so plainly. `PlayerScreen` does not render the entry point at all while it is off.

**It requires Android 13 (API 33).** Everything rests on
`RecognizerIntent.EXTRA_AUDIO_SOURCE`: **the app owns the microphone and feeds the recogniser a
pipe**, instead of letting the speech service open the mic itself. That is the fix for the loudest
complaint the feature ever had — the mic switching on and off throughout a recitation. A speech
service ends its session at every pause between phrases and the only way to keep listening is to
start another one, each start reopening the mic with a chime and an indicator blink.
`EXTRA_SEGMENTED_SESSION` was supposed to prevent that and is widely ignored. With the audio-source
pipe, sessions still end constantly — we just hand the next one a fresh pipe, one `AudioRecord`
spans the whole surah, and nothing is audible. Below API 33 that API does not exist, so rather than
ship the version users rejected, `DeviceRecognition.isSupported` reports false and Settings
explains why.

`DeviceRecognition` is the only place that touches the API-33 surface: `checkRecognitionSupport`
to discover which Arabic variants exist on-device, `triggerModelDownload` to fetch the offline pack
(the listener overload is API 34; on 33 it is fire-and-forget), and `createOnDeviceSpeechRecognizer`
to use it. Preference order is offline-then-online: `PlayerSettings.preferOfflineDeviceSpeech`
selects the on-device model when a pack is installed, and a network failure mid-surah latches it on
for one silent retry before anything is reported.

> **An offline Quran-tuned Whisper model was tried and removed in 1.8.0. Do not bring it back
> without new evidence.** It was Tarteel AI's fine-tune of Whisper (tiny, then base) quantised to
> q5_1 and bundled in the APK, decoded through whisper.cpp. The measurements, so nobody repeats
> the exercise:
>
> - **From audio files it was excellent**: base scored **100% word accuracy / 1% WER** across
>   Al-Fatihah, Al-Ikhlas and Ayat al-Kursi; tiny 87% / 13%.
> - **Through a microphone it was not usable.** It could not decode fast enough to name a wrong
>   word while the student was still on it, and that is the whole job. Confirmed by the repo owner
>   on current flagship hardware, not only on the mid-range test phone.
> - It cost ~58MB of APK (125MB → 21MB on removal), an NDK/CMake dependency and a sparse checkout
>   of a third-party repo, and four native settings — `-O3` before `add_subdirectory`, `audio_ctx`
>   sized to the audio, threads below core count, no `initial_prompt` — each of which made the
>   feature look completely dead when wrong.
>
> **Acoustic A/B testing of engines on this rig never produced a trustworthy answer**, and that is
> worth knowing before anyone tries again. `EngineComparisonTest` scored Google's recogniser at
> 84%, then 62%, then 53% on byte-identical audio in one afternoon, with individual ayahs going
> 100% → 0%. Nothing about that engine changed. Three explanations were tested and all were wrong:
> mic contention between engines (restructured engine-major: no change), sampling the offline
> decoder mid-decode (settle window 12s → 45s: no change), and a decode-throttle regression (no
> correlation). Whatever varies — room noise, speaker level, mic AGC after repeated runs — swamps
> the difference between engines. **Judging recognition accuracy needs a fixed rig or a real person
> reciting.** File-direct decoding was the only deterministic measurement available, and it flatters
> a model that cannot keep up live.


**The app scores Uthmani script against a recognizer that writes plain Arabic, and the two
disagree about spelling and about where words end.** Stripping the harakat is not enough, and this
was the single largest source of "I recited it perfectly and it told me I was wrong". Measured
across the whole Qur'an against a plain-spelling (imlaei) text, **434 of 6236 ayahs (7%) could not
be aligned word-for-word**; after the two fixes below, 17 can't. Both fixes only ever *add*
matches — they are consulted after the existing rules, never instead of them.

- **Spelling** (`ArabicTextNormalizer`). Runs of alif collapse to one, because Uthmani writes آ as
  ء + ا and both fold to ا here (ءَاتَىٰهُمْ arrived a letter longer than the آتاهم every engine
  emits). Then `isWordMatch` falls back to `isLongVowelSpellingVariant`, which forgives up to two
  **added or dropped ا/و/ي** — فَسْـَٔلْ against "فاسأل", ٱلرِّبَوٰا۟ against "الربا" — and never a
  different consonant. It declines to judge words with fewer than two consonants at all, since
  إِلَّا and أُو۟لُوا۟ both reduce to a lone ل. That took the word-level failures from 70 to 8.
- **Word boundaries** (`ArabicWordAligner`). The Uthmani text attaches the vocative يا to what
  follows — يَـٰٓأَيُّهَا, يَـٰقَوْمِ, يَـٰبَنِىٓ — and every recognizer says two words. That is **349 of the
  364 ayahs** whose token counts differ, across 469 ayahs in all, and a one-to-one walk cannot
  survive it: the first word scores wrong and everything after it is off by one. `matchAt` tries
  one-to-one first, then joins up to `MAX_JOINED` heard tokens against one written word, then the
  reverse. Used by the evaluator, `WrongAyahDetector` and `hasMovedOnToNextAyah` — anywhere words
  are counted against tokens. **Do not "fix" this by pre-splitting the reference text**: word
  indices address the correction clip, the highlight and the recap, so the reference list has to
  keep the shape the rest of the app uses.

The residue is 16 ayahs, all spellings that cannot be folded without also folding words
that really are different — رَءَا against "رأى", ٱلَّـٰٓـِٔى against "اللائي", يَا۟يْـَٔسُ against "ييأس".
**None of them interrupts anyone**: every one is within `RecitationTuning.NEAR_MISS_TOLERANCE`, so
`isConfidentMistake` passes it over — the ayah just doesn't complete by itself and the student
presses Next. `UthmaniTranscriptMatchTest` holds both halves of that, the fixes and the residue.
There used to be a seventeenth that was not orthography at all: **2:181's ayah-number glyph was
typed `"char_type": "word"` in `quran_text.json`**, so it was scored as a word to be recited and
the ayah could never finish. It is the end marker now (and its stray "(181)" translation is gone).
The same ayah exposed a timing bug — see `QUL_SPLIT_WORDS` in `.setup/make_timings.py`: QUL times
بَعْدَ مَا as two words where the text has one, which shifted every later highlight in 2:181, 8:6 and
13:37. 2:181 is merged back exactly; 8:6 and 13:37 lost a segment to the old generator and ship
without timings until the generator is re-run against QUL.

**Read every hypothesis the recogniser offers, not just the first.** The session asks for
`EXTRA_MAX_RESULTS` readings and `appendResult` used to keep `firstOrNull()`. That is how
At-Takathur 102:1 came back as `الحاكم` for أَلْهَىٰكُمُ — ه against ح, one substitution on a sound
the student got right — and was scored a mistake with the correct reading sitting unread in the
list. `RecitationSpeechManager.committedAlternatives` now carries the rest as whole transcripts
(same session prefix, different tail) and `RecitationViewModel.bestReading` scores each, keeping
the one that settles the most words. It is a plain `@Volatile` field rather than a flow on
purpose: the evaluator needs the readings belonging to *this* transcript, and two flows updated in
sequence give a collector no guarantee about which pairing it sees.

**That is evidence, not leniency, and the difference is why it is safe.** An alternative is
another plausible transcription of the audio the student actually produced, so preferring one that
fits the ayah means "one reading of what you said is the right word". The tempting shortcut —
letting `isWordMatch` allow two edits instead of one, since `NEAR_MISS_TOLERANCE` is already 2 —
is a different thing entirely: it accepts any word that merely *looks* similar. Measured across
the Qur'an it would make **60 adjacent word-pairs match that must not**, among them العزيز against
العليم, العليم against الحكيم, والشمس against والقمر and وأنفسنا against وأنفسكم. Those are the
confusions a memoriser actually makes, which is precisely what the coach exists to catch. Don't.

**What the screen may call a mistake is the coach's decision, not the evaluation.** Every gate
below decides whether a word is really wrong, and all of it used to govern only the correction
clip and the recap — `RecitationScreen` read `evaluationResult.firstMistake` directly, so a single
transient decode popped a red "Mistake Detected" card, painted the word red and reported "0/2
words correct" for a word the coach had explicitly declined to report. On 102:1 the coach stayed
correctly silent and the student was told they were wrong anyway. The screen now reads
`confirmedMistake` (set at the moment corroboration passes, cleared when the word comes back
correct) and `believedMistakeIndices` for the red highlight; an unmatched word that nobody
believes stays neutral, because unsettled is not the same as wrong.

**When the coach is allowed to interrupt.** The student is stopped only for a mistake that is
certainly theirs; everything else passes silently and the run continues. Three gates, all in
`RecitationViewModel.applyEvaluation`, and none of them is decoration — before they existed the
first run of `FullSurahRecitationTest` produced **four false interruptions per surah**:

- **Only a skip or a properly wrong word** (`isConfidentMistake`). A skip is unambiguous — the
  student is already saying a later word. A substitution counts only when the word heard is beyond
  `isWordMatch`'s one-edit tolerance *and* beyond `RecitationTuning.NEAR_MISS_TOLERANCE`. السِّرَاطَ for
  صِرَاطَ is the recogniser dropping a letter, not a mistake.
- **Corroboration** (`RecitationTuning.MISTAKE_CONFIRMATIONS`). The same wrong word must be named by consecutive
  evaluations. A recognition artifact moves between passes — measured on 1:7, one word came back
  as السِّرَاطَ, then الْسِرَاطَ, then سِرَاطَ, with a spurious إِنْ appearing and vanishing — while a
  real mistake is in every pass of that audio.
- **Two corrections per word, maximum** (`MAX_CORRECTIONS_PER_WORD`). A third playback is nagging,
  and if two have not landed the recogniser is as likely to be at fault as the student. The word
  still counts as a mistake in the report.
**Nothing auto-advances on a timer.** An earlier design banked the ayah and moved on after a few
seconds of silence, so that a surah could always be finished. It had to go: combined with the
`isComplete` bug below it read as "the coach jumps to the next ayah the moment I make a mistake",
which is the opposite of a revision aid. An ayah the recogniser cannot manage now simply waits.

**An ayah is finished when every word has been correct at least once** (`wordsEverCorrect`),
cumulatively, across as many attempts and correction clips as it takes. Not when a single
evaluation comes back all-correct: `RecitationResult.isComplete` describes *one pass*, and after
a correction `trimToLockedPrefix` rewrites the transcript to the locked prefix, so a forward
re-alignment could satisfy it in one step and skip the student straight past the word they had
just got wrong.

The three ways to leave an ayah, and there are deliberately no others:
- every word correct at least once — the run advances itself;
- **reciting the next ayah** (`hasMovedOnToNextAyah`) — reads the *end* of the transcript, not its
  opening. By the time someone moves on, the transcript still *begins* with the ayah they are
  leaving, so `WrongAyahDetector.matchingOtherAyah` (a prefix check, and only consulted at
  `lockedCorrectCount == 0`) can never see it;
- the Next button — which passes `startListening = isListening`, because dropping the microphone
  on the one deliberate way past a stuck ayah would make the student re-arm it every time.

Only corroborated mistakes reach the recap. Banking every transient `MISTAKE` evaluation put words
in the report that the student had recited perfectly.

**A skipped word needs `jumpedOverWord` to be corroborated at all, and without it skips were
never reported.** The first evaluation does flag the skip — and that same evaluation locks the
words before it as correct. On the next one the evaluator can align the tail cleanly from *after*
the gap (it prefers a walk with no mistake, which is what makes a mic gap survivable), so the skip
disappears and `MISTAKE_CONFIRMATIONS` is never reached. The word stayed PENDING for ever, the
ayah could not finish, the student pressed Next and the recap never mentioned it. `jumpedOverWord`
reads a PENDING word that has a CORRECT word *after* it as the skip it is, on committed results
only. Words pending at the *end* of an ayah are simply not recited yet and are never a mistake.

**`RecitationEvaluator` picks between alignments by how many words each one settles, not by how
far into the ayah it reaches.** `AlignWalk.extendExclusive` is an absolute index, so a walk that
starts late and matches nothing "reaches" further than one that starts at 0 and gets a dozen words
right — 12:87 opens يَـٰبَنِىَّ, the recognizer's "يا" is an incomplete prefix of يَا۟يْـَٔسُ thirteen
words later, and that empty walk won, scoring a near-perfect recitation 0/20.

**A correction clip that cannot play must fail fast, not hang.** `WordAudioPlayer.playAndAwait`
used to wait for `isPlaying` to go true then false; when the clip was missing or the network was
down, playback never started, so it sat out its full 8s timeout **with the microphone paused** —
indistinguishable from the coach ignoring the mistake, and the single loudest cause of "it never
gives me a chance to correct". It now waits on a `completions` counter that `onPlayerError` also
bumps. Two supporting fixes: `RecitationViewModel.prefetchWordClips` warms the current and next
ayah's clips as an ayah loads (the bulk fill in `ensureRecitePack` walks the whole learned list in
global-id order, so the ayah in front of the student was routinely still cold), and the player
resolves one expected filename instead of listing the entire word cache on the main thread.

**The recap is per *run*, not per ayah.** `RecitationSessionReport` accumulates a
`RecitationAyahOutcome` per ayah as the student recites and is surfaced once — when the last
learned ayah of the surah is finished (`isAtEndOfSurahRun`), or when they stop. A card after every
ayah forced an acknowledgement that broke the rhythm of revision; auto-advance now pauses only
`END_OF_AYAH_PAUSE_MS` between ayahs. Mistaken words stay tappable from any ayah in the run via
`playWordOf(surah, ayah, index)` — `playWord` alone would play from whichever ayah is current now.

**How Recite is tested, and why it is tested twice.** `RecitationViewModel.acceptTranscript` feeds
a transcript in through the same door and the same guards the recognizer's results use, so
`ReciteReviewEndToEndTest` (JVM, Robolectric) can drive a whole run — intro filter, evaluator,
corroboration, locked prefix, auto-advance, recap — deterministically and without a phone. It runs
every entry in `Reciter.entries`, which is how "recitation quality became reciter-dependent"
would be caught. It reaches no network: the word-clip cache is pre-filled with placeholder files,
because `WordAudioDownloader` returns any file that already exists and is non-empty, and
`autoPlayMistakeAudio` is off so nothing touches ExoPlayer. `FullSurahRecitationTest` keeps the
acoustic half on a device — one microphone session per surah, no false interruptions — now for
Sudais and Shuraym as well as the default reciter, plus one clip that is **deliberately wrong**
(1:2 with رَبِّ replaced by 1:4's مَـٰلِكِ) paired against the correct reading of the same ayah. Pair
it that way: a single acoustic run says very little on its own, but "correct is clean and wrong
names word 2" survives a bad day for the microphone. Regenerate the audio with
`python .setup/make_recitation_corpus.py` (needs `soundfile`; existing clips are left alone unless
`--force`).

**Do not strip intros in more than one place.** `RecitationIntroFilter` removes a'udhu/basmala
from the transcript, but Al-Fatihah 1:1 *is* the basmala — stripping it blindly erased the ayah
so it could never be scored. `stripLeadingIntros` now takes the ayah's own opening and refuses to
strip a phrase the ayah begins with, and `RecitationEvaluator.evaluateContinuing` is the single
call site (the ViewModel passes the raw transcript). A second strip re-breaks 1:1.

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

Two independent axes:

- `PlaybackMode.REVISE` — play through your learned ayahs (whole-ayah MP3s).
  `FULL_SURAH` — stream every ayah of the current surah, learned or not.
  `WORD_BY_WORD` — same learned-ayahs queue as REVISE, one word at a time. By default the
  service plays the ayah reciter's MP3 and cuts each word at the *next* word's onset
  (`loadAyahWordSeek` / `maybeParkAtWordEnd` — QUL's segment ends are too early to cut on); with
  *Word clips* on, ExoPlayer instead holds that ayah's **word files** (`loadWordPlaylist`). An
  ayah without validated timings has nothing to cut on and plays whole. The reader is
  `WordByWordView` only (no whole-ayah toggle). Next/Previous step words; running off either end
  of the ayah loads the next/previous ayah's words. Surah intros are skipped. Recite & review
  plays word files via `WordAudioPlayer` regardless.
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
