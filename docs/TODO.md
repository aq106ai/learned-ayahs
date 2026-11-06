# Content expansion and release checks

## Translations, reciters and tafsir (Android and web)

Goal: support the complete eligible catalogs of the chosen providers, with searchable,
language-aware pickers. Do not promise every translation or recording worldwide.

- [x] Inventory provider catalogs (2026-11-06): Quran.com API v4 has 126 translations and 20
  tafsirs, QUL 198 translations, everyayah 79 recordings. Terms: Tanzil is non-commercial only;
  Quran.com publishes no licence per edition; QUL says check each resource (almost all "no
  copyright information"); Quran Foundation's documented API now wants OAuth2 client credentials,
  while the anonymous `api.quran.com/api/v4` the apps use still answers. So nothing new is
  bundled: editions download on the user's device and are credited by name.
- [x] Shared generated catalog (`assets/content_catalog.json`, `.setup/make_content_catalog.py`)
  with `data/ContentCatalog.kt` and `web-app/static/js/core/catalog.js`. Settings store ids
  (`translationEdition`, `wordMeaningLanguage`, `tafsirEdition`); the reciter keys were already
  ids and Saheeh International is the default, so old installs need no migration.
- [x] Translation, style (word meanings / full translation / both), word-meaning language,
  tafsir and reciter chosen in the intro and changeable in Settings, on both apps. The chosen
  edition is used in the reader, text share, images and videos (credited by name); downloads
  are kept for offline use and Saheeh International shows when one can't be had.
- [ ] Word meanings beyond en/ur/id/bn/fa: Quran.com returned 9% (tr, hi) to 21% (ta) of words in
  English (`make_content_catalog.py --verify-words`). Find a complete source or leave them out.
- [ ] Refresh `content_catalog.json` on a schedule (a workflow, like `nature-photos.yml`), and
  add `retired` entries when Quran.com withdraws an edition.
- [ ] UI languages: almost every Android string is hard-coded in Compose (strings.xml has 3) and
  the web's are in the views. Move them to resources/message tables, then add an app-language
  choice to the intro and Settings, starting with English, Urdu and Arabic, with RTL layout tests.
- [ ] Tafsir reader (stage 2): a "Tafsir" action on the current ayah opens a bottom sheet / popup
  with the chosen edition, its name and source, sanitized HTML, cached for offline use.
- [ ] Reciters (stage 3; the user asked to keep accurate highlighting for every reciter, so no
  audio-only catalog). Candidates with an everyayah folder and a Quran.com/QUL recitation:
  Alafasy_128kbps, Minshawy_Murattal_128kbps, Abu_Bakr_Ash-Shaatree_128kbps, Hani_Rifai_192kbps,
  Husary_Muallim_128kbps, Minshawy_Mujawwad_192kbps, Mohammad_al_Tablaway_128kbps,
  Abdul_Basit_Mujawwad_128kbps. Pin each QUL id by measurement and run the timing audit.
- [ ] Add reciters only with verified complete audio and timing data for the exact recording,
  following CONTRIBUTING.md. Never fabricate segments or borrow another recording's timings.
  If an audio-only catalog is wanted, design that separately with explicit feature capabilities
  before changing this rule: no synchronized highlighting or word seeking without valid timings.
- [ ] Keep isolated-word reciters separate; require complete word files and stable word mapping.
- [ ] Tafsir: keep commentary separate from translations; ayah references and on-demand offline
  storage (the edition choice already exists, see above).
- [ ] Handle missing verses, unavailable resources, download retries, cache limits/deletion,
  provider updates and old saved selections consistently across Android and web.
- [ ] Verify all 6,236 ayah mappings; test RTL/LTR layouts, long translations, large fonts,
  text-only sharing, export pagination, offline behavior and recording/timing alignment.

## Before releasing 1.12.1

- [ ] On an Android device, run WordByWordTest, PlayerBackgroundTest and VideoExportTest using
  tools/device-test.ps1. No device was connected during the November 4 review.
- [ ] Manually test slow drags below/above halfway, reversing a drag, grabbing a settling page,
  playback during dragging, first/last playlist boundaries and cancelled touches.
- [ ] Preview a real exported video in portrait, square and landscape. Check Bismillah on/off,
  no duplicate intro for Al-Fatihah 1, the At-Tawbah restriction, and audio/video end alignment.
- [ ] Scan the end-card QR from the compressed shared video and confirm the intended Play Store
  listing opens. Test sharing files without appended text in receiving apps.
- [ ] Verify text sharing/copying with Arabic only, English only and both, including ranges,
  clipboard failures and small screens/large accessibility fonts.
- [ ] Check source attribution requirements for exported translations and word meanings;
  removing an automatic message should not silently remove any required attribution.
- [ ] Confirm Android/web policy for optional seeking in the word overlay: the web exposes
  seekInsideAyahAudio, while Android retains a stored preference but no current settings control.
  Ordinary word-by-word playback and overlay reading are separate behaviors.
