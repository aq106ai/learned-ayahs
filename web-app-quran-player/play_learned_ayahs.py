#!/usr/bin/env python3
"""Download and play learned ayahs from quran_library export (Maher Al Muaiqly, gapless)."""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import webbrowser
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def ensure_std_streams() -> None:
    """Under pythonw.exe (no console) sys.stdout/stderr are None; writing to them
    crashes request handlers mid-response. Point them at the null device so the
    server can run windowless (background launchers, quranplayer: protocol)."""
    devnull = None
    for name in ("stdout", "stderr"):
        if getattr(sys, name, None) is None:
            if devnull is None:
                devnull = open(os.devnull, "w", encoding="utf-8")
            setattr(sys, name, devnull)


class QuietHTTPRequestHandler(SimpleHTTPRequestHandler):
    """SimpleHTTPRequestHandler that never writes per-request access logs.

    The default handler logs every request to sys.stderr; under a windowless
    interpreter that write raises and kills the response. Silence it entirely."""

    def log_message(self, format, *args):  # noqa: A002 - match base signature
        pass

VERSE_COUNTS = [
    7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110,
    98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88,
    75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55, 78, 96, 29, 22, 24,
    13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42,
    29, 19, 36, 25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8,
    3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6,
]

RECITER_FOLDER = "MaherAlMuaiqly128kbps"
BASE_URL = f"https://everyayah.com/data/{RECITER_FOLDER}"
# Library exports are named like "quran_library 28 Jun 2026 07.20.05.json"; the newest one in
# the working directory is used unless --library names a file. With no export at all, the bundled
# sample library is used so the player can be tried out.
LIBRARY_GLOB = "quran_library*.json"
LIBRARY_STAMP_FORMAT = "%d %b %Y %H.%M.%S"
SAMPLE_LIBRARY_FILE = Path("sample_library.json")
AUDIO_DIR = Path("audio") / RECITER_FOLDER
PLAYLIST_FILE = Path("learned_ayahs_playlist.json")
PLAYER_FILE = Path("player.html")
PLAYER_TEMPLATE = Path("player_template.html")
QURAN_TEXT_FILE = Path("quran_text.json")
WORD_TIMINGS_FILE = Path("word_timings.json")
M3U_FILE = Path("learned_ayahs.m3u")
QURAN_CDN = "https://api.qurancdn.com/api/qdc/verses/by_key"
# Maher Al Muaiqly ayah-by-ayah segments (everyayah-style) via QUL public API.
QUL_RECITATION_ID = 13
QUL_AYAH_SEGMENTS_URL = f"https://qul.tarteel.ai/api/v1/audio/ayah_segments/{QUL_RECITATION_ID}"
QUL_SEGMENTS_PAGE_SIZE = 11

# Always included in the playlist even when absent from the library JSON export.
FULL_DEFAULT_SURAHS = [36, 55, 56, 61, 62, 67] + list(range(70, 115))
DEFAULT_PARTIAL_SURAHS: dict[int, tuple[int, int]] = {69: (1, 43)}


def surah_ayah_to_global(surah: int, ayah: int) -> int:
    return sum(VERSE_COUNTS[: surah - 1]) + ayah


def ayah_entry(surah: int, ayah: int, *, folder: str = "Default supplement") -> dict:
    filename = ayah_filename(surah, ayah)
    return {
        "global_id": surah_ayah_to_global(surah, ayah),
        "surah": surah,
        "ayah": ayah,
        "filename": filename,
        "url": f"{BASE_URL}/{filename}",
        "folder": folder,
    }


def default_supplement_ayahs() -> list[dict]:
    entries: list[dict] = []
    for surah in FULL_DEFAULT_SURAHS:
        for ayah in range(1, VERSE_COUNTS[surah - 1] + 1):
            entries.append(ayah_entry(surah, ayah))
    for surah, (start, end) in DEFAULT_PARTIAL_SURAHS.items():
        for ayah in range(start, end + 1):
            entries.append(ayah_entry(surah, ayah))
    return entries


def merge_default_ayahs(ayahs: list[dict]) -> list[dict]:
    by_gid = {entry["global_id"]: entry for entry in ayahs}
    for entry in default_supplement_ayahs():
        by_gid.setdefault(entry["global_id"], entry)
    return sorted(by_gid.values(), key=lambda entry: entry["global_id"])


