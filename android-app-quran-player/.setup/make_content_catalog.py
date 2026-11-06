#!/usr/bin/env python3
"""Generates the content catalog both apps read: translations, word meanings, tafsirs, reciters.

    python .setup/make_content_catalog.py                 # fetches Quran.com's resource lists
    python .setup/make_content_catalog.py --offline DIR   # or reads translations/tafsirs/languages
                                                          # JSON saved from those endpoints in DIR

Writes app/src/main/assets/content_catalog.json. The web app serves the same file from /data/.

Every edition has a **stable id** that settings store (never a display name):
  translations  "en-saheeh" (bundled) or "qc-<Quran.com resource id>"
  word meanings the language code: "en" (bundled) or a language Quran.com has word meanings in
  tafsirs       "qc-tafsir-<Quran.com resource id>"
  reciters      the existing Reciter enum names, unchanged, so saved choices carry over

A Quran.com resource id never changes meaning on their side (a revised edition gets a new id),
which is why it makes a stable key. Ids that vanish from Quran.com are kept in RETIRED with the
edition that replaces them, so an old saved choice falls back somewhere sensible.

**Licences.** Quran.com's API publishes no licence for any edition, and QUL marks almost all of
its copies "no copyright information". So nothing new is bundled: editions other than the two
that already ship are fetched from Quran.com by the user's own device when chosen, and each is
credited by name and source. The bundled Saheeh International is Tanzil's text, which Tanzil
allows verbatim, with the source cited, for non-commercial use. Record what is known in
`licence`; don't upgrade it to something more permissive than the provider says.

Word meanings: Quran.com's `verses/...?words=true&language=<code>` returns meanings in only a few
languages and English for the rest (checked 2026-11-06), so WORD_MEANING_LANGUAGES lists the ones
that came back in their own language. `--verify-words CODE` walks all 114 surahs for one language
and checks that every content word of quran_text.json gets a meaning in that language.
"""
import argparse
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
API = "https://api.quran.com/api/v4"
USER_AGENT = "LearnedAyahs-catalog/1.0"

# The bundled edition. Quran.com's own Saheeh (20) is the same work with footnote markers; it is
# left out so the picker doesn't show two Saheeh Internationals.
BUNDLED_TRANSLATION = {
    "id": "en-saheeh",
    "language": "en",
    "name": "Saheeh International",
    "author": "Saheeh International (Umm Muhammad)",
    "source": "Tanzil.net",
    "sourceUrl": "https://tanzil.net/trans/en.sahih",
    "licence": "Tanzil: verbatim, with the source cited, for non-commercial use",
    "bundled": True,
    "recommended": True,
}
SKIP_TRANSLATIONS = {20, 57}  # 20: duplicate of the bundled Saheeh; 57: transliteration, not a translation

# Shown first in onboarding for each language; the rest of the catalog is a search away. A
# language with none of these gets its first edition marked, so every language has one.
RECOMMENDED_TRANSLATIONS = {
    85, 84, 203,            # en: Abdel Haleem, Taqi Usmani, Hilali & Khan
    54, 234, 97, 819,       # ur: Junagarhi, Jalandhari, Maududi, Wahiduddin Khan
    33, 77, 31, 161,        # id Ministry, tr Diyanet, fr Hamidullah, bn Taisirul
    45, 79, 83, 27, 122,    # ru Kuliev, ru Abu Adel, es Isa Garcia, de Bubenheim, hi al-Umari
    39, 56, 135, 50, 37,    # ms Basmeih, zh Ma Jian, fa IslamHouse, ta Jan Trust, ml
    103, 153, 35, 126,      # pt Helmi Nasr, it Piccardo, ja Mita, bs Korkut
}
RECOMMENDED_TAFSIRS = {169, 168, 160, 159, 16, 91, 14}

# Measured with --verify-words on 2026-11-06, over all 77,429 content words:
#   ur 280 without a meaning (0.4%, those keep the English), id, bn, fa complete.
#   tr 6,890 / hi 6,907 / ta 15,971 words came back in English: left out, since a reader would
#   see a mix of two languages under the ayah. Re-check before adding them.
WORD_MEANING_LANGUAGES = ["en", "ur", "id", "bn", "fa"]

# Saved ids whose edition Quran.com has withdrawn -> what to use instead.
RETIRED = {}

