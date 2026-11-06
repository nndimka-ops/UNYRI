"""Keep known RU/UK localization debt visible and reject any new missing keys."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def catalog(directory: Path) -> set[str]:
    keys = set()
    for file in directory.glob("*.xml"):
        for element in ET.parse(file).getroot():
            if element.tag in ("string", "plurals", "string-array") and element.get("translatable") != "false":
                keys.add(element.attrib["name"])
    return keys


def evaluate(resources: Path, baseline: dict) -> dict:
    if baseline.get("schemaVersion") != 1 or set(baseline.get("knownMissing", {})) != {"values-ru", "values-uk"}:
        raise ValueError("Expected the reviewed RU/UK localization debt schema")
    default = catalog(resources / "values")
    if not default:
        raise ValueError("Default English resource catalog is empty")
    result = {"default_translatable_keys": len(default), "core": {}, "passed": True}
    for locale, known in baseline["knownMissing"].items():
        missing = default - catalog(resources / locale)
        newly_missing = sorted(missing - set(known))
        result["core"][locale] = {"missing": len(missing), "recorded_debt": len(known),
                                  "newly_missing": newly_missing,
                                  "completed_since_recording": sorted(set(known) - missing)}
        result["passed"] = result["passed"] and not newly_missing
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", type=Path, default=ROOT / "android/app/src/main/res")
    parser.add_argument("--baseline", type=Path, default=ROOT / "android/localization-known-missing.json")
    args = parser.parse_args()
    result = evaluate(args.resources, json.loads(args.baseline.read_text(encoding="utf-8")))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result["passed"] else 1)
