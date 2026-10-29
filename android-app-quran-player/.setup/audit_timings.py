#!/usr/bin/env python3
"""Checks a reciter's word timings against the recordings they describe, ayah by ayah.

    python .setup/audit_timings.py maher [--limit N] [--out timing-audit]

make_timings.py validates the timings' own shape (one segment per word, ordered, no overlaps);
this checks them against the sound. It downloads every ayah MP3 of the reciter from everyayah.com
(cached in <out>/audio/), finds the silences with ffmpeg's silencedetect, and asks of each ayah:

- does the first word begin where the voice begins? (an offset shifts every highlight)
- does the last word begin before the voice ends? (if not, it can never light up)
- does every audible pause end at a word's onset? (a pause with no word starting after it means
  the timings have drifted from the audio)
- does any word "begin" in the middle of a long silence?

Writes <out>/<reciter>.csv with the measurements and flags, and prints the worst ayahs.
Needs network access to everyayah.com and ffmpeg on PATH.
"""
import argparse
import concurrent.futures
import csv
import json
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
FOLDERS = {
    "maher": "MaherAlMuaiqly128kbps",
    "abdulbasit_murattal": "Abdul_Basit_Murattal_192kbps",
    "husary": "Husary_128kbps",
    "sudais": "Abdurrahmaan_As-Sudais_192kbps",
    "shuraym": "Saood_ash-Shuraym_128kbps",
}

SILENCE_DB = -35       # quieter than this counts as silence
SILENCE_MIN_S = 0.15   # a pause must last at least this long to count
PAUSE_TOLERANCE_MS = 250
LEAD_TOLERANCE_MS = 300


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


def silences(path):
    """(duration ms, [(start ms, end ms), ...]) of the silences in an MP3."""
    run = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-i", str(path), "-af",
         f"silencedetect=noise={SILENCE_DB}dB:d={SILENCE_MIN_S}", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    log = run.stderr
    duration = re.search(r"Duration: (\d+):(\d+):([\d.]+)", log)
    total = (int(duration.group(1)) * 3600 + int(duration.group(2)) * 60 + float(duration.group(3))) * 1000 if duration else 0
    starts = [float(x) * 1000 for x in re.findall(r"silence_start: (-?[\d.]+)", log)]
    ends = [float(x) * 1000 for x in re.findall(r"silence_end: ([\d.]+)", log)]
    out = []
    for i, s in enumerate(starts):
        out.append((max(0.0, s), ends[i] if i < len(ends) else total))
    return total, out


def audit(key, segments, path):
    total, quiet = silences(path)
    lead = quiet[0][1] if quiet and quiet[0][0] <= 30 else 0.0
    sound_end = quiet[-1][0] if quiet and quiet[-1][1] >= total - 150 else total  # MP3s end with a little padding
    onsets = [s[0] for s in segments]
    # Pauses inside the recitation: not the leading or trailing silence.
    pauses = [(s, e) for s, e in quiet if s > lead + 1 and e < sound_end - 1]
    unmatched = [p for p in pauses if not any(abs(o - p[1]) <= PAUSE_TOLERANCE_MS for o in onsets)]
    in_silence = [o for o in onsets[1:] if any(s + 150 < o < e - 150 and e - s >= 400 for s, e in quiet)]
    lead_offset = onsets[0] - lead
    flags = []
    if abs(lead_offset) > LEAD_TOLERANCE_MS:
        flags.append("first-word-offset")
    if onsets[-1] >= sound_end:
        flags.append("last-word-after-audio")
    if pauses and len(unmatched) / len(pauses) > 0.5 and len(unmatched) >= 2:
        flags.append("pauses-unmatched")
    if in_silence:
        flags.append("onset-in-silence")
    return {
        "ayah": key, "words": len(segments), "duration_ms": round(total), "voice_start_ms": round(lead),
        "first_onset_ms": onsets[0], "lead_offset_ms": round(lead_offset), "voice_end_ms": round(sound_end),
        "last_onset_ms": onsets[-1], "last_segment_end_ms": segments[-1][1],
        "pauses": len(pauses), "unmatched_pauses": len(unmatched), "onsets_in_silence": len(in_silence),
        "flags": " ".join(flags),
    }


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
    with open(out / f"{args.reciter}.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    flagged = [r for r in rows if r["flags"]]
    print(f"\n{args.reciter}: {len(rows)} ayahs, {len(flagged)} flagged")
    for flag in ["first-word-offset", "last-word-after-audio", "pauses-unmatched", "onset-in-silence"]:
        print(f"  {flag}: {sum(flag in r['flags'] for r in rows)}")
    offsets = sorted(rows, key=lambda r: -abs(r["lead_offset_ms"]))[:25]
    print("\nLargest first-word offsets (timing onset minus where the voice starts):")
    for r in offsets:
        print(f"  {r['ayah']:>8}  {r['lead_offset_ms']:>6} ms   voice {r['voice_start_ms']} ms, first onset {r['first_onset_ms']} ms")
    print("\nMost pauses with no word starting after them:")
    for r in sorted(rows, key=lambda r: -r["unmatched_pauses"])[:25]:
        print(f"  {r['ayah']:>8}  {r['unmatched_pauses']}/{r['pauses']} pauses unmatched, {r['onsets_in_silence']} onsets in silence")
    print("\nLast word starting after the voice ends:")
    for r in [r for r in rows if "last-word-after-audio" in r["flags"]][:25]:
        print(f"  {r['ayah']:>8}  last onset {r['last_onset_ms']} ms, voice ends {r['voice_end_ms']} ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())
