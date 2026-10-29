#!/usr/bin/env python3
"""Checks a reciter's word timings against the recordings they describe, ayah by ayah.

    python .setup/audit_timings.py maher [--limit N] [--out timing-audit]

make_timings.py validates the timings' own shape (one segment per word, ordered, no overlaps);
this checks them against the sound. It downloads every ayah MP3 of the reciter from everyayah.com
(cached in <out>/audio/), measures the loudness every 20 ms, and asks of each ayah:

- does the first word begin where the voice begins? (an offset shifts every highlight)
- does the last word begin before the voice ends? (if not, it can never light up)
- does every pause in the recitation end at a word's onset?
- does any word "begin" in the middle of a pause?

Each ayah is then one of: fine as it is; out by a constant amount, which shifting every segment
by the measured offset puts right (checked by the same four questions); or neither, and its
timings should be dropped so it is shown without highlighting rather than with wrong highlighting.
The verdicts go to .setup/timing_fixes/<reciter>.json, which make_timings.py applies whenever it
regenerates the asset, and apply_timing_fixes.py applies to the asset as it stands.

Writes <out>/<reciter>.csv with the measurements and prints the worst ayahs. Needs network access
to everyayah.com, ffmpeg on PATH and numpy.
"""
import argparse
import concurrent.futures
import csv
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
FIXES = HERE / "timing_fixes"
FOLDERS = {
    "maher": "MaherAlMuaiqly128kbps",
    "abdulbasit_murattal": "Abdul_Basit_Murattal_192kbps",
    "husary": "Husary_128kbps",
    "sudais": "Abdurrahmaan_As-Sudais_192kbps",
    "shuraym": "Saood_ash-Shuraym_128kbps",
}

RATE = 8000
FRAME_MS = 20
PAUSE_MIN_MS = 160      # quieter than speech for at least this long counts as a pause
LEAD_TOLERANCE_MS = 250
PAUSE_TOLERANCE_MS = 160
MIN_SHIFT_MS = 120      # a smaller offset isn't worth correcting


def fetch(url, dest):
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            tmp = dest.with_suffix(".part")
            tmp.write_bytes(data)
            tmp.rename(dest)
            return dest
        except Exception:
            time.sleep(2 ** attempt)
    raise RuntimeError(f"could not fetch {url}")


def envelope(path):
    """Loudness in dB per 20 ms frame of the recording, mono at 8 kHz."""
    pcm = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path), "-ac", "1", "-ar", str(RATE), "-f", "f32le", "-"],
        capture_output=True, check=True,
    ).stdout
    samples = np.frombuffer(pcm, dtype=np.float32)
    n = RATE * FRAME_MS // 1000
    frames = len(samples) // n
    if frames == 0:
        return np.array([-120.0])
    rms = np.sqrt(np.mean(samples[: frames * n].reshape(frames, n) ** 2, axis=1))
    return 20 * np.log10(rms + 1e-9)


def speech_mask(db):
    """Which frames are voice: well above the recording's own quiet floor, held for 60 ms."""
    floor = np.percentile(db, 10)
    peak = np.percentile(db, 95)
    threshold = max(floor + 0.35 * (peak - floor), peak - 35)
    loud = db > threshold
    # Ignore blips shorter than 60 ms.
    mask = loud.copy()
    run = 0
    for i, v in enumerate(loud):
        run = run + 1 if v else 0
        if 0 < run < 3 and (i + 1 >= len(loud) or not loud[i + 1]):
            mask[i - run + 1: i + 1] = False
    return mask


def pauses_of(mask, start, end):
    """(start ms, end ms) of the quiet runs of at least PAUSE_MIN_MS inside [start, end)."""
    out = []
    i = start
    while i < end:
        if not mask[i]:
            j = i
            while j < end and not mask[j]:
                j += 1
            if (j - i) * FRAME_MS >= PAUSE_MIN_MS:
                out.append((i * FRAME_MS, j * FRAME_MS))
            i = j
        else:
            i += 1
    return out


def judge(onsets, voice_start, voice_end, pauses):
    """How well a set of word onsets fits the recording."""
    matched = sum(1 for _, e in pauses if any(abs(o - e) <= PAUSE_TOLERANCE_MS for o in onsets))
    in_pause = sum(1 for o in onsets[1:] if any(s + 100 < o < e - 100 and e - s >= 300 for s, e in pauses))
    lead = onsets[0] - voice_start
    ok = (
        abs(lead) <= LEAD_TOLERANCE_MS
        and onsets[-1] < voice_end - 40
        and in_pause == 0
        and (not pauses or matched >= max(1, round(0.6 * len(pauses))))
    )
    return ok, matched, in_pause, lead