def global_to_surah_ayah(global_id: int) -> tuple[int, int]:
    remaining = global_id
    for surah, count in enumerate(VERSE_COUNTS, 1):
        if remaining <= count:
            return surah, remaining
        remaining -= count
    raise ValueError(f"Invalid global ayah id: {global_id}")


def ayah_filename(surah: int, ayah: int) -> str:
    return f"{surah:03d}{ayah:03d}.mp3"


def library_timestamp(path: Path) -> float:
    """Export time from the filename's timestamp, falling back to the file's modified time."""
    stamp = path.stem[len("quran_library"):].strip()
    try:
        return datetime.strptime(stamp, LIBRARY_STAMP_FORMAT).timestamp()
    except ValueError:
        return path.stat().st_mtime


def find_default_library() -> Path:
    exports = sorted(Path().glob(LIBRARY_GLOB), key=library_timestamp, reverse=True)
    return exports[0] if exports else SAMPLE_LIBRARY_FILE


def load_learned_ayahs(library_path: Path) -> list[dict]:
    with library_path.open(encoding="utf-8") as f:
        data = json.load(f)

    folders = {
        folder["id"]: folder
        for folder in data["folders"]
        if not folder.get("is_deleted") and "Every Learned Ayah" in folder["title"]
    }
    items = [item for item in data["items"] if not item.get("is_deleted")]

    seen: set[int] = set()
    ayahs: list[dict] = []
    for folder in sorted(folders.values(), key=lambda f: f["title"]):
        folder_items = sorted(
            (item for item in items if item["folder_id"] == folder["id"]),
            key=lambda item: item.get("custom_order", 0),
        )
        for item in folder_items:
            global_id = item["id"]
            if global_id in seen:
                continue
            seen.add(global_id)
            surah, ayah = global_to_surah_ayah(global_id)
            filename = ayah_filename(surah, ayah)
            ayahs.append(
                {
                    "global_id": global_id,
                    "surah": surah,
                    "ayah": ayah,
                    "filename": filename,
                    "url": f"{BASE_URL}/{filename}",
                    "folder": folder["title"],
                }
            )

    ayahs.sort(key=lambda entry: entry["global_id"])
    return merge_default_ayahs(ayahs)


def download_file(url: str, dest: Path) -> tuple[str, bool, str]:
    if dest.exists() and dest.stat().st_size > 0:
        return dest.name, True, "cached"

    dest.parent.mkdir(parents=True, exist_ok=True)
    request = Request(url, headers={"User-Agent": "quran-player/1.0"})
    try:
        with urlopen(request, timeout=60) as response:
            dest.write_bytes(response.read())
        return dest.name, True, "downloaded"
    except (HTTPError, URLError, TimeoutError) as exc:
        if dest.exists():
            dest.unlink(missing_ok=True)
        return dest.name, False, str(exc)


def download_ayahs(ayahs: list[dict], workers: int = 8) -> tuple[int, int]:
    cached = downloaded = failed = 0
    failures: list[str] = []

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {
            pool.submit(download_file, entry["url"], AUDIO_DIR / entry["filename"]): entry
            for entry in ayahs
        }
        total = len(futures)
        done = 0
        for future in as_completed(futures):
            entry = futures[future]
            done += 1
            name, ok, status = future.result()
            if ok:
                if status == "cached":
                    cached += 1
                else:
                    downloaded += 1
            else:
                failed += 1
                failures.append(f"{entry['surah']}:{entry['ayah']} ({name}) -> {status}")
            if done % 25 == 0 or done == total:
                print(f"  progress: {done}/{total} (new={downloaded}, cached={cached}, failed={failed})")

    if failures:
        print("\nFailed downloads:")
        for line in failures[:20]:
            print(f"  - {line}")
        if len(failures) > 20:
            print(f"  ... and {len(failures) - 20} more")

    return downloaded, failed


def download_audhu_billah() -> None:
    filename = "audhubillah.mp3"
    # Reciter folder no longer hosts this file; everyayah keeps one shared copy at /data/.
    url = "https://everyayah.com/data/audhubillah.mp3"
    dest = AUDIO_DIR / filename
    name, ok, status = download_file(url, dest)
    if ok:
        print(f"  {name}: {status}")
    else:
        print(f"  Warning: could not fetch {name} ({status}) — player will try online fallback")


