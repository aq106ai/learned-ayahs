#!/usr/bin/env python3
"""Generates the background theme for every ayah, and the bundled English translation.

    python .setup/make_video_themes.py                  # downloads the translation from jsDelivr
    python .setup/make_video_themes.py quran_en.json    # or reads a local copy

Video export ("Share as video") draws a background behind the recitation. Each theme is a group of
two or three backgrounds drawn by `video/BackgroundScenes.kt`, and this script picks the theme
whose subject matches what the ayah is about. It reads the ayah's English meaning (Saheeh
International, from Tanzil.net by way of the `quran-json` npm package) and scores it against a
word list per theme. Then it smooths the scores with the neighbouring ayahs, so a passage keeps
one background instead of flickering between them, and falls back to the surah's own leading
theme, then to `oneness`, when the meaning names nothing concrete. A few well-known ayahs and
surahs are pinned by hand (OVERRIDES).

Writes, under app/src/main/assets/:
  video_themes.json            {"themes": [key, ...], "ayahs": [theme index for each of 6236 ayahs]}
  translation_en_saheeh.json   the translation itself, one string per ayah in order, verbatim

The theme keys must match `VideoThemes.kt`; VideoThemesTest checks that they do. Only the
derived theme numbers depend on the translation's wording, so changing translation never needs
the backgrounds to change.
"""
import json
import re
import sys
import urllib.request
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
SOURCE_URL = "https://cdn.jsdelivr.net/npm/quran-json@3.1.2/dist/quran_en.json"
TOTAL_AYAHS = 6236

