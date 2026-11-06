# Asset generators

One-off scripts that produce committed assets. They are **not** part of the Gradle build — run
them by hand when the underlying data or design changes, then commit the result.

```bash
pip install uharfbuzz fonttools matplotlib
```

## `make_timings.py` — word timings per reciter

Builds `app/src/main/assets/word_timings_<reciter>.json` from QUL for every reciter in
`data/Reciter.kt`.

Two things it exists to get right:

1. **QUL's raw segments cannot be trusted verbatim.** ~80 ayahs carry a spurious ~70ms leading
   blip that overlaps the next segment (2:21 reports 12 segments for 11 words). Copying that
   shifts every word's highlight one early and discards the real final segment — the
   "last word never highlights" bug. The script drops blips that overlap what follows.
2. **Everything is validated before it ships:** one segment per content word, strictly ordered,
   non-overlapping. Ayahs that still don't align are dropped rather than approximated, because
   the app has no estimated fallback.

It caches raw responses in `raw_<reciter>.json`, so re-running to tweak the cleaning costs no
API calls. Delete those to re-fetch. `TimingDataIntegrityTest` re-verifies the output.

**Adding a reciter** requires more than an entry in the enum: QUL must have segment data for it,
and everyayah's audio must be byte-identical to the audio those timings describe —

```bash
curl -sI "https://everyayah.com/data/<everyayah_dir>/112001.mp3"        | grep -i content-length
curl -sI -L "https://audio-cdn.tarteel.ai/quran/<qul_dir>/112001.mp3"   | grep -i content-length
```

If the lengths differ, the timings describe a different master and the highlight will drift.

## `make_content_catalog.py` — translations, word meanings, tafsirs, reciters

Writes `app/src/main/assets/content_catalog.json`, which both apps read (the web serves it from
`/data/`). It lists Quran.com's translations and tafsirs by **stable id** (`qc-<resource id>`,
`qc-tafsir-<resource id>`, plus the bundled `en-saheeh`), with language, author, source and what
is known of the licence, and the five reciters under their existing keys. Settings store these
ids, never names. Nothing new is bundled: the apps fetch a chosen edition from Quran.com.

`--verify-words <code>` walks all 114 surahs and counts content words of `quran_text.json` that
get no meaning, or one in another language. Only languages that come back (nearly) complete are
in `WORD_MEANING_LANGUAGES`; the measured numbers are in the script. `ContentCatalogTest` and
`web-app/tests/unit/catalog.test.js` check the output.

## `make_icon.py` — the آية launcher icon

Regenerates `res/drawable/ic_launcher_foreground.xml` + `ic_launcher_monochrome.xml`. Vector
drawables can't reference a font, so the word is converted to outlines: HarfBuzz shapes it (Arabic
needs contextual forms — fontTools alone yields disconnected letters) and fontTools extracts the
outlines. Writes `icon_preview.png`; **look at it** — wrong glyph forms are the failure mode.
