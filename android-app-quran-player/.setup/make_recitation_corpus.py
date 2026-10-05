"""
Dev-time: build `app/src/androidTest/assets/recitation` -- the audio the instrumented recitation
tests play aloud at the microphone, plus the manifest that names what each clip is.

Two kinds of clip:

* **Correct recitations**, straight from everyayah, decoded to the 16kHz mono PCM the tests want.
  Al-Fatihah and Al-Ikhlas for the default reciter; Al-Ikhlas again for each other reciter, so
  `FullSurahRecitationTest` can put more than one voice in front of the coach.
* **One deliberate mistake**, spliced from the same reciter's own recordings using the validated
  word timings: Al-Fatihah 1:2 with رَبِّ replaced by مَـٰلِكِ, which is 1:4's first word. A real
  memorisation slip sounds like this -- the right voice, the right pace, one wrong word -- and
  synthesising it is the only way to test the correction path without asking someone to misrecite
  on cue.

Needs `soundfile` (`pip install soundfile`); no ffmpeg.

    python .setup/make_recitation_corpus.py
"""
import json
import os
import sys
import urllib.request
from fractions import Fraction

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DEST = os.path.join(ROOT, "app", "src", "androidTest", "assets", "recitation")
EVERYAYAH = "https://everyayah.com/data/{folder}/{surah:03d}{ayah:03d}.mp3"
RATE = 16_000

# Must match `data/Reciter.kt`: enum name -> (everyayah folder, timings asset).
RECITERS = {
    "MAHER_AL_MUAIQLY": ("MaherAlMuaiqly128kbps", "word_timings_maher.json"),
    "ABDUL_BASIT": ("Abdul_Basit_Murattal_192kbps", "word_timings_abdulbasit_murattal.json"),
    "AL_HUSARY": ("Husary_128kbps", "word_timings_husary.json"),
    "AS_SUDAIS": ("Abdurrahmaan_As-Sudais_192kbps", "word_timings_sudais.json"),
    "ASH_SHURAYM": ("Saood_ash-Shuraym_128kbps", "word_timings_shuraym.json"),
}

DEFAULT_RECITER = "MAHER_AL_MUAIQLY"
AL_FATIHAH = [(1, a) for a in range(1, 8)]
AL_IKHLAS = [(112, a) for a in range(1, 5)]
AYAT_AL_KURSI = [(2, 255)]

# The default reciter carries the whole existing corpus; the others only need a short surah,
# because what a second voice tests is the coach's tolerance, not its coverage.
PLAN = {
    DEFAULT_RECITER: AL_FATIHAH + AL_IKHLAS + AYAT_AL_KURSI,
    "AS_SUDAIS": AL_IKHLAS,
    "ASH_SHURAYM": AL_IKHLAS,
}

# (reciter, surah, ayah, word index to replace) <- (surah, ayah, word index to take instead)
SPLICES = [
    {
        "reciter": DEFAULT_RECITER,
        "surah": 1,
        "ayah": 2,
        "word_index": 2,           # رَبِّ
        "from": (1, 4, 0),         # مَـٰلِكِ
        "file": "001002_wrong_word2.wav",
        "said_instead": "مَـٰلِكِ",
    },
]

# Butt-joining two recordings clicks; 8ms of ramp either side of a cut does not, and is far
# shorter than any phoneme.
FADE_MS = 8

cache = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".corpus-cache")
os.makedirs(cache, exist_ok=True)
os.makedirs(DEST, exist_ok=True)


