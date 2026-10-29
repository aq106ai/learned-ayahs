#!/usr/bin/env python3
"""Finds and prepares the nature photos "Share as video" can use as backgrounds.

    python backgrounds/fetch_nature_photos.py candidates   # search, write contact sheets to review
    python backgrounds/fetch_nature_photos.py finalize     # fetch the photos listed in selected.json

Every photo comes from Wikimedia Commons and must be released under CC0 (no rights reserved), read
from the file's own licence metadata, so the app and the videos people share need no permission and
no attribution, though the app can credit the photographer on the video. Candidates are found by searching
each subject within Commons' "CC-zero" category; anything whose title, description or categories
mention people, animals, buildings or places of worship is dropped, because the videos carry the
Qur'an (see the content norms in android-app-quran-player/CLAUDE.md).

That filter is a first pass, not a guarantee: a person reviews the contact sheets in candidates/
and lists the photos to keep in selected.json. `finalize` then downloads only those, as JPEGs
2048 px on the long side, plus a 216×384 thumbnail each, and writes nature/photos.json, its copy
in the app's assets (nature_photos.json), and nature/CREDITS.md.

Reviewed by eye on the first run: rejected for people (green_hills-0), birds (coast-0), possible
figures (dunes-0), buildings, cars, cranes, pylons or a city (storm-0,1,3; river_valley-5,7;
ocean-3; sunrise-0,7; snow_peaks-3,4; mountain_lake-3,7), and 500px watermarks (coast-2,3).

Needs network access to commons.wikimedia.org and upload.wikimedia.org, and Pillow.
"""
import io
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

HERE = Path(__file__).resolve().parent
CANDIDATES = HERE / "candidates"
NATURE = HERE / "nature"
# The app reads the list from its assets; the photos stay here and are downloaded when used.
APP_ASSET = HERE.parent / "android-app-quran-player" / "app" / "src" / "main" / "assets" / "nature_photos.json"
SELECTED = HERE / "selected.json"
API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "LearnedAyahs/1.0 (https://github.com/aq106ai/learned-ayahs; background photo curation)"

# Subject key -> (title shown in the app, Commons search terms, ayah themes it suits).
SUBJECTS = {
    "forest": ("Forest", "forest trees sunlight", ["growth", "paradise", "signs"]),
    "waterfall": ("Waterfalls", "waterfall", ["rivers", "paradise", "rain"]),
    "mountain_lake": ("Mountain lakes", "mountain lake reflection", ["patience", "remembrance", "mountains"]),
    "snow_peaks": ("Snowy peaks", "snow mountains peak", ["mountains", "majesty", "trust"]),
    "ocean": ("Ocean", "ocean waves sea", ["sea", "flood", "signs"]),
    "coast": ("Coast", "coast cliffs sea sunset", ["sea", "dawn", "gratitude"]),
    "dunes": ("Dunes", "sand dunes desert", ["desert", "prophets", "ruins", "moses"]),
    "meadow": ("Meadows", "meadow flowers field", ["growth", "gratitude", "mercy", "provision"]),
    "river_valley": ("Valleys", "river valley", ["rivers", "provision", "earth"]),
    "autumn": ("Autumn", "autumn forest leaves", ["death", "resurrection", "signs"]),
    "sunrise": ("Sunrise", "sunrise clouds sky", ["dawn", "resurrection", "glad_tidings", "light"]),
    "sunset": ("Sunset", "sunset sky clouds", ["death", "sun", "family"]),
    "night_sky": ("Night sky", "milky way night sky stars", ["night", "stars", "cosmos", "supplication"]),
    "storm": ("Clouds", "storm clouds thunderstorm", ["storm", "punishment", "judgment", "wind"]),
    "canyon": ("Canyons", "canyon rock formation", ["earth", "ruins", "creation_of_man", "majesty"]),
    "green_hills": ("Hills", "green hills landscape", ["earth", "growth", "harvest", "trust"]),
}

# Anything that names one of these is not a pure landscape.
EXCLUDE = re.compile(
    r"\b(people|persons?|man|men|woman|women|child(ren)?|boys?|girls?|portrait|tourists?|hikers?|"
    r"selfie|crowd|wedding|nude|animals?|birds?|dogs?|cats?|horses?|cows?|cattle|sheep|goats?|"
    r"camels?|deer|fish|insects?|butterfl\w*|bees?|wildlife|zoo|church|cathedral|chapel|temple|"
    r"mosque|shrine|statue|sculpture|cross|crucifix|buddha|pagoda|monastery|graffiti|cars?|"
    r"vehicles?|boats?|ships?|houses?|buildings?|city|cityscape|street|bridge|tower|castle|"
    r"logo|text|sign|advertis\w*|flag)\b",
    re.I,
)
PER_SUBJECT = 8


