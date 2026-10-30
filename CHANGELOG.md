# Changelog

Notable changes to the Learned Ayahs Android app and web app. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the Android app uses
`versionName` from `android-app-quran-player/app/build.gradle.kts`.

## [Unreleased]

Android 1.12.0 (versionCode 45), which includes 1.11.0 (43) and 1.11.1 (44).

### Added
- **Choose the theme and the player's background while setting up** (Android and web app): the
  welcome now has a "Make it yours" page, applied as you pick, and changeable any time after.
- **Web app: the sidebar folds down to its icons** (the button at its foot; remembered in that
  browser), and over a player background the scene fills the whole window, with the sidebar and
  the phone tab bar translucent over it.
- **Android: backgrounds in the player.** The background button (in the playback panel) puts
  the video maker's scenes behind the ayah while it plays: matched to what each ayah is about,
  drawn or as nature photos, or one chosen for every ayah. It's off unless chosen; the scene
  moves only while the recitation plays, and the text stays light on it whatever the theme.
- **Android: the whole ayah's translation in the player** (Saheeh International), under the
  Arabic. Turned on with a background, or on its own in Settings → Reading.
- **Web app: make videos.** The video maker runs in the browser (Make a video, or Share → Video
  in the player): the same shapes, reciters, word meanings, translation, drawn scenes and nature
  photos as the Android app, encoded on the device with WebCodecs to MP4 (or WebM where the
  browser has no H.264). It works on the GitHub Pages site too.
- **Web app: backgrounds in the player, the whole ayah's translation, and Share** (a video or an
  image of the ayah as it looks now), as in the Android app.
- **Word timing audit** (`.setup/audit_timings.py`, `timing-audit.yml`): checks a reciter's word
  timings against everyayah's recordings, ayah by ayah, and writes which ayahs to shift and which
  to drop to `.setup/timing_fixes/`, which `make_timings.py` applies.

### Changed
- **No agreement screen on first launch** (Android and web app). The sources and disclaimer no
  longer stand between a new user and the app behind "I agree": they are linked from the
  welcome's last page and always in Settings. The content's licences ask for the sources to be
  credited, which that does, not agreed to.
- **Android: one Share button in the player**, in place of the film icon. It offers a video,
  which opens the video maker set up like the player (background, translation, word meanings),
  or an image of the ayah as it looks now, with the word being recited lit up, in any of the
  video shapes. The sources go in the message, as for videos.

### Fixed
- **Word highlighting that never reached the last word, or started early or late** (all five
  reciters, Android and web app). Checked against every recording:
  - For about 900 ayahs per reciter the timing data put the last word in the silence after the
    recitation, so the highlight never reached it and Word by word played the last two words as
    one. Its start is now estimated from the recording and the words' lengths, which is within
    0.15-0.3 s for half of ayahs, depending on the reciter.
  - The first word no longer lights up over the silence before the recitation starts.
  - Ayahs whose timings don't fit the recording at all (19 to 221 per reciter) show without
    highlighting rather than with the wrong word lit.
- **Android: words cut mid-word in Play → Word by word, and Next from a remote stepping from the
  wrong word.** With words cut from the ayah recording, a word's end was checked only every
  250 ms and each check moved the word counter on by one, so a word could run into the next and
  the counter fell behind the recitation through a run of short words. The word now stops on
  time, the counter follows the audio, and Next/Previous across an ayah during a repeat pause
  carries on playing.

## Android 1.10.0 (versionCode 42)

