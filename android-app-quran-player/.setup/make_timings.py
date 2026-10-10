"""
Dev-time: build complete, validated word-timing assets from QUL for the reciters we ship.

Why this exists: the old word_timings.json covered only the repo owner's 1,841 ayahs, and it
copied QUL's raw segments verbatim — including a spurious ~70ms leading segment on 80 ayahs that
shifted every word's highlight by one and left the real final segment unused. This regenerates
the full Qur'an per reciter, cleans that artifact, and *validates* the result so the data cannot
silently regress. Ayahs that cannot be aligned are dropped rather than approximated.

QUL segment shape: [from_word_index, to_word_index, start_ms, end_ms], 11 ayahs per request.
QUL's audio is byte-identical to everyayah's for these reciters (checked), so the timings apply.
"""
import json
import os
import sys
import time
import urllib.request

# Resolved from this file's location so the script runs from any checkout. Raw QUL responses are
# cached next to wherever it is *run* from (`raw_<name>.json`), deliberately not in the repo.
ASSETS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets",
)
URL = "https://qul.tarteel.ai/api/v1/audio/ayah_segments/{id}?surah={s}&from={f}&page={p}"

# Ayahs asked for per request. **The response is paginated independently of this**, and at the
# time of writing it fits 10 ayahs to a page -- so a window of 11 comes back as page 1 with ten
# ayahs and page 2 with the eleventh. Walking `from` in steps of PAGE without reading page 2 drops
# every 11th ayah: Sudais came out at 5691/6236 with 2:11, 2:22, 2:33 ... missing in a perfect
# arithmetic progression, and `TimingDataIntegrityTest.coverage_is_effectively_complete` is what
# caught it. `fetch` now follows `pagination.next_page` to the end, which is correct whatever
# page size the API picks.
PAGE = 11

# asset suffix -> QUL recitation id.
#
# QUL's API does not name its reciters, so an id is confirmed the same way the audio is: the last
# segment's end_ms for an ayah has to land on the end of everyayah's MP3 for that reciter. Across
# 1:1, 1:7, 2:255, 112:1 and 112:2 every id below leaves 0-50ms of trailing silence (plus a longer
# tail on 2:255, which all five share), and no other id comes close. That measurement is what
# "the timings describe this exact recording" means here -- see CLAUDE.md.
RECITERS = {
    "maher": 13,
    "abdulbasit_murattal": 15,
    "husary": 20,
    "sudais": 16,
    "shuraym": 25,
}

VERSE_COUNTS = [
    7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110,
    98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88,
    75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24,
    13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42,
    29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8,
    3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6,
]

with open(os.path.join(ASSETS, "quran_text.json"), encoding="utf-8") as fh:
    TEXT = json.load(fh)

WORDS = {k: sum(1 for w in v["words"] if w["char_type"] != "end") for k, v in TEXT.items()}


def fetch_page(rid, surah, frm, page, attempts=4):
    """One page of one `from` window: (segments dict, next page number or None)."""
    last = None
    for i in range(attempts):
        try:
            req = urllib.request.Request(
                URL.format(id=rid, s=surah, f=frm, p=page),
                headers={"User-Agent": "learned-ayahs-timingsgen/1.0"},
            )
            with urllib.request.urlopen(req, timeout=60) as r:
                body = json.loads(r.read().decode("utf-8"))
            pagination = body.get("pagination") or {}
            return (body.get("segments") or {}), pagination.get("next_page")
        except Exception as exc:  # noqa: BLE001
            last = exc
            time.sleep(1.5 * (i + 1))
    print(f"  WARN {rid} {surah}:{frm} page {page} failed: {last}", file=sys.stderr)
    return {}, None


def fetch(rid, surah, frm):
    """Every ayah in the `from` window, following pagination to the last page."""
    out, page = {}, 1
    while page:
        segments, page = fetch_page(rid, surah, frm, page)
        out.update(segments)
        if page:
            time.sleep(0.05)
    return out


def to_pair(s):
    """
    QUL is mostly [from_word, to_word, start, end], but a handful of ayahs (94:3 for Abdul
    Basit) use [word, start, end]. Anything else we don't understand well enough to trust.
    """
    if len(s) >= 4:
        return [s[2], s[3]]
    if len(s) == 3:
        return [s[1], s[2]]
    return None