# Theme key -> list of (regex, weight). Concrete scenes weigh more than attributes: "Forgiving and
# Merciful" closes hundreds of ayahs whose subject is something else entirely.
LEXICON = {
    "mercy": [(r"\bmerc(y|iful)\b", 0.6), (r"\bcompassion", 1), (r"\bkindness\b", 1)],
    "forgiveness": [(r"\bforgiv", 0.6), (r"\brepent", 1.5), (r"\bpardon", 1)],
    "paradise": [(r"\bgardens?\b", 3), (r"\bparadise\b", 3), (r"\bcouches\b", 2), (r"\bcompanions of the right\b", 2),
                 (r"\beternal(ly)? (therein|abid)", 1.5), (r"\bgarments of silk\b", 2), (r"\bfountain", 2), (r"\bcups?\b", 2), (r"\bthe righteous\b", 1.5), (r"\bwine\b", 1.5)],
    "rivers": [(r"\brivers?\b", 3), (r"\bflowing\b", 1.5), (r"\bspring(s)?\b", 2)],
    "provision": [(r"\bprovision", 1.5), (r"\bfruits?\b", 2), (r"\bpalm", 2), (r"\bdates\b", 2), (r"\bgrapes?\b", 2),
                  (r"\bpomegranate", 2), (r"\bolive", 2)],
    "hellfire": [(r"\bhellfire\b", 3), (r"\bhell\b", 3), (r"\bthe fire\b", 3), (r"\bblaze\b", 3), (r"\bscalding\b", 2),
                 (r"\bpunishment of (the )?burning", 3), (r"\bzaqqum\b", 3)],
    "punishment": [(r"\bpunishment\b", 1.5), (r"\bdestroyed\b", 2.5), (r"\bseized them\b", 2.5), (r"\bdestruction\b", 2),
                   (r"\bovertook\b", 2), (r"\bblast\b", 2.5)],
    "judgment": [(r"\bday of (judgement|judgment|recompense|resurrection)\b", 3), (r"\bthe hour\b", 3),
                 (r"\bhorn\b", 2.5), (r"\breckoning\b", 2), (r"\bscales?\b", 1.5), (r"\brecord\b", 1)],
    "resurrection": [(r"\bresurrect", 2.5), (r"\braised up\b", 2), (r"\bbring (the )?dead\b", 2.5), (r"\bgive life\b", 2),
                     (r"\bbones\b", 2), (r"\bgraves\b", 2)],
    "death": [(r"\bdeath\b", 2), (r"\bdie[sd]?\b", 1.5), (r"\btaken in death\b", 2.5), (r"\bsoul\b", 0.8)],
    "night": [(r"\bnight\b", 2.5), (r"\bdarkness(es)?\b", 2)],
    "dawn": [(r"\bdawn\b", 3), (r"\bmorning\b", 2.5), (r"\bdaybreak\b", 3), (r"\bbrightness\b", 2)],
    "sun": [(r"\bsun\b", 3), (r"\bday\b(?! of)", 0.6), (r"\bshade\b", 1.5)],
    "moon": [(r"\bmoon\b", 3), (r"\bcrescent", 2.5)],
    "stars": [(r"\bstars?\b", 3), (r"\bconstellations?\b", 3), (r"\bplanets?\b", 2.5), (r"\blamps\b", 1.5)],
    "cosmos": [(r"\bheavens?\b", 1.2), (r"\bsky\b", 1.5), (r"\bseven (heavens|tracts|layers)\b", 3), (r"\bcreated the heavens\b", 2)],
    "mountains": [(r"\bmountains?\b", 3), (r"\bfirmly set\b", 2), (r"\bpegs\b", 2.5)],
    "earth": [(r"\bearth\b", 0.8), (r"\bspread (it|the earth)\b", 2.5), (r"\bbed\b", 1.5), (r"\bground\b", 1.5)],
    "sea": [(r"\bseas?\b", 3), (r"\bships?\b", 3), (r"\bocean", 3), (r"\bwaves?\b", 2.5), (r"\bpearl", 2)],
    "flood": [(r"\bflood\b", 4), (r"\bnoah\b", 3), (r"\bark\b", 3), (r"\bdrowned\b", 3)],
    "rain": [(r"\brain\b", 3), (r"\bwater\b", 1.5), (r"\bsent down from the sky\b", 3), (r"\bdownpour\b", 3)],
    "wind": [(r"\bwinds?\b", 3), (r"\bclouds?\b", 2.5)],
    "storm": [(r"\bthunder", 3), (r"\blightning\b", 3), (r"\bthunderbolt", 3), (r"\bscreaming wind\b", 3)],
    "growth": [(r"\bplants?\b", 2.5), (r"\bvegetation\b", 3), (r"\bgreen\b", 2), (r"\bgrow", 1.5), (r"\btrees?\b", 2),
               (r"\bseeds?\b", 2), (r"\bgrain\b", 2)],
    "harvest": [(r"\bcrops?\b", 3), (r"\bharvest", 3), (r"\btilth\b", 3), (r"\bspike(s)?\b", 2.5), (r"\bsow", 2)],
    "desert": [(r"\bdesert\b", 3), (r"\bsand", 3), (r"\bjourney\b", 1.5), (r"\bcaravan", 3), (r"\btravel(l)?ers?\b", 1.5)],
    "ruins": [(r"\bruins?\b", 3), (r"\bdwellings\b", 2.5), (r"\b(ʿ|')?aad\b", 3), (r"\bthamud\b", 3), (r"\bpeople of lot\b", 2.5),
              (r"\bcities\b", 2), (r"\bgenerations before\b", 2.5), (r"\bwhat was the end\b", 2.5)],
    "prayer": [(r"\bprayer\b", 2.5), (r"\bprostrat", 2.5), (r"\bbow(ing)?\b", 2), (r"\bmasjid", 3), (r"\bmosques?\b", 3),
               (r"\bqiblah\b", 3)],
    "light": [(r"\blight\b", 2.5), (r"\bguidance\b", 1.2), (r"\bguide[sd]?\b", 1), (r"\bstraight path\b", 1.5)],
    "light_verse": [(r"\blamp\b", 3), (r"\bniche\b", 4), (r"\bglass\b", 2)],
    "oneness": [(r"\bno deity except\b", 3), (r"\bone god\b", 2.5), (r"\bpartners?\b", 1.5), (r"\bassociate\b", 1.5),
                (r"\bworship\b", 1), (r"\bthe one\b", 2), (r"\bself-sufficient\b", 2)],
    "majesty": [(r"\bexalted\b", 1.5), (r"\bmighty\b", 0.8), (r"\bpower\b", 1), (r"\bgreat(est)?\b", 0.6), (r"\bsupreme\b", 1.5)],
    "throne": [(r"\bthrone\b", 3), (r"\bdominion\b", 2), (r"\bsovereign", 2), (r"\bkursi\b", 3)],
    "knowledge": [(r"\bknowledge\b", 1.2), (r"\bpen\b", 3), (r"\bwrit(e|ten|ing)\b", 2), (r"\bread\b", 2.5), (r"\btablet\b", 2.5),
                  (r"\blearn", 1.5), (r"\bwisdom\b", 1.5)],
    "revelation": [(r"\bqur'?an\b", 2), (r"\bthe book\b", 1.5), (r"\brevealed\b", 1.5), (r"\brevelation\b", 2),
                   (r"\bverses\b", 1.2), (r"\bscripture", 1.5)],
    "prophets": [(r"\bmessengers?\b", 1.2), (r"\bprophets?\b", 1.5), (r"\babraham\b", 2.5), (r"\bjoseph\b", 2.5),
                 (r"\bjacob\b", 2), (r"\bjesus\b", 2), (r"\bmary\b", 2), (r"\bsolomon\b", 2.5), (r"\bdavid\b", 2.5),
                 (r"\bjonah\b", 2.5), (r"\bjob\b", 2.5), (r"\bzechariah\b", 2.5), (r"\bjohn\b", 2.5), (r"\bshu'?ayb\b", 2.5),
                 (r"\bhud\b", 2.5), (r"\bsalih\b", 2.5)],
    "moses": [(r"\bmoses\b", 3), (r"\bpharaoh\b", 3), (r"\bstaff\b", 2), (r"\bsinai\b", 3), (r"\bchildren of israel\b", 2)],
    "hajj": [(r"\bhajj\b", 4), (r"\bpilgrimage\b", 4), (r"\bka'?bah\b", 4), (r"\bsacred house\b", 4), (r"\bsafa\b", 4),
             (r"\b'?umrah\b", 4), (r"\bsacrificial animals\b", 3)],
    "fasting": [(r"\bfast(ing)?\b", 3), (r"\bramadhan\b", 4), (r"\bramadan\b", 4)],
    "charity": [(r"\bspend", 2), (r"\bcharit", 2.5), (r"\bzakah\b", 3), (r"\bloan\b", 2), (r"\bthe needy\b", 2)],
    "patience": [(r"\bpatien", 2.5), (r"\bsteadfast", 2), (r"\bendur", 2), (r"\btrial\b", 1.5), (r"\bhardship\b", 2)],
    "gratitude": [(r"\bgrateful\b", 2.5), (r"\bthank", 2.5), (r"\bpraise\b", 1.5), (r"\bfavou?rs?\b", 1.5), (r"\bblessings?\b", 1.2)],
    "family": [(r"\bparents\b", 3), (r"\bmother\b", 2.5), (r"\bfather\b", 1.5), (r"\bchildren\b", 1), (r"\brelatives\b", 2),
               (r"\bwi(fe|ves)\b", 1.5), (r"\bhusband", 1.5), (r"\bmarr", 2), (r"\bdivorce", 2.5), (r"\borphans?\b", 2.5)],
    "justice": [(r"\bjustice\b", 3), (r"\bjust(ly)?\b", 1.5), (r"\bequity\b", 3), (r"\bwitness(es)?\b", 1.2),
                (r"\binheritance\b", 3), (r"\bdebt\b", 2.5), (r"\bmeasure\b", 2), (r"\bweigh", 2), (r"\bcontract", 2.5)],
    "striving": [(r"\bfight", 2), (r"\bbattle\b", 2.5), (r"\bstriv", 2), (r"\bcause of allah\b", 1.5), (r"\barmy\b", 2),
                 (r"\bhorses?\b", 1)],
    "disbelief": [(r"\bhypocrit", 2.5), (r"\bdisbeliev", 0.8), (r"\bdeny\b", 1), (r"\bdeniers\b", 1.5), (r"\bdisease\b", 2),
                  (r"\bdeceiv", 1.5), (r"\bblind\b", 1.5), (r"\bdeaf\b", 1.5)],
    "signs": [(r"\bsigns\b", 1.5), (r"\bdo they not (see|look|reflect|consider)\b", 2.5), (r"\breflect\b", 1.5),
              (r"\balternation of the night\b", 3)],
    "supplication": [(r"\bour lord\b", 2), (r"\bmy lord\b", 1.5), (r"\binvoke\b", 1.5), (r"\bsupplicat", 3), (r"\bcall upon\b", 1.5),
                     (r"\bdu'?a\b", 3)],
    "remembrance": [(r"\bremembrance\b", 3), (r"\bremember\b", 1.2), (r"\bexalt", 1.5), (r"\bglorif", 2),
                    (r"\bhearts are assured\b", 4)],
    "trust": [(r"\brely\b", 3), (r"\breliance\b", 3), (r"\bsufficient for\b", 2), (r"\btrust\b", 1.5), (r"\bdisposer of affairs\b", 2.5)],
    "glad_tidings": [(r"\bglad tidings\b", 3), (r"\bvictory\b", 3), (r"\bconquest\b", 3), (r"\bsuccess(ful)?\b", 1.5),
                     (r"\bgood tidings\b", 3)],
    "creation_of_man": [(r"\bclay\b", 3), (r"\bsperm", 3), (r"\bclinging\b", 3), (r"\bwomb", 3), (r"\bcreated (you|man|mankind)\b", 2.5),
                        (r"\bdust\b", 1.5), (r"\badam\b", 2.5)],
    "unseen": [(r"\bangels?\b", 2), (r"\bunseen\b", 2.5), (r"\bjinn\b", 2), (r"\bspirit\b", 1.5), (r"\bgabriel\b", 2.5)],
}

