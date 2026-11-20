#!/usr/bin/env python3
"""Build ios/UNYRI/Resources/ReactionEmojiKeywords.json from Unicode CLDR annotations (ru/uk/en).

Inputs (downloaded once; not committed): emoji-test.txt (Unicode Emoji 15.1) and CLDR 46 cldr-json
annotations-full + annotations-derived-full for ru, uk and en. Output schema (read by
UnyriReactionCatalog.build): {"<emoji without FE0F>": {"ru": ["name", "keyword keyword ..."], "uk": [...], "en": [...]}}.
Only fully-qualified emoji up to Emoji 15.0 (iOS 17 renders them), without skin-tone variants (the
picker synthesizes tones from the base emoji).

Usage: python3 -I tools/cldr/generate_reaction_keywords.py <download dir> <output json>
"""
import json
import re
import sys
from pathlib import Path

TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
LANGS = ("ru", "uk", "en")


def load(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    block = data.get("annotations") or data.get("annotationsDerived") or {}
    return block.get("annotations", {})


def main(src, out):
    src = Path(src)
    emoji = []
    for line in (src / "emoji-test.txt").read_text(encoding="utf-8").splitlines():
        m = re.match(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*\S+\s+E(\d+)\.(\d+)", line)
        if not m:
            continue
        version = (int(m.group(2)), int(m.group(3)))
        scalars = [int(x, 16) for x in m.group(1).split()]
        if version > (15, 0) or any(s in TONES for s in scalars):
            continue
        emoji.append("".join(chr(s) for s in scalars if s != 0xFE0F))
    tables = {lang: {**load(src / f"annotationsDerived-{lang}.json"), **load(src / f"annotations-{lang}.json")} for lang in LANGS}
    result = {}
    for key in emoji:
        entry = {}
        for lang in LANGS:
            item = tables[lang].get(key)
            if not item:
                continue
            words = [w.strip() for w in item.get("default", []) if w.strip()]
            name = (item.get("tts") or words or [""])[0].strip()
            if not name:
                continue
            entry[lang] = [name, " ".join(w for w in words if w != name)] if words else [name]
        if entry:
            result[key] = entry
    Path(out).write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":"), sort_keys=False), encoding="utf-8")
    print(f"emoji considered {len(emoji)}, with names {len(result)}, bytes {Path(out).stat().st_size}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
