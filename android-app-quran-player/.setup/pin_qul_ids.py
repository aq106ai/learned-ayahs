#!/usr/bin/env python3
"""Finds which QUL recitation id, if any, describes an everyayah recording — by measurement.

    python .setup/pin_qul_ids.py Alafasy_128kbps Minshawy_Murattal_128kbps ...
    python .setup/pin_qul_ids.py --list          # every QUL id with the audio it times

Highlighting is only honest with timings for the exact recording (CLAUDE.md), and QUL's ids are
not names. For each everyayah folder this checks, on 1:1, 1:7, 2:255, 112:1 and 112:2:

  * bytes: is everyayah's MP3 the same file as the audio QUL says it timed (`audio_url`)?
    (SHA-256 of both downloads.)
  * ends:  does QUL's last segment end where everyayah's MP3 ends? Measured against ffprobe's
    duration, which for these MP3s is only good to a couple of hundred ms: Maher, shipped and
    byte-identical to QUL 13, reads -177..+7 ms. So this is a sanity bound (±250 ms), not the
    0-50 ms the shipped five were first pinned with; 2:255 carries a long tail for everyone.
  * words: one segment per content word of our text.

An id is only proposed when it wins on every check. Byte-identical audio is the strong evidence;
the end check is the one the existing five were pinned by. Nothing is written: the result goes
into make_timings.RECITERS by hand, then audit_timings.py judges every ayah.

Needs ffprobe on PATH. Downloads are cached under .setup/.pin_cache/ (not committed).
"""
import argparse
import hashlib
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE / ".pin_cache"
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
QUL = "https://qul.tarteel.ai/api/v1/audio/ayah_segments/{id}?surah={s}&from={f}&page=1"
EVERYAYAH = "https://everyayah.com/data/{folder}/{s:03d}{a:03d}.mp3"
PROBES = [(1, 1), (1, 7), (2, 255), (112, 1), (112, 2)]
UA = {"User-Agent": "learned-ayahs-pin/1.0"}
MAX_ID = 300
END_SLACK_MS = 250  # ffprobe's MP3 duration is approximate (see above); 2:255 is reported, not required


def get(url, attempts=4):
    last = None
    for i in range(attempts):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read()
        except Exception as exc:  # noqa: BLE001
            last = exc
            time.sleep(1.5 * (i + 1))
    raise last


def cached(name, url):
    path = CACHE / name
    if not path.is_file():
        path.parent.mkdir(parents=True, exist_ok=True)
        data = get(url)
        tmp = path.with_suffix(path.suffix + ".part")
        tmp.write_bytes(data)
        tmp.replace(path)
    return path


def qul(rid, s, a):
    """(audio_url, segments) for one ayah, or (None, None)."""
    body = json.loads(cached(f"qul/{rid}/{s}_{a}.json", QUL.format(id=rid, s=s, f=a)).read_text(encoding="utf-8"))
    row = (body.get("segments") or {}).get(f"{s}:{a}")
    return (row.get("audio_url"), row.get("segments")) if row else (None, None)


def duration_ms(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True, text=True, check=True,
    ).stdout.strip()
    return round(float(out) * 1000)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def content_words():
    text = json.loads((ASSETS / "quran_text.json").read_text(encoding="utf-8"))
    return {k: sum(1 for w in v["words"] if w["char_type"] != "end") for k, v in text.items()}


def list_ids():
    found = {}
    misses = 0
    for rid in range(1, MAX_ID + 1):
        url, _ = qul(rid, 112, 1)
        if url:
            found[rid] = url.rsplit("/", 1)[0]
            misses = 0
        else:
            misses += 1
    return found


def main():
    p = argparse.ArgumentParser()
    p.add_argument("folders", nargs="*")
    p.add_argument("--list", action="store_true")
    a = p.parse_args()
    ids = list_ids()
    if a.list or not a.folders:
        for rid, base in ids.items():
            print(f"{rid:4d}  {base}")
        return
    words = content_words()
    results = {}
    for folder in a.folders:
        print(f"\n== {folder}")
        ours = {}
        for s, ay in PROBES:
            f = cached(f"everyayah/{folder}/{s:03d}{ay:03d}.mp3", EVERYAYAH.format(folder=folder, s=s, a=ay))
            ours[(s, ay)] = (duration_ms(f), sha(f))
        rows = []
        for rid in ids:
            checks = []
            for s, ay in PROBES:
                url, segs = qul(rid, s, ay)
                if not segs:
                    checks.append(None)
                    continue
                theirs = cached(f"qulaudio/{rid}/{s:03d}{ay:03d}.mp3", url)
                dur, digest = ours[(s, ay)]
                checks.append({
                    "same_bytes": sha(theirs) == digest,
                    "gap": dur - round(float(segs[-1][-1])),  # rows are [from, to, start, end] or [word, start, end]
                    "words_ok": len(segs) == words[f"{s}:{ay}"] and all(len(x) == 3 or int(x[1]) - int(x[0]) == 1 for x in segs),
                })
            if any(c is None for c in checks):
                continue
            rows.append((rid, checks))
        def rank(row):
            rid, checks = row
            return (-sum(c["same_bytes"] for c in checks), sum(abs(c["gap"]) for c in checks[:2] + checks[3:]))
        rows.sort(key=rank)
        for rid, checks in rows[:4]:
            print(f"  QUL {rid:4d} {ids[rid]}")
            for (s, ay), c in zip(PROBES, checks):
                print(f"      {s}:{ay:<4} same bytes={c['same_bytes']!s:5}  trailing gap={c['gap']:6d} ms  words ok={c['words_ok']}")
        best = rows[0] if rows else None
        if best:
            rid, checks = best
            ends = all(abs(c["gap"]) <= END_SLACK_MS for (s, ay), c in zip(PROBES, checks) if (s, ay) != (2, 255))
            same = all(c["same_bytes"] and c["words_ok"] for c in checks)
            runner = rows[1] if len(rows) > 1 else None
            unique = runner is None or rank(runner) != rank(best)
            ok = same and ends and unique
            results[folder] = rid if ok else None
            print(f"  -> {'PIN QUL ' + str(rid) if ok else 'no id passes'} (same bytes and one segment per word on all: {same}, ends within {END_SLACK_MS} ms: {ends}, unique: {unique})")
        else:
            results[folder] = None
            print("  -> no QUL id times all five probe ayahs")
    print("\n" + json.dumps(results, indent=1))


if __name__ == "__main__":
    sys.exit(main())