def api(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json"})
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except Exception:
            time.sleep(2 ** attempt)
    raise RuntimeError(f"could not fetch {url}")


def strip_html(text):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", text or "")).strip()


def photographer(text):
    """The Artist field, cleaned: Commons repeats "Unknown author" for an unnamed one."""
    name = strip_html(text)
    return "Unknown" if not name or name.lower().startswith("unknown author") else name


def search(terms):
    """CC0 photos for the search terms, best match first, with their licence metadata."""
    data = api({
        "action": "query",
        "generator": "search",
        "gsrsearch": f'{terms} incategory:"CC-zero" filetype:bitmap filew:>1600',
        "gsrnamespace": 6,
        "gsrlimit": 50,
        "prop": "imageinfo|categories",
        "iiprop": "url|size|extmetadata|mime",
        "iiurlwidth": 2048,
        "cllimit": "max",
    })
    pages = sorted(data.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0))
    found = []
    for page in pages:
        info = (page.get("imageinfo") or [{}])[0]
        meta = info.get("extmetadata", {})
        licence = meta.get("LicenseShortName", {}).get("value", "")
        if licence.strip().upper() not in ("CC0", "CC0 1.0", "CC-ZERO"):
            continue
        if info.get("mime") != "image/jpeg" or info.get("width", 0) < 1600:
            continue
        description = strip_html(meta.get("ImageDescription", {}).get("value", ""))
        categories = " ".join(c["title"] for c in page.get("categories", []))
        words = f"{page['title']} {description} {categories} {strip_html(meta.get('Categories', {}).get('value', ''))}"
        if EXCLUDE.search(words):
            continue
        found.append({
            "file": page["title"],
            "page": info.get("descriptionurl"),
            "thumb": info.get("thumburl"),
            "width": info.get("width"),
            "height": info.get("height"),
            "author": photographer(meta.get("Artist", {}).get("value", "")),
            "licence": licence.strip(),
            "description": description[:200],
        })
    return found


def candidates():
    CANDIDATES.mkdir(exist_ok=True)
    listing = {}
    for key, (title, terms, _) in SUBJECTS.items():
        picks = search(terms)[:PER_SUBJECT]
        listing[key] = picks
        sheet = Image.new("RGB", (4 * 300, 2 * 230), "black")
        draw = ImageDraw.Draw(sheet)
        for i, pick in enumerate(picks):
            image = Image.open(io.BytesIO(fetch(pick["thumb"]))).convert("RGB")
            image = ImageOps.fit(image, (300, 200))
            x, y = (i % 4) * 300, (i // 4) * 230
            sheet.paste(image, (x, y))
            draw.text((x + 4, y + 204), f"{key}-{i}", fill="white")
        sheet.save(CANDIDATES / f"{key}.jpg", quality=85)
        print(f"{key}: {len(picks)} candidates")
    (CANDIDATES / "candidates.json").write_text(json.dumps(listing, indent=1, ensure_ascii=False), encoding="utf-8")


def finalize():
    listing = json.loads((CANDIDATES / "candidates.json").read_text(encoding="utf-8"))
    selected = json.loads(SELECTED.read_text(encoding="utf-8"))
    NATURE.mkdir(exist_ok=True)
    for old in NATURE.glob("*.jpg"):
        old.unlink()
    photos, credits = [], []
    for key, indexes in selected.items():
        title, _, themes = SUBJECTS[key]
        for n, index in enumerate(indexes):
            pick = dict(listing[key][index])
            pick["author"] = photographer(pick["author"])
            image = Image.open(io.BytesIO(fetch(pick["thumb"]))).convert("RGB")
            image.thumbnail((2048, 2048))
            name = f"{key}_{n + 1}"
            image.save(NATURE / f"{name}.jpg", quality=80, optimize=True, progressive=True)
            ImageOps.fit(image, (216, 384)).save(NATURE / f"{name}_thumb.jpg", quality=80)
            photos.append({
                "id": name, "subject": key, "title": title, "themes": themes,
                "width": image.width, "height": image.height,
                "author": pick["author"], "licence": pick["licence"], "source": pick["page"],
            })
            credits.append(f"| `{name}.jpg` | {title} | {pick['author']} | {pick['licence']} | [{pick['file']}]({pick['page']}) |")
    manifest = json.dumps({"version": 1, "photos": photos}, indent=1, ensure_ascii=False)
    (NATURE / "photos.json").write_text(manifest, encoding="utf-8")
    APP_ASSET.write_text(manifest, encoding="utf-8")
    (NATURE / "CREDITS.md").write_text(
        "# Nature photo credits\n\nEvery photo is from Wikimedia Commons under CC0 (no rights reserved), "
        "chosen by `backgrounds/fetch_nature_photos.py` and reviewed by hand. Resized to 2048 px.\n\n"
        "| File | Subject | Photographer | Licence | Source |\n|---|---|---|---|---|\n"
        + "\n".join(credits) + "\n",
        encoding="utf-8",
    )
    print(f"{len(photos)} photos")


if __name__ == "__main__":
    {"candidates": candidates, "finalize": finalize}[sys.argv[1] if len(sys.argv) > 1 else "candidates"]()
