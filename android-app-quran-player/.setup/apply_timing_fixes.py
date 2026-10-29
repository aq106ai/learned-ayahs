#!/usr/bin/env python3
"""Applies the audit's verdicts (.setup/timing_fixes/<reciter>.json) to a word-timing asset.

    python .setup/apply_timing_fixes.py maher          # fix the asset in place
    python .setup/apply_timing_fixes.py --check maher  # fail if the asset still needs fixing

audit_timings.py measures where each ayah's voice begins and ends in everyayah's recording and
writes, per ayah, either a constant shift that lines the timings up with it or a drop. A shift is
only applied while the ayah's first onset is still the one the audit measured, so running this
twice, or on a freshly regenerated asset, never shifts anything twice. make_timings.py applies the
same file when it regenerates an asset, through apply().
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parent / "app" / "src" / "main" / "assets"
FIXES = HERE / "timing_fixes"


def apply(timings, fixes):
    """Fixes [timings] ({"s:a": {"segments": [[start, end], ...]}}) in place; returns (shifted, dropped)."""
    shifted, dropped = 0, 0
    for key, fix in fixes.get("shift", {}).items():
        entry = timings.get(key)
        if not entry or entry["segments"][0][0] != fix["first_onset_ms"]:
            continue
        d = fix["shift_ms"]
        entry["segments"] = [[max(0, s + d), max(1, e + d)] for s, e in entry["segments"]]
        shifted += 1
    for key in fixes.get("drop", {}):
        if timings.pop(key, None) is not None:
            dropped += 1
    return shifted, dropped


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
        shifted, dropped = apply(timings, fixes)
        if check:
            if shifted or dropped:
                stale.append(f"{name}: {shifted} to shift, {dropped} to drop")
            continue
        asset.write_text(json.dumps(timings, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        print(f"{name}: shifted {shifted}, dropped {dropped}; {len(timings)} ayahs keep timings")
    if stale:
        sys.exit("Timing fixes not applied: " + "; ".join(stale) + ". Run .setup/apply_timing_fixes.py")


if __name__ == "__main__":
    main()
