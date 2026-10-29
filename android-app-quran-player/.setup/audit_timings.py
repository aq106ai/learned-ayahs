#!/usr/bin/env python3
"""Checks a reciter's word timings against the recordings they describe, ayah by ayah.

    python .setup/audit_timings.py maher [--limit N] [--out timing-audit] [--asset]

make_timings.py validates the timings' own shape (one segment per word, ordered, no overlaps);
this checks them against the sound. It downloads every ayah MP3 of the reciter from everyayah.com
(cached in <out>/audio/), measures the loudness every 20 ms, and judges QUL's cleaned timings as
make_timings.generate() produces them (from QUL, or its raw_<reciter>.json cache), so re-running it
gives the same verdicts whatever the asset already holds. --asset judges the shipped asset instead.

What it finds, and does about it:

- **The last word timed in the trailing silence.** For ~18% of ayahs, every reciter, QUL's last
  segment is a 20-110 ms sliver at the very end of the file, after the recitation has ended, and
  the real last word is inside the segment before it: the highlight never reaches the last word,
  and Word by word plays the last two words as one. The last word's start is found in the
  recording: the dip in loudness nearest where the letters of the last two words put it. The
  estimator is scored first on the ayahs whose last word QUL did time, with that onset hidden, and
  the run fails rather than write repairs unless the best way clearly beats leaving the sliver
  (see gate()). Measured on Maher: within 160 ms for half the ayahs, 460 ms for nine in ten, where
  the sliver itself is out by the whole last word.
- **The first word started at 0 on a recording that opens with silence**: moved to the voice.
- **The whole ayah out by a constant amount**, where both ends agree: shifted.
- **Timings that don't fit the recording at all** (a different recording, a first word seconds
  from the voice, words starting inside long silences): dropped, so the ayah shows without
  highlighting rather than with the wrong word lit.

The verdicts go to .setup/timing_fixes/<reciter>.json, which make_timings.py applies whenever it
regenerates the asset, and apply_timing_fixes.py applies to the asset as it stands. Writes
<out>/<reciter>.csv with the measurements. Needs network access to everyayah.com (and QUL unless
cached), ffmpeg on PATH and numpy.
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
LEAD_TOLERANCE_MS = 300
TAIL_TOLERANCE_MS = 300  # an ayah's last word often fades out below the loudness threshold
SILENCE_MS = 600        # a word can't begin well inside a silence this long
PAUSE_TOLERANCE_MS = 160
MIN_SHIFT_MS = 150      # a smaller offset isn't worth correcting
SLIVER_MS = 120         # a last word shorter than this, ending at the file's end, is QUL's artefact
EDGE_MS = 250           # the last word starts at least this far from the word before and the end

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


def speech_mask(db, share=0.35):
    """Which frames are voice: well above the recording's own quiet floor, held for 60 ms.
    [share] places the threshold between the floor and the peak; lower catches quiet endings."""
    floor = np.percentile(db, 10)
    peak = np.percentile(db, 95)
    threshold = max(floor + share * (peak - floor), peak - 40)
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
    """How well a set of word onsets fits the recording. Pauses are only evidence here, never a
    reason on their own: a dip in loudness inside a word (a stop, a nasal) looks like one too."""
    matched = sum(1 for _, e in pauses if any(abs(o - e) <= PAUSE_TOLERANCE_MS for o in onsets))
    in_pause = sum(1 for o in onsets[1:] if any(s + 250 < o < e - 250 and e - s >= SILENCE_MS for s, e in pauses))
    lead = onsets[0] - voice_start
    ok = abs(lead) <= LEAD_TOLERANCE_MS and onsets[-1] < voice_end + TAIL_TOLERANCE_MS and in_pause == 0
    return ok, matched, in_pause, lead


def letters(word):
    """How many letters a word is written with (harakat and Quranic marks don't count)."""
    return max(1, sum(1 for c in word if "\u0621" <= c <= "\u064a" or c in "\u0670\u0671"))


def gaps_of(mask, start_ms, end_ms, min_frames):
    """(start ms, end ms) of each quiet run of at least [min_frames] inside the window."""
    out = []
    i = max(0, start_ms // FRAME_MS)
    end = min(len(mask), end_ms // FRAME_MS)
    while i < end:
        if not mask[i]:
            j = i
            while j < end and not mask[j]:
                j += 1
            if j - i >= min_frames:
                out.append((i * FRAME_MS, j * FRAME_MS))
            i = j
        else:
            i += 1
    return out


def dips_of(db, start_ms, end_ms, prominence):
    """Times of loudness minima inside the window at least [prominence] dB below both sides."""
    smooth = np.convolve(db, np.ones(3) / 3, mode="same")
    reach = 5  # 100 ms either side
    out = []
    for i in range(max(reach, start_ms // FRAME_MS), min(len(smooth) - reach, end_ms // FRAME_MS)):
        v = smooth[i]
        if v > smooth[i - 1] or v > smooth[i + 1]:
            continue
        if min(smooth[i - reach:i].max(), smooth[i + 1:i + 1 + reach].max()) - v >= prominence:
            out.append(i * FRAME_MS)
    return out


KINDS = ("gap2-mid", "gap2-end", "dip3", "dip6")

# Ways of finding the last word's start between the word before (P) and the voice's end (E). Each
# is scored on the ayahs QUL timed properly, and the best that passes the gate is used:
# - sliver: leaving it where QUL puts it on those ayahs, at the file's end (what a repair must beat);
# - letters: where the letters of the last two words put it between the measured start of the word
#   before and the measured end of the voice;
# - near-<kind>-<t>: the dip in loudness nearest that, within t% of the span;
# - fit: E minus the reciter's own duration for a last word of that many letters, fitted on the
#   ayahs QUL timed properly (and scored on others than it was fitted on);
# - fit-snap-<kind>-<ms>: that, moved to a dip in loudness within ms of it.
VARIANTS = ["sliver", "letters", "fit"] + [f"near-{k}-{t}" for k in KINDS for t in (35, 50)] + [
    f"fit-snap-{k}-{ms}" for k in KINDS for ms in (60, 120, 200)]


def window_of(db, mask, p, e, share_before, last_letters, end):
    """What the estimators need to place the last word: the span and its dips in loudness."""
    lo, hi = p + EDGE_MS, e - EDGE_MS
    if hi <= lo:
        return None
    return {"p": p, "e": e, "end": end, "share": share_before, "last": last_letters, "points": {
        "gap2-mid": [(a + b) // 2 for a, b in gaps_of(mask, lo, hi, 2)],
        "gap2-end": [b for _, b in gaps_of(mask, lo, hi, 2)],
        "dip3": dips_of(db, lo, hi, 3),
        "dip6": dips_of(db, lo, hi, 6),
    }}


def estimate(w, variant, model):
    """The last word's start by [variant], or None; [model] is fit()'s (a, b)."""
    if not w:
        return None
    lo, hi = w["p"] + EDGE_MS, w["e"] - EDGE_MS
    clamp = lambda t: int(min(max(t, lo), hi)) // FRAME_MS * FRAME_MS  # noqa: E731
    expected = w["p"] + (w["e"] - w["p"]) * w["share"]
    if variant == "sliver":
        # What QUL does on the ayahs this repairs: the last word ~50 ms before the end of the file.
        return w["end"] - 50
    if variant == "letters":
        return clamp(expected)
    fitted = clamp(w["e"] - (model[0] + model[1] * w["last"]))
    if variant == "fit":
        return fitted
    parts = variant.split("-")
    if parts[0] == "near":
        kind, reach, centre = f"{parts[1]}-{parts[2]}" if parts[1] == "gap2" else parts[1], None, expected
        reach = int(parts[-1]) / 100 * (w["e"] - w["p"])
    else:
        kind = f"{parts[2]}-{parts[3]}" if parts[2] == "gap2" else parts[2]
        reach, centre = int(parts[-1]), fitted
    near = [t for t in w["points"][kind] if abs(t - centre) <= reach]
    if near:
        return min(near, key=lambda t: abs(t - centre))
    return None if parts[0] == "near" else fitted


def fit(rows):
    """Least squares (a, b) for: voice end - last onset = a + b * letters of the last word."""
    xs = np.array([r["window"]["last"] for r in rows], dtype=float)
    ys = np.array([r["window"]["e"] - r["segments"][-1][0] for r in rows], dtype=float)
    if len(xs) < 10:
        return (600.0, 60.0)
    b, a = np.polyfit(xs, ys, 1)
    return (float(a), float(b))


def measure(key, segments, words, path):
    """Everything the verdicts need about one ayah, measured from its recording."""
    db = envelope(path)
    mask = speech_mask(db)
    total = len(db) * FRAME_MS
    # Where the voice starts and ends is taken with a gentler threshold: recitation often fades
    # in and out more quietly than it is recited.
    gentle = np.flatnonzero(speech_mask(db, share=0.15))
    if len(gentle) == 0:
        return {"ayah": key, "segments": segments, "duration_ms": total, "silent": True}
    first, last = int(gentle[0]), int(gentle[-1]) + 1
    voice_start, voice_end = first * FRAME_MS, last * FRAME_MS
    faint = np.flatnonzero(speech_mask(db, share=0.05))
    row = {
        "ayah": key, "segments": segments, "duration_ms": total, "voice_start_ms": voice_start, "voice_end_ms": voice_end,
        "faint_end_ms": int(faint[-1] + 1) * FRAME_MS, "pauses": pauses_of(mask, first, last), "window": None,
    }
    if len(segments) >= 2 and len(words) >= 2:
        before, lastw = letters(words[-2]), letters(words[-1])
        row["window"] = window_of(db, mask, segments[-2][0], voice_end, before / (before + lastw), lastw, segments[-1][1])
    return row


def verdict(row, found):
    """(kind, detail, fixes) for one measured ayah; [found] is where its last word starts if QUL
    timed it in the trailing silence (None: not found, or no estimator good enough)."""
    segs = row["segments"]
    if row.get("silent"):
        return "drop", "no voice found", {}
    total, start, end = row["duration_ms"], row["voice_start_ms"], row["voice_end_ms"]
    onsets = [s[0] for s in segs]
    last_end = segs[-1][1]
    if last_end > total + 500:
        return "drop", f"timings run to {last_end} ms on a {total} ms recording", {}
    if last_end < row["faint_end_ms"] - 1000:
        return "drop", f"timings end at {last_end} ms, the recitation at {row['faint_end_ms']} ms", {}

    fixes = {}
    if len(segs) >= 2 and segs[-1][1] - segs[-1][0] < SLIVER_MS:
        if found is None:
            return "drop", "last word timed in the trailing silence, and its start isn't clear in the recording", {}
        fixes["last_onset"] = {"from": onsets[-1], "to": found}
        onsets[-1] = found
    if len(segs) >= 2 and onsets[0] == 0 and start > LEAD_TOLERANCE_MS and start < min(onsets[1] - 100, segs[0][1]):
        fixes["first_onset"] = {"from": 0, "to": start}
        onsets[0] = start

    pauses = row["pauses"]
    ok, matched, in_pause, lead = judge(onsets, start, end, pauses)
    if ok:
        return ("repair" if fixes else "fine"), "", fixes
    shift = start - onsets[0]
    if not fixes and abs(shift) >= MIN_SHIFT_MS and abs(last_end + shift - total) <= 300:
        moved = [o + shift for o in onsets]
        ok2, m2, _, _ = judge(moved, start, end, pauses)
        # Moving the words must not make them fit the recitation's pauses any worse.
        if ok2 and moved[0] >= 0 and m2 >= matched:
            return "shift", f"all words {abs(shift)} ms {'late' if shift < 0 else 'early'}", {
                "shift": {"first_onset_ms": onsets[0], "shift_ms": shift, "why": f"both ends {abs(shift)} ms out; {m2}/{len(pauses)} pauses fit"}}
    reasons = []
    if abs(lead) > LEAD_TOLERANCE_MS:
        reasons.append(f"first word {lead:+d} ms from the voice")
    if onsets[-1] >= end + TAIL_TOLERANCE_MS:
        reasons.append(f"last word starts at {onsets[-1]} ms, voice ends {end} ms")
    if in_pause:
        reasons.append(f"{in_pause} words start inside a silence")
    return "drop", "; ".join(reasons), {}


def score(rows):
    """Each variant's error on ayahs whose last word QUL timed, the fit made on the other half:
    ({name: stats}, the fit over all of them)."""
    good = [r for r in rows if not r.get("silent") and r["window"] and r["segments"][-1][1] - r["segments"][-1][0] >= 200
            and abs(r["segments"][0][0] - r["voice_start_ms"]) <= LEAD_TOLERANCE_MS]
    train, test = good[::2], good[1::2]
    model = fit(train)
    stats = {}
    for name in VARIANTS:
        errors = sorted(abs(e - r["segments"][-1][0]) for r in test for e in [estimate(r["window"], name, model)] if e is not None)
        if not errors:
            stats[name] = {"found": 0, "p50": 10 ** 6, "p90": 10 ** 6, "useful": 0}
            continue
        within = sum(1 for e in errors if e <= 150)
        stats[name] = {
            "found": round(len(errors) / len(test), 3), "p50": errors[len(errors) // 2], "p90": errors[len(errors) * 9 // 10],
            "within150": round(within / len(errors), 3), "within300": round(sum(1 for e in errors if e <= 300) / len(errors), 3),
            # Right to within 150 ms, as a share of all the ayahs (not finding counts against it).
            "useful": round(within / len(test), 3), "of": len(test),
        }
    return stats, fit(good)


def gate(stats):
    """The best way of placing the last word, if it clearly beats leaving QUL's sliver: half its
    errors under half the sliver's median, nine in ten under the sliver's median."""
    left = stats["sliver"]["p50"]
    passing = {n: s for n, s in stats.items() if n != "sliver" and s["p50"] <= left / 2 and s["p90"] <= left}
    return max(passing, key=lambda n: passing[n]["useful"]) if passing else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reciter", choices=sorted(FOLDERS))
    parser.add_argument("--limit", type=int, default=0, help="only the first N ayahs (a quick try)")
    parser.add_argument("--out", default="timing-audit")
    parser.add_argument("--asset", action="store_true", help="judge the shipped asset, not QUL's timings")
    args = parser.parse_args()

    if args.asset:
        timings = json.loads((ASSETS / f"word_timings_{args.reciter}.json").read_text(encoding="utf-8"))
    else:
        import make_timings
        timings, _ = make_timings.generate(args.reciter)
        if len(timings) < 6000 and not args.limit:
            sys.exit(f"only {len(timings)} ayahs from QUL; is it reachable?")
    text = json.loads((ASSETS / "quran_text.json").read_text(encoding="utf-8"))
    words = {k: [w["text"] for w in v["words"] if w["char_type"] != "end"] for k, v in text.items()}
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
        rows = list(pool.map(measure, keys, [timings[k]["segments"] for k in keys], [words.get(k, []) for k in keys],
                             [files[k] for k in keys], chunksize=32))

    stats, model = score(rows)
    print(f"last word: voice end - onset = {model[0]:.0f} ms + {model[1]:.0f} ms per letter (fitted)")
    print("\nFinding the last word's start, scored on ayahs whose last word QUL timed (error in ms):")
    for name, s in sorted(stats.items(), key=lambda kv: -kv[1]["useful"]):
        print(f"  {name:>12}: {s}")
    chosen = gate(stats)
    print(f"chosen: {chosen or 'none clearly beats leaving the sliver'}")

    fixes = {"shift": {}, "first_onset": {}, "last_onset": {}, "drop": {}}
    kinds = {"fine": 0, "repair": 0, "shift": 0, "drop": 0}
    table = []
    for row in rows:
        kind, why, fix = verdict(row, estimate(row["window"], chosen, model) if chosen else None)
        kinds[kind] += 1
        key = row["ayah"]
        for name, value in fix.items():
            fixes[name][key] = value
        if kind == "drop":
            fixes["drop"][key] = why
        segs = row["segments"]
        table.append({"ayah": key, "words": len(segs), "duration_ms": row["duration_ms"], "voice_start_ms": row.get("voice_start_ms"),
                      "voice_end_ms": row.get("voice_end_ms"), "faint_end_ms": row.get("faint_end_ms"), "first_onset_ms": segs[0][0],
                      "last_onset_ms": segs[-1][0], "last_end_ms": segs[-1][1], "verdict": kind, "reason": why,
                      "fixes": json.dumps(fix) if fix else ""})

    out.mkdir(parents=True, exist_ok=True)
    with open(out / f"{args.reciter}.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=list(table[0]))
        writer.writeheader()
        writer.writerows(table)

    print(f"\n{args.reciter}: {len(rows)} ayahs: {kinds['fine']} fine, {kinds['repair']} repaired "
          f"({len(fixes['last_onset'])} last words, {len(fixes['first_onset'])} first words), "
          f"{kinds['shift']} shifted, {kinds['drop']} dropped")
    # Why ayahs are dropped, by the reason's first words, with examples to look up.
    groups = {}
    for key, why in fixes["drop"].items():
        groups.setdefault(" ".join(w for w in why.split()[:4] if not w.lstrip("+-").isdigit()), []).append(f"{key} ({why})")
    print("\nDropped, by reason:")
    for reason, items in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        print(f"  {len(items):5d}  {reason}:  " + "; ".join(items[:4]))
    if chosen is None:
        sys.exit("No way of placing the last word clearly beats leaving it; nothing written.")
    if not args.limit:
        FIXES.mkdir(exist_ok=True)
        (FIXES / f"{args.reciter}.json").write_text(json.dumps({
            "reciter": args.reciter,
            "about": "Written by audit_timings.py from everyayah's recordings; applied by make_timings.py and apply_timing_fixes.py "
                     "while the asset still holds each 'from' value. last_onset: QUL timed the last word in the trailing silence; "
                     "'to' is where the recording shows it starting. first_onset: QUL started the first word at 0 on a recording "
                     "that opens with silence. shift: every segment out by a constant amount. drop: the timings don't fit.",
            "last_word_estimator": {"method": chosen, "fit_ms": [round(model[0]), round(model[1])], **stats[chosen]},
            **fixes,
        }, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")

    print("\nShifted:")
    for k, v in sorted(fixes["shift"].items(), key=lambda kv: -abs(kv[1]["shift_ms"]))[:20]:
        print(f"  {k:>8}  {v['shift_ms']:+6d} ms   {v['why']}")
    print("\nLast words moved (largest first):")
    for k, v in sorted(fixes["last_onset"].items(), key=lambda kv: kv[1]["to"] - kv[1]["from"])[:20]:
        print(f"  {k:>8}  {v['from']} -> {v['to']} ms")
    print("\nDropped:")
    for k, why in list(fixes["drop"].items())[:80]:
        print(f"  {k:>8}  {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
