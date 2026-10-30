#!/usr/bin/env python3
"""Applies the audit's verdicts (.setup/timing_fixes/<reciter>.json) to a word-timing asset.

    python .setup/apply_timing_fixes.py maher          # fix the asset in place
    python .setup/apply_timing_fixes.py --check maher  # fail if the asset still needs fixing

audit_timings.py compares QUL's timings with everyayah's recording, ayah by ayah, and writes:

- shift: the whole ayah is out by a constant amount (both ends agree); move every segment.
- first_onset: QUL starts the first word over the recording's opening silence (at 0, or as a 70 ms
  blip there) or a little after the voice; move it to where the voice starts.
- last_onset: QUL put the last word in the final ~50 ms of the file, after the recitation ended;
  move it to where the recording shows the last word beginning (and end the word before there).
- drop: the timings don't fit the recording; the ayah shows without highlighting.

Each repair applies only while the asset still holds the value the audit measured ("from"), so
running this twice, or on a freshly regenerated asset, never applies anything twice.
make_timings.py applies the same file when it regenerates an asset, through apply().
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
FIXES = HERE / "timing_fixes"


def apply(timings, fixes):
    """Fixes [timings] ({"s:a": {"segments": [[start, end], ...]}}) in place; returns counts by kind."""
    counts = {"shifted": 0, "first onsets": 0, "last onsets": 0, "dropped": 0}
    for key, fix in fixes.get("shift", {}).items():
        entry = timings.get(key)
        if not entry or entry["segments"][0][0] != fix["first_onset_ms"]:
            continue
        d = fix["shift_ms"]
        entry["segments"] = [[max(0, s + d), max(1, e + d)] for s, e in entry["segments"]]
        counts["shifted"] += 1
    for key, fix in fixes.get("first_onset", {}).items():
        entry = timings.get(key)
        segs = entry and entry["segments"]
        room = segs[1][0] if segs and len(segs) > 1 else (segs[0][1] if segs else 0)
        if not segs or segs[0][0] != fix["from"] or not fix["to"] < room:
            continue
        # A 70 ms blip at 0 moves with its onset; a segment that already ends later keeps its end.
        segs[0] = [fix["to"], max(segs[0][1], min(fix["to"] + 100, room))]
        counts["first onsets"] += 1
    for key, fix in fixes.get("last_onset", {}).items():
        entry = timings.get(key)
        segs = entry and entry["segments"]
        if not segs or len(segs) < 2 or segs[-1][0] != fix["from"] or not segs[-2][0] < fix["to"] < segs[-1][1]:
            continue
        segs[-1][0] = fix["to"]
        segs[-2][1] = min(segs[-2][1], fix["to"])
        counts["last onsets"] += 1
    for key in fixes.get("drop", {}):
        if timings.pop(key, None) is not None:
            counts["dropped"] += 1
    return counts


def main():
    check = "--check" in sys.argv
    names = [a for a in sys.argv[1:] if not a.startswith("--")] or sorted(p.stem for p in FIXES.glob("*.json"))
    stale = []
    for name in names:
        fixes_path = FIXES / f"{name}.json"
        if not fixes_path.exists():
            continue
        fixes = json.loads(fixes_path.read_text(encoding="utf-8"))
        asset = ASSETS / f"word_timings_{name}.json"
        timings = json.loads(asset.read_text(encoding="utf-8"))
        counts = apply(timings, fixes)
        done = ", ".join(f"{k} {v}" for k, v in counts.items())
        if check:
            if any(counts.values()):
                stale.append(f"{name}: {done}")
            continue
        asset.write_text(json.dumps(timings, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print(f"{name}: {done}; {len(timings)} ayahs keep timings")
    if stale:
        sys.exit("Timing fixes not applied: " + "; ".join(stale) + ". Run .setup/apply_timing_fixes.py")


if __name__ == "__main__":
    main()
