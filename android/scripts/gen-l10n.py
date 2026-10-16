#!/usr/bin/env python3
"""Generate app/src/main/assets/l10n/<lang>.json from the iOS String Catalogs.

The iOS catalogs key every string by its English text, so the Android app
looks strings up the same way (see L10n.kt). Swift format specifiers are
rewritten for java.util.Formatter: %@ -> %s, %lld/%ld/%d -> %d, %1$@ -> %1$s.
Android-only strings and their translations live in android-strings.xcstrings
(same format) and are merged on top.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
CATALOGS = [
    os.path.join(REPO, "Conduit", "Localizable.xcstrings"),
    os.path.join(ROOT, "android-strings.xcstrings"),
]
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "l10n")

SPEC = re.compile(r"%(\d+\$)?(l{0,2})([@dDuUxXfeEgGcCsS])")


def convert(value: str) -> str:
    def repl(m):
        pos, _, kind = m.groups()
        pos = pos or ""
        if kind == "@" or kind in "sS":
            return f"%{pos}s"
        if kind in "dDuU":
            return f"%{pos}d"
        return f"%{pos}{kind}"
    return SPEC.sub(repl, value)


def unit_value(loc):
    unit = loc.get("stringUnit")
    if unit and unit.get("value") is not None:
        return unit["value"]
    # Plural/device variations: take "other" (or the first) form.
    variations = loc.get("variations", {})
    for kind in ("plural", "device"):
        forms = variations.get(kind)
        if forms:
            form = forms.get("other") or next(iter(forms.values()))
            return form.get("stringUnit", {}).get("value")
    return None


def main():
    tables = {}
    for path in CATALOGS:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            catalog = json.load(f)
        for key, entry in catalog.get("strings", {}).items():
            for lang, loc in entry.get("localizations", {}).items():
                if lang == "en":
                    continue
                value = unit_value(loc)
                if value is None:
                    continue
                tables.setdefault(lang, {})[convert(key)] = convert(value)
    os.makedirs(OUT, exist_ok=True)
    for lang, table in sorted(tables.items()):
        with open(os.path.join(OUT, f"{lang}.json"), "w", encoding="utf-8") as f:
            json.dump(table, f, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        print(f"{lang}: {len(table)} strings", file=sys.stderr)


if __name__ == "__main__":
    main()