# The full list, in the order the asset numbers them. "oneness" doubles as the fallback.
THEMES = list(LEXICON)
FALLBACK = "oneness"
assert len(THEMES) >= 50, len(THEMES)

# Pinned by hand: ayahs whose picture is well known, and surahs that are about one thing.
OVERRIDES = {
    (2, 255): "throne",
    (24, 35): "light_verse",
    (1, 1): "mercy",
    (55, 13): "paradise",
    (36, 38): "sun",
    (36, 39): "moon",
    (36, 40): "cosmos",
    (17, 1): "night",
    (2, 185): "fasting",
    (2, 183): "fasting",
    (2, 196): "hajj",
    (3, 96): "hajj",
    (22, 27): "hajj",
}
SURAH_OVERRIDES = {
    97: "night",       # Al-Qadr
    91: "sun",         # Ash-Shams
    92: "night",       # Al-Layl
    93: "dawn",        # Ad-Duha
    89: "dawn",        # Al-Fajr
    113: "dawn",       # Al-Falaq
    85: "stars",       # Al-Buruj
    86: "stars",       # At-Tariq
    71: "flood",       # Nuh
    101: "judgment",   # Al-Qari'ah
    99: "judgment",    # Az-Zalzalah
    81: "judgment",    # At-Takwir
    82: "judgment",    # Al-Infitar
    84: "judgment",    # Al-Inshiqaq
    112: "oneness",    # Al-Ikhlas
    109: "oneness",    # Al-Kafirun
    108: "rivers",     # Al-Kawthar
    105: "desert",     # Al-Fil (no animals are drawn: the desert where it happened)
    106: "desert",     # Quraysh, the caravans
    103: "dawn",       # Al-'Asr: time
    110: "glad_tidings",  # An-Nasr
    1: "mercy",        # Al-Fatihah
}

