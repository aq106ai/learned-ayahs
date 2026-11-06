#!/usr/bin/env python3
"""Writes the interface translations for both apps from one table.

    python .setup/make_ui_strings.py            # write the tables
    python .setup/make_ui_strings.py --check    # fail if a table is stale or a translation is bad

Source: .setup/ui_strings.json — {"<English text>": {"ur": "...", "ar": "..."}}. The English is the
key, exactly as it appears in tx("…") (Android, ui/i18n/UiStrings.kt) and tx('…') (web,
static/js/core/i18n.js). Placeholders — {0}, {1} on Android, {name} on the web — must appear in a
translation exactly as in the English; --check refuses one that drops or invents a placeholder.

Writes:
  app/src/main/assets/ui_strings_<code>.json        Android
  ../web-app/static/js/i18n/<code>.js               web (an ES module, so it loads synchronously)

Translations were written for 1.13 and should be reviewed by fluent readers; Qur'anic and Islamic
terms (ayah/آیت/آية, surah/سورت/سورة, tafsir, A'udhu, Bismillah) are kept as readers know them.
UiStringsTest (Android) and tests/unit/i18n.test.js (web) check that every key used in the code is
translated in every shipped language.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = Path(__file__).resolve().parent / "ui_strings.json"
LANGUAGES = ["ur", "ar"]
PLACEHOLDER = re.compile(r"\{[A-Za-z0-9_]+\}")


def outputs(table):
    files = {}
    for code in LANGUAGES:
        entries = {k: v[code] for k, v in sorted(table.items()) if v.get(code)}
        files[ROOT / "app" / "src" / "main" / "assets" / f"ui_strings_{code}.json"] = json.dumps(entries, ensure_ascii=False, indent=0) + "\n"
        files[ROOT.parent / "web-app" / "static" / "js" / "i18n" / f"{code}.js"] = (
            "// Generated from android-app-quran-player/.setup/ui_strings.json by make_ui_strings.py. Do not edit.\n"
            f"export default {json.dumps(entries, ensure_ascii=False, indent=0)};\n"
        )
    return files


# tx("…" [+ "…"]*) in Kotlin; tx('…') / tx("…") in JavaScript. Keys built at run time (tx(label))
# are not seen here; UiStringsTest covers the enum labels passed that way.
KT_CALL = re.compile(r'\btx\(\s*((?:"(?:[^"\\\n]|\\.)*"\s*\+?\s*)+)')
KT_PART = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
JS_CALL = re.compile(r"""\btx\(\s*(?:'((?:[^'\\\n]|\\.)*)'|"((?:[^"\\\n]|\\.)*)")""")
UNESCAPE = re.compile(r'\\(.)')


def unescape(s):
    return UNESCAPE.sub(lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), s)


def used_keys():
    keys = set()
    for f in (ROOT / "app" / "src" / "main" / "java").rglob("*.kt"):
        if f.name == "UiStrings.kt":
            continue
        for m in KT_CALL.finditer(f.read_text(encoding="utf-8")):
            keys.add(unescape("".join(KT_PART.findall(m.group(1)))))
    web = ROOT.parent / "web-app" / "static" / "js"
    for f in [p for d in ("views", "services", "player", "core", "video") for p in (web / d).rglob("*.js")] + [web / "main.js"]:
        if f.name == "i18n.js":
            continue
        for m in JS_CALL.finditer(f.read_text(encoding="utf-8")):
            keys.add(unescape(m.group(1) if m.group(1) is not None else m.group(2)))
    return keys


def problems(table):
    out = []
    for key, tr in table.items():
        want = sorted(PLACEHOLDER.findall(key))
        for code in LANGUAGES:
            text = tr.get(code)
            if not text:
                out.append(f"{code}: no translation for {key!r}")
            elif sorted(PLACEHOLDER.findall(text)) != want:
                out.append(f"{code}: placeholders differ in {key!r}: {text!r}")
    return out


def main():
    table = json.loads(SOURCE.read_text(encoding="utf-8"))
    bad = problems(table) + [f"used in the code but not in ui_strings.json: {k!r}" for k in sorted(used_keys() - table.keys())]
    files = outputs(table)
    if "--check" in sys.argv:
        stale = [str(p) for p, text in files.items() if not p.is_file() or p.read_text(encoding="utf-8") != text]
        for line in bad + [f"stale: {s} (run make_ui_strings.py)" for s in stale]:
            print(line, file=sys.stderr)
        sys.exit(1 if bad or stale else 0)
    if bad:
        sys.exit("\n".join(bad))
    for path, text in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(text.encode("utf-8"))
    print(f"{len(table)} strings, {len(LANGUAGES)} languages")


if __name__ == "__main__":
    main()