def clean(raw, want):
    """
    Raw QUL segments -> `want` non-overlapping [start, end] pairs, or None if it can't be
    aligned honestly.

    QUL sometimes reports one more 'word' than the text has, the extra being a very short
    leading blip that overlaps the next segment (e.g. 2:21 gives [0,70] then [40,2080]).
    Real speech segments never overlap, so that blip is identifiable and droppable.
    """
    pairs = [to_pair(s) for s in raw]
    if any(p is None for p in pairs):
        return None
    segs = sorted(pairs, key=lambda p: (p[0], p[1]))

    # Drop leading blips that overlap what follows, while we have more than we need.
    while len(segs) > want and len(segs) >= 2:
        a, b = segs[0], segs[1]
        if b[0] < a[1] or (a[1] - a[0]) < 150:
            segs.pop(0)
            continue
        break

    # A trailing extra is usually the end-of-ayah glyph, which carries no meaning of its own.
    while len(segs) > want:
        segs.pop()

    if len(segs) != want:
        return None
    for i, (st, en) in enumerate(segs):
        if en <= st:
            return None
        if i and st < segs[i - 1][1]:
            return None  # still overlapping: don't trust it
    return segs


# Words the shipped text holds as one but QUL times as two, by ayah and 1-based word position.
# بَعْدَ مَا is a single word in the Quran.com text this app uses, while QUL — keyed by spoken
# words — gives each half its own segment. Unhandled, the extra segment shifted every later
# highlight one word early, and `clean` then threw away the *last* real segment as if it were the
# end-of-ayah glyph. The halves are timed separately here and merged back into their one word.
QUL_SPLIT_WORDS = {"2:181": [3], "8:6": [4], "13:37": [8]}


def merge_split_words(key, segs):
    split = set(QUL_SPLIT_WORDS.get(key, ()))
    out, i, word = [], 0, 1
    while i < len(segs):
        if word in split and i + 1 < len(segs):
            out.append([segs[i][0], segs[i + 1][1]])
            i += 2
        else:
            out.append(segs[i])
            i += 1
        word += 1
    return out


# Optional CLI filter: `python make_timings.py sudais shuraym` regenerates only those assets.
# Without it every reciter is rebuilt, which costs ~570 QUL requests each.
SELECTED = sys.argv[1:] or list(RECITERS)
for unknown in [n for n in SELECTED if n not in RECITERS]:
    sys.exit(f"unknown reciter '{unknown}'; known: {', '.join(RECITERS)}")

for name in SELECTED:
    rid = RECITERS[name]
    cache = f"raw_{name}.json"
    if os.path.exists(cache):
        with open(cache, encoding="utf-8") as fh:
            raw_all = json.load(fh)
        print(f"[{name}] loaded {len(raw_all)} ayahs from cache")
    else:
        raw_all = {}
        for surah in range(1, 115):
            frm = 1
            while frm <= VERSE_COUNTS[surah - 1]:
                for key, val in fetch(rid, surah, frm).items():
                    if val.get("segments"):
                        raw_all[key] = val["segments"]
                frm += PAGE
                time.sleep(0.05)
            print(f"[{name}] surah {surah:3d}/114  ayahs={len(raw_all)}", flush=True)
        with open(cache, "w", encoding="utf-8") as fh:
            json.dump(raw_all, fh)

    out, dropped = {}, []
    for key, want in WORDS.items():
        raw = raw_all.get(key)
        if not raw:
            dropped.append((key, "missing"))
            continue
        spoken = want + len(QUL_SPLIT_WORDS.get(key, ()))
        segs = clean(raw, spoken)
        if segs is None:
            dropped.append((key, f"{len(raw)} segs vs {spoken} spoken words"))
            continue
        out[key] = {"segments": merge_split_words(key, segs)}

    dest = os.path.join(ASSETS, f"word_timings_{name}.json")
    with open(dest, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, separators=(",", ":"))

    pct = 100 * len(out) / 6236
    print(f"\n[{name}] VALID {len(out)}/6236 ({pct:.1f}%)   dropped {len(dropped)}")
    for k, why in dropped[:8]:
        print(f"    {k}: {why}")
    print(f"[{name}] wrote {dest} ({os.path.getsize(dest)/1024:.0f} KB)\n")