def download_bismillah() -> None:
    filename = "bismillah.mp3"
    url = "https://everyayah.com/data/bismillah.mp3"
    dest = AUDIO_DIR / filename
    name, ok, status = download_file(url, dest)
    if ok:
        print(f"  {name}: {status}")
    else:
        print(f"  Warning: could not fetch {name} ({status}) — player will try online fallback")


def download_intro_clips() -> None:
    print("Fetching intro clips (A'udhu Billah + Bismillah)…")
    download_audhu_billah()
    download_bismillah()


def build_playlist_payload(ayahs: list[dict], source: str) -> dict:
    return {
        "reciter": "Maher Al Muaiqly",
        "source": source,
        "collection": "Every Learned Ayah",
        "count": len(ayahs),
        "tracks": [
            {
                "index": index,
                "global_id": entry["global_id"],
                "surah": entry["surah"],
                "ayah": entry["ayah"],
                "label": f"Surah {entry['surah']} : Ayah {entry['ayah']}",
                "file": f"audio/{RECITER_FOLDER}/{entry['filename']}",
            }
            for index, entry in enumerate(ayahs, start=1)
        ],
    }


def write_playlist(ayahs: list[dict], payload: dict) -> None:
    PLAYLIST_FILE.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    with M3U_FILE.open("w", encoding="utf-8") as f:
        f.write("#EXTM3U\n")
        for entry in ayahs:
            path = AUDIO_DIR / entry["filename"]
            f.write(f"#EXTINF:-1,Surah {entry['surah']} Ayah {entry['ayah']}\n")
            f.write(str(path.resolve()).replace("\\", "/") + "\n")


def fetch_verse_words(surah: int, ayah: int, retries: int = 3) -> list[dict]:
    url = f"{QURAN_CDN}/{surah}:{ayah}?words=true&word_fields=text_uthmani"
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = Request(url, headers={"User-Agent": "quran-player/2.0"})
            with urlopen(request, timeout=25) as response:
                data = json.load(response)
            words = []
            for word in data.get("verse", {}).get("words", []):
                words.append(
                    {
                        "position": word.get("position"),
                        "text": word.get("text_uthmani") or word.get("text", ""),
                        "char_type": "end" if word.get("char_type_name") == "end" else "word",
                    }
                )
            return words
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
            last_error = exc
            time.sleep(0.4 * (attempt + 1))
    raise last_error or RuntimeError(f"Failed to fetch {surah}:{ayah}")


def write_quran_text(ayahs: list[dict], *, include_all_surah_ayahs: bool = False, workers: int = 4) -> bool:
    """Build quran_text.json for learned ayahs (and optionally every ayah in learned surahs)."""
    keys: set[tuple[int, int]] = {(a["surah"], a["ayah"]) for a in ayahs}
    if include_all_surah_ayahs:
        for surah in sorted({a["surah"] for a in ayahs}):
            for ayah in range(1, VERSE_COUNTS[surah - 1] + 1):
                keys.add((surah, ayah))

    payload: dict[str, dict] = {}
    if QURAN_TEXT_FILE.exists() and QURAN_TEXT_FILE.stat().st_size > 2:
        try:
            payload = json.loads(QURAN_TEXT_FILE.read_text(encoding="utf-8"))
            print(f"  resuming with {len(payload)} cached ayah texts", flush=True)
        except json.JSONDecodeError:
            payload = {}

    sorted_keys = [pair for pair in sorted(keys) if f"{pair[0]}:{pair[1]}" not in payload]
    if not sorted_keys:
        print(f"  quran text already complete ({len(payload)} ayahs)", flush=True)
        return False

    total = len(sorted_keys)
    done = 0
    print(f"  fetching {total} ayah texts ({workers} parallel workers)…", flush=True)

    def fetch_one(pair: tuple[int, int]) -> tuple[str, dict | None]:
        surah, ayah = pair
        key = f"{surah}:{ayah}"
        try:
            return key, {"words": fetch_verse_words(surah, ayah)}
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
            print(f"  text fetch failed {key}: {exc}", flush=True)
            return key, None

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch_one, pair): pair for pair in sorted_keys}
        for future in as_completed(futures):
            key, entry = future.result()
            if entry is not None:
                payload[key] = entry
            done += 1
            if done % 10 == 0 or done == total:
                print(f"  quran text: {done}/{total} ({len(payload)} saved)", flush=True)
            if done % 25 == 0:
                QURAN_TEXT_FILE.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")

    QURAN_TEXT_FILE.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return True