# Must match data/Reciter.kt and web-app/static/js/core/reciters.js (tests check both).
RECITERS = [
    ("MAHER_AL_MUAIQLY", "Maher Al Muaiqly", "MaherAlMuaiqly128kbps", "word_timings_maher.json"),
    ("ABDUL_BASIT", "Abdul Basit (Murattal)", "Abdul_Basit_Murattal_192kbps", "word_timings_abdulbasit_murattal.json"),
    ("AL_HUSARY", "Mahmoud Khalil Al-Husary", "Husary_128kbps", "word_timings_husary.json"),
    ("AS_SUDAIS", "Abdurrahman As-Sudais", "Abdurrahmaan_As-Sudais_192kbps", "word_timings_sudais.json"),
    ("ASH_SHURAYM", "Saud Ash-Shuraym", "Saood_ash-Shuraym_128kbps", "word_timings_shuraym.json"),
    ("ABDUL_BASIT_MUJAWWAD", "Abdul Basit (Mujawwad)", "Abdul_Basit_Mujawwad_128kbps", "word_timings_abdulbasit_mujawwad.json"),
    ("AL_HUSARY_MUALLIM", "Mahmoud Khalil Al-Husary (Muallim)", "Husary_Muallim_128kbps", "word_timings_husary_muallim.json"),
    ("AL_HUSARY_MUJAWWAD", "Mahmoud Khalil Al-Husary (Mujawwad)", "Husary_128kbps_Mujawwad", "word_timings_husary_mujawwad.json"),
    ("AL_GHAMDI", "Saad Al-Ghamdi", "Ghamadi_40kbps", "word_timings_ghamadi.json"),
    ("AD_DUSSARY", "Yasser Ad-Dussary", "Yasser_Ad-Dussary_128kbps", "word_timings_dussary.json"),
    ("AL_TUNAIJI", "Khalifa Al-Tunaiji", "khalefa_al_tunaiji_64kbps", "word_timings_tunaiji.json"),
]

# How the Arabic is drawn. Each font is bundled (res/font, served by the web app), SIL OFL, and
# covers every codepoint of quran_text.json. Checked 2026-11-06 and left out: Quran.com's own
# KFGQPC page fonts (V1/V2/V4 tajweed) and IndoPak Nastaleeq — no licence available to us (QUL:
# "no copyright information"; Quran.com's frontend repo has no licence); Digital Khatt Madina /
# Old Madina / IndoPak (OFL, but they misdraw Quran.com's text: صِرَٰطَ breaks at the dagger alif,
# IndoPak waqf marks are missing glyphs). Tajweed colours need Quran.com's tajweed text, which
# differs from ours in 165 of 761 words checked (other marks, not just colour).
MUSHAF_STYLES = [
    {"id": "uthmani-scheherazade", "name": "Uthmani · Scheherazade New", "family": "Scheherazade New",
     "font": "scheherazade_new.ttf", "author": "SIL International", "source": "software.sil.org/scheherazade",
     "licence": "SIL Open Font License 1.1", "script": "uthmani"},
    {"id": "uthmani-amiri", "name": "Uthmani · Amiri Quran", "family": "Amiri Quran",
     "font": "amiri_quran.ttf", "author": "Khaled Hosny (The Amiri Project, 1.003)", "source": "github.com/aliftype/amiri",
     "licence": "SIL Open Font License 1.1", "script": "uthmani"},
]

# Languages Quran.com lists without an ISO code we can use.
EXTRA_ISO = {"arabic": "ar", "divehi, dhivehi, maldivian": "dv", "divehi": "dv"}
RTL_FALLBACK = {"ar", "ur", "fa", "he", "ps", "sd", "ug", "dv", "ku", "ks"}


