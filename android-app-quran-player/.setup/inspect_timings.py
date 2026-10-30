#!/usr/bin/env python3
"""Shows, for a few ayahs, QUL's raw segments, the asset's segments and where the recording is voiced.

    python .setup/inspect_timings.py maher 2:13 2:20 4:175

For looking into what audit_timings.py flags: whether a fault is QUL's data, the generator's
cleaning, or the audit's measurement. Needs network access to qul.tarteel.ai and everyayah.com,
ffmpeg and numpy (the timing-audit workflow runs it).
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_timings as audit  # noqa: E402
import make_timings as make  # noqa: E402


def runs(mask):
    """(start ms, end ms) of each voiced stretch."""
    out, i = [], 0
    while i < len(mask):
        if mask[i]:
            j = i
            while j < len(mask) and mask[j]:
                j += 1
            out.append((i * audit.FRAME_MS, j * audit.FRAME_MS))
            i = j
        else:
            i += 1
    return out


def main():
    reciter, keys = sys.argv[1], sys.argv[2:]
    timings = json.loads((audit.ASSETS / f"word_timings_{reciter}.json").read_text(encoding="utf-8"))
    # The same cache as the audit run by timing-audit.yml (--out ../timing-audit).
    out = audit.HERE.parent.parent / "timing-audit" / "audio" / reciter
    for key in keys:
        s, a = map(int, key.split(":"))
        raw = make.fetch(make.RECITERS[reciter], s, a).get(key)
        name = f"{s:03d}{a:03d}.mp3"
        path = audit.fetch(f"https://everyayah.com/data/{audit.FOLDERS[reciter]}/{name}", out / name)
        db = audit.envelope(path)
        peak = float(np.percentile(db, 95))
        print(f"\n== {key}: {make.WORDS.get(key)} words, recording {len(db) * audit.FRAME_MS} ms, floor {np.percentile(db, 10):.1f} dB, peak {peak:.1f} dB")
        print(f"   QUL raw:  {raw}")
        print(f"   asset:    {timings.get(key, {}).get('segments')}")
        print(f"   voiced:   {runs(audit.speech_mask(db))}")
        print(f"   faint:    {runs(audit.speech_mask(db, share=0.15))}")
        tail = db[-int(3000 / audit.FRAME_MS):] - peak
        print(f"   last 3 s, dB under peak per 100 ms: {[round(float(np.max(tail[i:i + 5])), 1) for i in range(0, len(tail), 5)]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