def fetch_ayah_segments(surah: int, from_ayah: int, retries: int = 3) -> dict[str, dict]:
    """Fetch up to 11 ayahs of word segments starting at from_ayah (QUL ayah-by-ayah API)."""
    url = f"{QUL_AYAH_SEGMENTS_URL}?surah={surah}&from={from_ayah}"
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = Request(url, headers={"User-Agent": "quran-player/2.0"})
            with urlopen(request, timeout=45) as response:
                data = json.load(response)
            return data.get("segments") or {}
        except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
            last_error = exc
            time.sleep(0.5 * (attempt + 1))
    raise last_error or RuntimeError(f"Failed to fetch segments {surah}:{from_ayah}")


def segments_to_timings(raw_segments: list) -> list[list[int]]:
    """Convert QUL [word_start, word_end, start_ms, end_ms] to [[start_ms, end_ms], ...]."""
    ordered = sorted(raw_segments, key=lambda seg: seg[0])
    return [[int(seg[2]), int(seg[3])] for seg in ordered]


def write_word_timings(ayahs: list[dict]) -> bool:
    """Build word_timings.json for playlist ayahs using QUL segment data."""
    needed: set[tuple[int, int]] = {(a["surah"], a["ayah"]) for a in ayahs}
    by_surah: dict[int, set[int]] = {}
    for surah, ayah in needed:
        by_surah.setdefault(surah, set()).add(ayah)

    payload: dict[str, dict] = {}
    if WORD_TIMINGS_FILE.exists() and WORD_TIMINGS_FILE.stat().st_size > 2:
        try:
            payload = json.loads(WORD_TIMINGS_FILE.read_text(encoding="utf-8"))
            print(f"  resuming with {len(payload)} cached word timings", flush=True)
        except json.JSONDecodeError:
            payload = {}

    missing_total = sum(1 for pair in needed if f"{pair[0]}:{pair[1]}" not in payload)
    if not missing_total:
        print(f"  word timings already complete ({len(payload)} ayahs)", flush=True)
        return False

    fetched = 0
    print(f"  fetching word timings for {missing_total} ayahs via QUL…", flush=True)

    for surah in sorted(by_surah):
        needed_ayahs = by_surah[surah]
        max_ayah = max(needed_ayahs)
        from_ayah = 1
        while from_ayah <= max_ayah:
            batch_missing = [
                ayah
                for ayah in range(from_ayah, from_ayah + QUL_SEGMENTS_PAGE_SIZE)
                if ayah in needed_ayahs and f"{surah}:{ayah}" not in payload
            ]
            if batch_missing:
                try:
                    segments_by_key = fetch_ayah_segments(surah, from_ayah)
                except (HTTPError, URLError, TimeoutError, json.JSONDecodeError) as exc:
                    print(f"  segment fetch failed surah {surah} from {from_ayah}: {exc}", flush=True)
                    from_ayah += QUL_SEGMENTS_PAGE_SIZE
                    continue

                for key, entry in segments_by_key.items():
                    try:
                        s, a = (int(part) for part in key.split(":"))
                    except ValueError:
                        continue
                    if (s, a) not in needed or key in payload:
                        continue
                    raw = entry.get("segments") or []
                    if not raw:
                        continue
                    payload[key] = {"segments": segments_to_timings(raw)}
                    fetched += 1

                if fetched and fetched % 50 == 0:
                    WORD_TIMINGS_FILE.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
                    print(f"  word timings: {fetched} new ({len(payload)} saved)", flush=True)

            from_ayah += QUL_SEGMENTS_PAGE_SIZE

    WORD_TIMINGS_FILE.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    still_missing = sum(1 for pair in needed if f"{pair[0]}:{pair[1]}" not in payload)
    print(
        f"  word timings done: {len(payload)} ayahs cached, {still_missing} still missing",
        flush=True,
    )
    return True


def write_player(payload: dict) -> None:
    playlist_json = json.dumps(payload)
    if not PLAYER_TEMPLATE.exists():
        raise FileNotFoundError(f"Missing template: {PLAYER_TEMPLATE}")
    template = PLAYER_TEMPLATE.read_text(encoding="utf-8")
    PLAYER_FILE.write_text(template.replace("__PLAYLIST__", playlist_json), encoding="utf-8")



