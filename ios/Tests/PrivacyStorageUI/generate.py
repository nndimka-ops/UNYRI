"""Prepare an isolated native UI fixture using unmodified production views.

Transport/account/call state is synthetic. Production policies, repositories,
lock controller, settings views, shared settings rows and palette are copied.
"""
import sys, plistlib, hashlib, json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/qa"))
from native_ui_project import write_project

def block(source, marker):
    start = source.index(marker); end = source.index("{", start) + 1; depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}"); end += 1
    return source[start:end]

destination = Path(sys.argv[1]); destination.mkdir(parents=True, exist_ok=True)
sources = []
for rel in ["Support/UserSettingsPolicy.swift", "Support/UserSettings.swift", "Support/AccountAutoDelete.swift", "Support/AppColorStyle.swift", "Support/AppLanguage.swift", "Models/UserModels.swift", "UI/Profile/PrivacyStorageSettingsView.swift", "UI/Profile/AccountAutoDeleteSettingsView.swift"]:
    name = "Production" + Path(rel).name; (destination/name).write_bytes((ROOT/"ios/COCO"/rel).read_bytes()); sources.append(name)
settings = (ROOT/"ios/UNYRI/UI/Profile/ProfileView.swift").read_text(encoding="utf-8")
(destination/"ProductionSettings.swift").write_text(settings[:settings.index("private struct AccountDevice:")], encoding="utf-8"); sources.append("ProductionSettings.swift")
theme = (ROOT/"ios/UNYRI/UI/Theme/UnyriTheme.swift").read_text(encoding="utf-8")
tokens = theme[:theme.index("struct UnyriAppColorThemePicker:")]
for name in ["enum UnyriColors", "enum UnyriShapes", "enum UnyriHaptics", "private struct UnyriPressFeedback", "struct UnyriQuietButtonStyle", "struct UnyriPrimaryButtonStyle", "struct UnyriNotice"]:
    tokens += "\n" + block(theme, name)
(destination/"ProductionSettingsTheme.swift").write_text(tokens, encoding="utf-8"); sources.append("ProductionSettingsTheme.swift")
for name in ["Fixture.swift", "SettingsTests.swift"]: (destination/name).write_bytes((Path(__file__).parent/name).read_bytes())
write_project(destination, "PrivacyStorageUI", "com.coco.qa.privacystorage", sources + ["Fixture.swift"], ["SettingsTests.swift"])
(destination/"Localizable.xcstrings").write_bytes((ROOT/"ios/UNYRI/Localizable.xcstrings").read_bytes())
project = destination/"PrivacyStorageUI.xcodeproj/project.pbxproj"; spec = plistlib.loads(project.read_bytes()); objects = spec["objects"]
ref = "F11010000000000000000001"; build = "F11010000000000000000002"
objects[ref] = dict(isa="PBXFileReference", lastKnownFileType="text.json.xcstrings", path="Localizable.xcstrings", sourceTree="<group>")
objects[build] = dict(isa="PBXBuildFile", fileRef=ref)
next(value for value in objects.values() if value["isa"] == "PBXResourcesBuildPhase")["files"].append(build)
next(value for value in objects.values() if value["isa"] == "PBXGroup" and "name" not in value)["children"].append(ref)
for value in objects.values():
    if value["isa"] == "XCBuildConfiguration": value["buildSettings"]["INFOPLIST_KEY_NSFaceIDUsageDescription"] = "Unlock COCO settings fixture."
project.write_bytes(plistlib.dumps(spec))
(destination/"source-manifest.json").write_text(json.dumps({"evidence":"native synthetic transport and account; no real biometric or network proof", "sources":{name:hashlib.sha256((destination/name).read_bytes()).hexdigest() for name in sources}}, indent=2), encoding="utf-8")
