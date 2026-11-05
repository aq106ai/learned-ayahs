# Content expansion and release checks

## Translations, reciters and tafsir (Android and web)

Goal: support the complete eligible catalogs of the chosen providers, with searchable,
language-aware pickers. Do not promise every translation or recording worldwide.

- [ ] Inventory provider catalogs and record stable IDs, languages, authors/reciters,
  editions, coverage, source URLs, versions, attribution and redistribution permissions.
  Evaluate Quran.com/QUL and EveryAyah against the existing asset-generation pipeline.
- [ ] Add a shared generated catalog and matching Android/web models. Persist IDs rather
  than display names; migrate the current English translation and five reciter choices.
- [ ] Offer translation selection in the reader, text copy/share, images and videos.
  Load/download selected resources on demand; keep Arabic and English usable offline.
  Distinguish full-ayah translations from word meanings and UI interface languages.
- [ ] Add reciters only with verified complete audio and timing data for the exact recording,
  following CONTRIBUTING.md. Never fabricate segments or borrow another recording's timings.
  If an audio-only catalog is wanted, design that separately with explicit feature capabilities
  before changing this rule: no synchronized highlighting or word seeking without valid timings.
- [ ] Keep isolated-word reciters separate; require complete word files and stable word mapping.
- [ ] Add a tafsir reader with language/author selection, ayah references, source attribution,
  sanitized content and on-demand offline storage. Keep commentary separate from translations.
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