def serve_player(root: Path, port: int = 8765) -> None:
    handler = partial(QuietHTTPRequestHandler, directory=str(root))
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f"http://127.0.0.1:{port}/player.html"
    print(f"Serving at {url}")
    print("Press Ctrl+C to stop.")
    webbrowser.open(url)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping server.")
        server.shutdown()


def main() -> int:
    ensure_std_streams()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--library",
        type=Path,
        default=None,
        help=f"library export to read (default: newest {LIBRARY_GLOB} here, else {SAMPLE_LIBRARY_FILE})",
    )
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--skip-download", action="store_true")
    parser.add_argument("--skip-text", action="store_true", help="skip fetching quran_text.json")
    parser.add_argument(
        "--skip-timings",
        action="store_true",
        help="skip fetching word_timings.json (exact word highlight sync)",
    )
    parser.add_argument(
        "--text-all-surah-ayahs",
        action="store_true",
        help="include every ayah text for surahs that have learned bookmarks (for Surah loop mode offline)",
    )
    parser.add_argument("--no-open", action="store_true")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument(
        "--file-only",
        action="store_true",
        help="open player.html via file:// instead of local HTTP server",
    )
    parser.add_argument(
        "--serve-only",
        action="store_true",
        help="start the local HTTP server only (skip library sync and regeneration)",
    )
    args = parser.parse_args()

    root = Path(__file__).resolve().parent

    if args.serve_only:
        if not PLAYER_FILE.exists():
            print(
                f"Missing {PLAYER_FILE.name}. Run once without --serve-only to generate it.",
                file=sys.stderr,
            )
            return 1
        if args.file_only:
            print("--serve-only cannot be combined with --file-only", file=sys.stderr)
            return 1
        if not args.no_open:
            serve_player(root, port=args.port)
        else:
            handler = partial(QuietHTTPRequestHandler, directory=str(root))
            server = ThreadingHTTPServer(("127.0.0.1", args.port), handler)
            print(f"Serving at http://127.0.0.1:{args.port}/player.html")
            print("Press Ctrl+C to stop.")
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                print("\nStopping server.")
                server.shutdown()
        return 0

    library = args.library or find_default_library()
    if not library.exists():
        print(f"Library file not found: {library}", file=sys.stderr)
        return 1
    print(f"Reading library: {library}")

    ayahs = load_learned_ayahs(library)
    print(f"Loaded {len(ayahs)} unique learned ayahs in Quran order")
    print(f"  first: Surah {ayahs[0]['surah']}:{ayahs[0]['ayah']}")
    print(f"  last:  Surah {ayahs[-1]['surah']}:{ayahs[-1]['ayah']}")

    payload = build_playlist_payload(ayahs, library.name)
    write_playlist(ayahs, payload)
    write_player(payload)
    print(f"Wrote {PLAYLIST_FILE} and {PLAYER_FILE}")

    if not args.skip_text:
        missing_text = write_quran_text(ayahs, include_all_surah_ayahs=args.text_all_surah_ayahs, workers=args.workers)
        if missing_text:
            print(f"Updated {QURAN_TEXT_FILE}")
        else:
            print(f"{QURAN_TEXT_FILE} unchanged (already complete)")

    if not args.skip_timings:
        missing_timings = write_word_timings(ayahs)
        if missing_timings:
            print(f"Updated {WORD_TIMINGS_FILE}")
        else:
            print(f"{WORD_TIMINGS_FILE} unchanged (already complete)")

    if not args.skip_download:
        print(f"Downloading to {AUDIO_DIR} …")
        downloaded, failed = download_ayahs(ayahs, workers=args.workers)
        print(f"Done. new={downloaded}, failed={failed}")
        print("Fetching intro clips…")
        download_intro_clips()
        if failed:
            return 2
    elif not (AUDIO_DIR / "audhubillah.mp3").exists() or not (AUDIO_DIR / "bismillah.mp3").exists():
        print("Fetching missing intro clips…")
        download_intro_clips()

    if not args.no_open:
        if args.file_only:
            player_url = PLAYER_FILE.resolve().as_uri()
            print(f"Opening player: {player_url}")
            webbrowser.open(player_url)
        else:
            serve_player(root, port=args.port)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