### Added
- **Android: share ayahs as a video.** "Make a video" (the film icon in the player and on a
  surah's page) turns one ayah, a range or a whole surah into an MP4 on the phone, to save or
  share:
  - shapes for Shorts and Reels (9:16), feed posts (4:5), square and YouTube (16:9);
  - any of the five reciters, with the word being recited lit up;
  - optional word-by-word meanings and the Saheeh International translation;
  - A'udhu and Bismillah before the first ayah;
  - a background matched to what each ayah is about, from 53 themes of two or three drawn
    backgrounds each, or one theme chosen for the whole video.
  - or nature photos instead: 45 CC0 photos from Wikimedia Commons in fifteen subjects (forest,
    waterfalls, mountain lakes, ocean, dunes, night sky and more), each checked by hand, matched to
    the ayahs the same way and downloaded only when used.
  Backgrounds show only sky, land, water, light and pattern, never people or animals, and the
  video carries the recitation alone, with no music. The picture stays clean, with only the
  surah and ayah; the sources go in the message the video is shared with, or under the text with
  "Credits on video".
- **Sources and disclaimer**, in the Android app and the web app. Before anything else, both
  list where all the Qur'an text, meanings, recitations and timings come from, and ask the user
  to agree to the disclaimer. Users updating from an earlier version are asked once too. The
  same text is always available in Settings.
- **Web app** (`web-app/`): the Android app's features in the browser.
  - Revision, Full surah and Word by word, with every repeat mode and the pause before repeat.
  - The intro rules, the five reciters and validated word highlighting.
  - The word-by-word reader, the surah and playlist panels, and gestures.
  - Media Session controls, Wake Lock and offline audio.
  - Recite & review (beta).
  - It adds accounts with sync across devices, user management for administrators, and
    import/export in the Android app's file format.
  - Runs from one standard-library Python file, or as a static build without accounts.
- Open-source release: MIT license, contributor docs, code of conduct, security policy,
  third-party notices, issue/PR templates and GitHub Actions CI.

### Changed
- `.setup/make_icon.py` finds the font relative to the repository instead of an absolute path.

### Fixed
- Android: tapping an ayah with nothing marked played A'udhu and then stopped instead of
  going on to the ayah.
- Android: the intro screen's last line had a stray "back to you." fragment appended.

### Removed
- The original desktop web player (`web-app-quran-player/`). The web app does everything it
  did, and can still import its `quran_library` exports.

## 1.9.2 — Android

### Fixed
- **A crash with a flood of notifications during playback.** Media3 and the app both ran the
  playback notification under the same id. Media3 also re-started the service on every player
  event, so a session (word-by-word above all) posted thousands of notifications. Media3 also
  dropped the foreground service on every pause, so the next promotion from the background, for
  example after a "pause before repeat" gap with the screen off, crashed the app on Android 12+.
  The app now owns its one notification and its foreground state, posts only when the content
  changes, never crashes entering the foreground, and no longer restarts itself in a crash loop.
- **On phones set to Arabic, Persian or Bengali, no audio could play.** File names were written with
  the phone's own digits ("٠٠٢٢٥٥.mp3"), so every recitation URL failed.
- Word by word: words are cut at the next word's start, so very short words are no longer skipped
  or looped silently under Repeat: Word; an ayah without timings plays once, not once per word.
- Ayah text in the word reader could stay on "Loading ayah text…". Changing reciter now reloads
  the word timings.
- An interrupted download could leave a truncated MP3 that was treated as saved. Downloads now
  complete into place and must be audio.
- Auto Backup stopped once saved audio pushed the app past Android's 25 MB limit. It now backs up
  only your learned ayahs, bookmarks and settings.
- 2:181: Recite & review can finish the ayah (its number was stored as a word), and its word
  highlighting is aligned. 8:6 and 13:37 had misaligned highlighting and are unhighlighted until
  their timings are regenerated.
- The notification and lock screen named Maher Al Muaiqly whatever the chosen reciter.
- With nothing marked yet, tapping an ayah in a surah did not play the surah; the player went
  back to "No ayahs marked yet". Full surah now plays without a learned list.
- With swiping turned off, a swipe across the reader still stepped it like a tap (tap to
  advance). A swipe is no longer a tap.

### Added
- Audio focus (pauses for calls and other apps), pausing when headphones are unplugged, and a
  wake lock for streaming with the screen off.
- The crash screen explains what happened and has Copy and Share buttons for the report.

### Changed
- Targets Android 16 (API 36), built with AGP 8.10, Gradle 8.11 and Kotlin 2.0. Screens stay inside
  the safe area under Android 15+'s edge-to-edge drawing.

### Removed
- Cleartext (HTTP) traffic permission; every source is HTTPS.

## 1.9.1

Earlier milestones, as recorded in the project's design notes:

- **1.9.0**: Added the As-Sudais and Ash-Shuraym reciters, each with validated word timings.
- **1.8.0**: Removed the bundled offline Whisper model (whisper.cpp + NDK). Recite & review now
  uses the platform speech service, and the APK shrank from about 125 MB to 21 MB.
- **1.4.0**: The learned list is now marked in-app only. Removed the personal "default
  supplement", the Downloads scan and all storage permissions. Import/export moved to the Storage
  Access Framework.
- **1.1.0**: File read failures show a friendly message instead of a raw error.
- **1.0.26 and earlier**: See [docs/PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md).