def mp3(folder, surah, ayah):
    """everyayah's MP3 for one ayah, downloaded once."""
    path = os.path.join(cache, f"{folder}_{surah:03d}{ayah:03d}.mp3")
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        url = EVERYAYAH.format(folder=folder, surah=surah, ayah=ayah)
        req = urllib.request.Request(url, headers={"User-Agent": "learned-ayahs-corpus/1.0"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path, "wb") as fh:
            fh.write(r.read())
        print(f"  downloaded {os.path.basename(path)}")
    return path


def samples(folder, surah, ayah):
    """
    Mono 16kHz float samples.

    Polyphase resampling, not linear interpolation: everyayah is 44.1kHz and dropping to 16kHz
    without the anti-alias filter folds everything above 8kHz back down into the speech band,
    which is exactly the range a recognizer listens to.
    """
    data, rate = sf.read(mp3(folder, surah, ayah), always_2d=True)
    mono = data.mean(axis=1)
    if rate == RATE:
        return mono.astype(np.float32)
    ratio = Fraction(RATE, int(rate)).limit_denominator(1000)
    return resample_poly(mono, ratio.numerator, ratio.denominator).astype(np.float32)


def write(name, produce, force):
    """
    Writes the clip, or leaves an existing one alone and reports its duration.

    [produce] is a callable so that skipping a clip also skips downloading and decoding it.
    """
    path = os.path.join(DEST, name)
    if os.path.exists(path) and os.path.getsize(path) > 0 and not force:
        return sf.info(path).duration
    audio = produce()
    sf.write(path, audio, RATE, subtype="PCM_16")
    return len(audio) / RATE


def ramp(audio, samples_count):
    """Fades the first and last [samples_count] samples of a slice, in place."""
    n = min(samples_count, len(audio) // 2)
    if n <= 0:
        return audio
    envelope = np.linspace(0.0, 1.0, n, dtype=np.float32)
    audio[:n] *= envelope
    audio[-n:] *= envelope[::-1]
    return audio


def timings(asset, key):
    with open(os.path.join(ASSETS, asset), encoding="utf-8") as fh:
        return json.load(fh).get(key, {}).get("segments")


def clip_name(reciter, surah, ayah):
    return (
        f"{surah:03d}{ayah:03d}.wav"
        if reciter == DEFAULT_RECITER
        else f"{reciter.lower()}_{surah:03d}{ayah:03d}.wav"
    )


def corpus_samples(reciter, surah, ayah):
    """
    The clip already in the corpus, or a fresh decode if there isn't one.

    A spliced mistake has to be built from the *same* audio as the correct clip beside it, so
    that the only difference between the two is the one word -- otherwise a test comparing them
    is also comparing two decoders.
    """
    path = os.path.join(DEST, clip_name(reciter, surah, ayah))
    if os.path.exists(path) and os.path.getsize(path) > 0:
        data, rate = sf.read(path, always_2d=True)
        if rate != RATE:
            raise SystemExit(f"{path} is {rate}Hz, expected {RATE}")
        return data.mean(axis=1).astype(np.float32)
    folder, _ = RECITERS[reciter]
    return samples(folder, surah, ayah)


def word_span(segments, index, total):
    """
    Samples belonging to one word: **its own onset up to the next word's onset.**

    QUL reports the onset accurately and the offset often not at all -- Maher's لِلَّهِ and رَبِّ in
    1:2 both come back as exactly 100ms, which no reciter has ever managed. Using the next word's
    start instead recovers a span that sums to the length of the recording, and cutting on it
    replaces a whole word rather than its first tenth.
    """
    start = int(segments[index][0] * RATE / 1000)
    if index + 1 < len(segments):
        end = int(segments[index + 1][0] * RATE / 1000)
    else:
        end = total
    return start, min(max(end, start), total)


def splice(spec):
    """One ayah with a single word replaced by a word from another ayah, same reciter."""
    _, asset = RECITERS[spec["reciter"]]
    surah, ayah, index = spec["surah"], spec["ayah"], spec["word_index"]
    host_segments = timings(asset, f"{surah}:{ayah}")
    src_surah, src_ayah, src_index = spec["from"]
    src_segments = timings(asset, f"{src_surah}:{src_ayah}")
    if not host_segments or not src_segments:
        raise SystemExit(f"no timings for {surah}:{ayah} or {src_surah}:{src_ayah} in {asset}")

    host = corpus_samples(spec["reciter"], surah, ayah)
    source = corpus_samples(spec["reciter"], src_surah, src_ayah)

    start, end = word_span(host_segments, index, len(host))
    src_start, src_end = word_span(src_segments, src_index, len(source))
    fade = int(FADE_MS * RATE / 1000)
    before = ramp(host[:start].copy(), fade)
    wrong = ramp(source[src_start:src_end].copy(), fade)
    after = ramp(host[end:].copy(), fade)
    return np.concatenate([before, wrong, after])


def main():
    # Clips already in the tree are left alone unless asked for: they are binary, they are inputs
    # to an acoustic test whose results move on their own, and rewriting 2.5MB of identical-
    # sounding audio to change a resampler makes a change impossible to review.
    force = "--force" in sys.argv[1:]
    manifest = []
    for reciter, ayahs in PLAN.items():
        folder, _ = RECITERS[reciter]
        print(f"[{reciter}] {len(ayahs)} ayahs from {folder}")
        for surah, ayah in ayahs:
            # The default reciter keeps the historic bare filenames the corpus already used.
            name = clip_name(reciter, surah, ayah)
            seconds = write(name, lambda: samples(folder, surah, ayah), force)
            manifest.append(
                {
                    "reciter": reciter,
                    "surah": surah,
                    "ayah": ayah,
                    "file": name,
                    "seconds": round(seconds, 2),
                }
            )

    for spec in SPLICES:
        seconds = write(spec["file"], lambda: splice(spec), force)
        manifest.append(
            {
                "reciter": spec["reciter"],
                "surah": spec["surah"],
                "ayah": spec["ayah"],
                "file": spec["file"],
                "seconds": round(seconds, 2),
                "mistakeWordIndex": spec["word_index"],
                "saidInstead": spec["said_instead"],
            }
        )
        src = spec["from"]
        # Deliberately no Arabic in the console output: a Windows terminal on the default code
        # page raises UnicodeEncodeError and takes the manifest write down with it.
        print(f"[splice] {spec['file']}: {spec['surah']}:{spec['ayah']} word "
              f"{spec['word_index']} replaced by {src[0]}:{src[1]} word {src[2]} "
              f"({seconds:.2f}s)")

    with open(os.path.join(DEST, "manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, ensure_ascii=False, indent=2)
    total = sum(os.path.getsize(os.path.join(DEST, e["file"])) for e in manifest)
    print(f"\nwrote {len(manifest)} clips, {total / 1024 / 1024:.1f} MB, to {DEST}")


if __name__ == "__main__":
    main()