def audit(key, segments, path):
    db = envelope(path)
    mask = speech_mask(db)
    voiced = np.flatnonzero(mask)
    total = len(db) * FRAME_MS
    if len(voiced) == 0:
        return {"ayah": key, "verdict": "drop", "reason": "no voice found", "shift_ms": 0, "duration_ms": total}
    first, last = int(voiced[0]), int(voiced[-1]) + 1
    voice_start, voice_end = first * FRAME_MS, last * FRAME_MS
    pauses = pauses_of(mask, first, last)
    onsets = [s[0] for s in segments]

    ok, matched, in_pause, lead = judge(onsets, voice_start, voice_end, pauses)
    row = {
        "ayah": key, "words": len(segments), "duration_ms": total, "voice_start_ms": voice_start, "voice_end_ms": voice_end,
        "first_onset_ms": onsets[0], "last_onset_ms": onsets[-1], "lead_offset_ms": lead, "pauses": len(pauses),
        "matched_pauses": matched, "onsets_in_pause": in_pause, "shift_ms": 0, "verdict": "fine", "reason": "",
    }
    if ok:
        return row
    shift = voice_start - onsets[0]
    if abs(shift) >= MIN_SHIFT_MS:
        moved = [o + shift for o in onsets]
        ok2, m2, p2, _ = judge(moved, voice_start, voice_end, pauses)
        if ok2 and moved[0] >= 0:
            row.update(verdict="shift", shift_ms=shift, reason=f"all words {abs(shift)} ms {'late' if shift < 0 else 'early'}; shifted, {m2}/{len(pauses)} pauses fit")
            return row
    reasons = []
    if abs(lead) > LEAD_TOLERANCE_MS:
        reasons.append(f"first word {lead:+d} ms from the voice")
    if onsets[-1] >= voice_end - 40:
        reasons.append(f"last word starts at {onsets[-1]} ms, voice ends {voice_end} ms")
    if in_pause:
        reasons.append(f"{in_pause} words start inside a pause")
    if pauses and matched < max(1, round(0.6 * len(pauses))):
        reasons.append(f"{len(pauses) - matched}/{len(pauses)} pauses with no word after them")
    row.update(verdict="drop", reason="; ".join(reasons))
    return row


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reciter", choices=sorted(FOLDERS))
    parser.add_argument("--limit", type=int, default=0, help="only the first N ayahs (a quick try)")
    parser.add_argument("--out", default="timing-audit")
    args = parser.parse_args()

    timings = json.loads((ASSETS / f"word_timings_{args.reciter}.json").read_text(encoding="utf-8"))
    keys = sorted(timings, key=lambda k: tuple(map(int, k.split(":"))))
    if args.limit:
        keys = keys[: args.limit]
    out = Path(args.out)
    audio = out / "audio" / args.reciter
    folder = FOLDERS[args.reciter]

    def download(key):
        s, a = map(int, key.split(":"))
        name = f"{s:03d}{a:03d}.mp3"
        return key, fetch(f"https://everyayah.com/data/{folder}/{name}", audio / name)

    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        files = dict(pool.map(download, keys))
    print(f"{len(files)} recordings", flush=True)

    with concurrent.futures.ProcessPoolExecutor() as pool:
        rows = list(pool.map(audit, keys, [timings[k]["segments"] for k in keys], [files[k] for k in keys], chunksize=32))

    out.mkdir(parents=True, exist_ok=True)
    fields = list(dict.fromkeys(f for r in rows for f in r))
    with open(out / f"{args.reciter}.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    shifts = {r["ayah"]: {"first_onset_ms": r["first_onset_ms"], "shift_ms": r["shift_ms"], "why": r["reason"]} for r in rows if r["verdict"] == "shift"}
    drops = {r["ayah"]: r["reason"] for r in rows if r["verdict"] == "drop"}
    if not args.limit:
        FIXES.mkdir(exist_ok=True)
        (FIXES / f"{args.reciter}.json").write_text(json.dumps({
            "reciter": args.reciter,
            "about": "Written by audit_timings.py from everyayah's recordings. shift: move every segment of the ayah by shift_ms "
                     "(applied only while its first onset is still first_onset_ms). drop: remove the ayah's timings.",
            "shift": shifts,
            "drop": drops,
        }, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"\n{args.reciter}: {len(rows)} ayahs: {len(rows) - len(shifts) - len(drops)} fine, {len(shifts)} shifted, {len(drops)} dropped")
    print("\nShifted (largest first):")
    for k, v in sorted(shifts.items(), key=lambda kv: -abs(kv[1]["shift_ms"]))[:40]:
        print(f"  {k:>8}  {v['shift_ms']:+6d} ms   {v['why']}")
    print("\nDropped:")
    for k, why in list(drops.items())[:80]:
        print(f"  {k:>8}  {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