def fetch(path):
    req = urllib.request.Request(f"{API}{path}", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def read(offline, name, path):
    if offline:
        return json.loads((Path(offline) / name).read_text(encoding="utf-8"))
    return fetch(path)


def clean(s):
    return " ".join(str(s or "").split())


def build(offline=None):
    languages = read(offline, "languages", "/resources/languages")["languages"]
    by_name = {}
    langs = {}
    for lang in languages:
        code = lang["iso_code"]
        by_name[lang["name"].lower()] = code
        langs[code] = {
            "name": clean(lang["name"]),
            "nativeName": clean(lang.get("native_name") or lang["name"]),
            "rtl": lang.get("direction") == "rtl" or code in RTL_FALLBACK,
        }
    by_name.update(EXTRA_ISO)

    def code_for(language_name):
        return by_name.get(language_name.lower())

    translations = [BUNDLED_TRANSLATION]
    skipped = []
    for t in read(offline, "translations", "/resources/translations")["translations"]:
        if t["id"] in SKIP_TRANSLATIONS:
            continue
        code = code_for(t["language_name"])
        if not code:
            skipped.append(t["language_name"])
            continue
        translations.append({
            "id": f"qc-{t['id']}",
            "language": code,
            "name": clean(t["name"]),
            "author": clean(t.get("author_name")) or clean(t["name"]),
            "source": "Quran.com",
            "sourceUrl": "https://quran.com",
            "resourceId": t["id"],
            "licence": "Not stated by the provider; fetched from Quran.com when chosen",
            "bundled": False,
            "recommended": t["id"] in RECOMMENDED_TRANSLATIONS,
        })

    tafsirs = []
    for t in read(offline, "tafsirs", "/resources/tafsirs")["tafsirs"]:
        code = code_for(t["language_name"])
        if not code:
            skipped.append(t["language_name"])
            continue
        tafsirs.append({
            "id": f"qc-tafsir-{t['id']}",
            "language": code,
            "name": clean(t["name"]),
            "author": clean(t.get("author_name")) or clean(t["name"]),
            "source": "Quran.com",
            "sourceUrl": "https://quran.com",
            "resourceId": t["id"],
            "licence": "Not stated by the provider; fetched from Quran.com when opened",
            "recommended": t["id"] in RECOMMENDED_TAFSIRS,
        })

    word_meanings = [{
        "id": code,
        "language": code,
        "source": "Quran.com",
        "sourceUrl": "https://quran.com",
        "bundled": code == "en",
    } for code in WORD_MEANING_LANGUAGES]

    used = {x["language"] for x in translations + tafsirs + word_meanings} | {"ar"}
    langs.setdefault("ar", {"name": "Arabic", "nativeName": "العربية", "rtl": True})
    for code in sorted(used - langs.keys()):
        langs[code] = {"name": code, "nativeName": code, "rtl": code in RTL_FALLBACK}

    have = {x["language"] for x in translations if x["recommended"]}
    for x in translations:
        if x["language"] not in have:
            x["recommended"] = True
            have.add(x["language"])

    key = lambda x: (x["language"] != "en", x["language"], not x.get("bundled"), not x["recommended"], x["name"].lower())
    translations.sort(key=key)
    tafsirs.sort(key=lambda x: (x["language"], not x["recommended"], x["name"].lower()))
    if skipped:
        print(f"skipped editions in unknown languages: {sorted(set(skipped))}", file=sys.stderr)

    return {
        "format": "learned-ayahs-content-catalog",
        "version": 1,
        "api": API,
        "defaults": {"translation": "en-saheeh", "wordMeanings": "en", "tafsir": "", "reciter": "MAHER_AL_MUAIQLY", "mushafStyle": "uthmani-scheherazade"},
        "languages": {code: langs[code] for code in sorted(used)},
        "translations": translations,
        "wordMeanings": word_meanings,
        "tafsirs": tafsirs,
        "mushafStyles": MUSHAF_STYLES,
        "reciters": [{"id": i, "name": n, "folder": f, "timingsAsset": a, "highlighting": True} for i, n, f, a in RECITERS],
        "retired": RETIRED,
    }


def verify_words(code):
    """Every content word of the bundled text gets a meaning in `code`, aligned by position."""
    text = json.loads((ASSETS / "quran_text.json").read_text(encoding="utf-8"))
    counts = [0] * 115
    for key in text:
        counts[int(key.split(":")[0])] += 1
    missing, foreign, total = 0, 0, 0
    for surah in range(1, 115):
        page = 1
        while page:
            d = fetch(f"/verses/by_chapter/{surah}?words=true&language={code}&per_page=50&page={page}&word_fields=position")
            for v in d["verses"]:
                ours = [w["position"] for w in text[v["verse_key"]]["words"] if w["char_type"] == "word"]
                theirs = {w["position"]: w for w in v["words"] if w["char_type_name"] == "word"}
                for pos in ours:
                    total += 1
                    w = theirs.get(pos)
                    if not w or not (w.get("translation") or {}).get("text"):
                        missing += 1
                    elif w["translation"].get("language_name") not in (None, LANGUAGE_NAMES.get(code)):
                        foreign += 1
            page = d["pagination"]["next_page"]
    print(f"{code}: {total} content words, {missing} without a meaning, {foreign} in another language")
    return missing == 0


LANGUAGE_NAMES = {"en": "english", "ur": "urdu", "id": "indonesian", "bn": "bengali", "tr": "turkish",
                  "hi": "hindi", "ta": "tamil", "fa": "persian"}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--offline", metavar="DIR")
    p.add_argument("--verify-words", metavar="CODE")
    a = p.parse_args()
    if a.verify_words:
        sys.exit(0 if verify_words(a.verify_words) else 1)
    catalog = build(a.offline)
    out = ASSETS / "content_catalog.json"
    out.write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out.relative_to(ROOT)}: {len(catalog['translations'])} translations, "
          f"{len(catalog['tafsirs'])} tafsirs, {len(catalog['wordMeanings'])} word-meaning languages, "
          f"{len(catalog['languages'])} languages")


if __name__ == "__main__":
    main()