NEIGHBOUR = 0.4        # how much each adjacent ayah's scores count
SURAH_PRIOR = 0.15     # how much the surah's overall scores count
MIN_SCORE = 1.5        # below this, the ayah names nothing concrete enough to picture


def load_translation(arg):
    if arg:
        data = json.loads(Path(arg).read_text(encoding="utf-8"))
    else:
        with urllib.request.urlopen(SOURCE_URL, timeout=60) as response:
            data = json.loads(response.read().decode("utf-8"))
    ayahs = []
    for chapter in data:
        for verse in chapter["verses"]:
            ayahs.append((chapter["id"], verse["id"], verse["translation"]))
    assert len(ayahs) == TOTAL_AYAHS, len(ayahs)
    return ayahs


def score(text):
    lower = text.lower()
    return {key: sum(w * len(re.findall(rx, lower)) for rx, w in rules) for key, rules in LEXICON.items()}


def main():
    ayahs = load_translation(sys.argv[1] if len(sys.argv) > 1 else None)
    scores = [score(t) for _, _, t in ayahs]

    by_surah = {}
    for i, (s, _, _) in enumerate(ayahs):
        by_surah.setdefault(s, []).append(i)
    surah_totals = {s: Counter() for s in by_surah}
    for s, idx in by_surah.items():
        for i in idx:
            surah_totals[s].update(scores[i])

    picks = []
    for i, (s, a, _) in enumerate(ayahs):
        if (s, a) in OVERRIDES:
            picks.append(OVERRIDES[(s, a)])
            continue
        if s in SURAH_OVERRIDES:
            picks.append(SURAH_OVERRIDES[s])
            continue
        n = len(by_surah[s])
        combined = Counter(scores[i])
        for j in (i - 1, i + 1):
            if 0 <= j < len(ayahs) and ayahs[j][0] == s:
                for k, v in scores[j].items():
                    combined[k] += NEIGHBOUR * v
        for k, v in surah_totals[s].items():
            combined[k] += SURAH_PRIOR * v / n
        key, best = max(combined.items(), key=lambda kv: kv[1]) if combined else (FALLBACK, 0)
        if max(scores[i].values(), default=0) < MIN_SCORE and best < MIN_SCORE * 1.5:
            key = None  # decided below, from the ayahs around it
        picks.append(key)

    # An ayah that names nothing concrete takes the theme of the passage it sits in: the nearest
    # decided ayah in the same surah, else the surah's most common theme, else the fallback.
    decided = list(picks)
    for s, idx in by_surah.items():
        common = Counter(decided[i] for i in idx if decided[i])
        lead = common.most_common(1)[0][0] if common else FALLBACK
        for i in idx:
            if decided[i]:
                continue
            nearest = None
            for d in range(1, 4):
                for j in (i - d, i + d):
                    if j in idx and decided[j]:
                        nearest = decided[j]
                        break
                if nearest:
                    break
            picks[i] = nearest or lead

    index = {k: n for n, k in enumerate(THEMES)}
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "video_themes.json").write_text(
        json.dumps({"version": 1, "themes": THEMES, "ayahs": [index[p] for p in picks]}, separators=(",", ":")),
        encoding="utf-8",
    )
    (ASSETS / "translation_en_saheeh.json").write_text(
        json.dumps([t for _, _, t in ayahs], ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    counts = Counter(picks)
    print(f"{len(picks)} ayahs, {len(counts)} of {len(THEMES)} themes used")
    for key in THEMES:
        print(f"  {key:16} {counts[key]}")


if __name__ == "__main__":
    main()
