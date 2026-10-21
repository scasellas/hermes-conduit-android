#!/usr/bin/env python3
"""List English keys used by the Android code (t("…") / L10n.tr("…")) that
have no translation in the generated tables. Prints JSON {key: null}."""
import json, os, re, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java")
pattern = re.compile(r'(?:\bt|L10n\.tr)\(\s*"((?:[^"\\]|\\.)*)"')
keys = set()
for dirpath, _, files in os.walk(SRC):
    for f in files:
        if f.endswith(".kt"):
            text = open(os.path.join(dirpath, f), encoding="utf-8").read()
            for m in pattern.finditer(text):
                k = m.group(1)
                if "$" in k:  # Kotlin templates aren't keys
                    continue
                keys.add(bytes(k, "utf-8").decode("unicode_escape").encode("latin-1").decode("utf-8"))
table = json.load(open(os.path.join(ROOT, "app", "src", "main", "assets", "l10n", "de.json"), encoding="utf-8"))
missing = sorted(k for k in keys if k not in table)
print(len(keys), "keys,", len(missing), "missing", file=sys.stderr)
json.dump(missing, sys.stdout, ensure_ascii=False, indent=0)
