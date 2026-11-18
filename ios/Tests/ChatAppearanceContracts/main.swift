import Foundation

func require(_ condition: @autoclosure () -> Bool, _ message: String) {
    precondition(condition(), message)
}
func json(_ value: [String: Any]) -> Data { try! JSONSerialization.data(withJSONObject: value) }

/// Deterministic interleaving: another subsystem writes just after a domain
/// snapshot is read (old implementation), or during the first owned-key write.
final class RacingAppearanceDefaults: UserDefaults {
    var armed = false
    var injections = 0
    private func inject() {
        guard armed else { return }
        armed = false; injections += 1
        super.set("racing-diagnostics-value", forKey: "unrelated-diagnostics")
    }
    override func persistentDomain(forName domainName: String) -> [String: Any]? {
        let snapshot = super.persistentDomain(forName: domainName)
        inject()
        return snapshot
    }
    override func set(_ value: Any?, forKey defaultName: String) {
        if UnyriChatAppearanceSettings.preferenceKeys.contains(defaultName) || defaultName == UnyriChatAppearanceSettings.revisionKey { inject() }
        super.set(value, forKey: defaultName)
    }
}

let suite = "COCO.appearance.contracts." + UUID().uuidString
let defaults = UserDefaults(suiteName: suite)!
defer { defaults.removePersistentDomain(forName: suite) }
let beforeRead = defaults.persistentDomain(forName: suite) ?? [:]
require(UnyriChatAppearanceSettings.read(from: defaults) == UnyriChatAppearanceSettings(), "Approved clean defaults")
require(NSDictionary(dictionary: beforeRead).isEqual(to: defaults.persistentDomain(forName: suite) ?? [:]), "Read must never persist")
require(UnyriChatAppearanceSettings().fontSize == 17 && UnyriChatAppearanceSettings().mediumText &&
    UnyriChatAppearanceSettings().density == "relaxed", "Approved 17pt, slightly heavier text and spacious layout defaults")
require(UnyriSavedChatStyle.pattern == "coco" && UnyriSavedChatStyle.patternScale == 32, "Approved wallpaper")
require(UnyriSavedChatStyle.headerHeight == 62 && UnyriSavedChatStyle.avatarSize == 36 && UnyriSavedChatStyle.entryHeight == 40 && UnyriSavedChatStyle.entryFont == 15 && UnyriSavedChatStyle.entryRadius == 30, "Approved static chrome")
require(UnyriAppearanceSurfaceMetrics.alpha(transparency: 23) == Double(196) / 255 && UnyriAppearanceSurfaceMetrics.alpha(transparency: 25) == Double(191) / 255, "Match Android saved 8-bit text/voice/file alpha")
require(UnyriAppearanceSurfaceMetrics.alpha(transparency: 0) == 1 && UnyriAppearanceSurfaceMetrics.alpha(transparency: 100) == 0, "Transparency endpoints")
defaults.set("ru", forKey: "coco.language")
defaults.set(22, forKey: UnyriChatAppearanceSettings.fontSizeKey)
defaults.set(false, forKey: UnyriChatAppearanceSettings.patternKey)
defaults.set(json(["version": 1, "fontFamily": "serif"]), forKey: UnyriChatAppearanceSettings.overridesKey)
let oldJSON = defaults.data(forKey: UnyriChatAppearanceSettings.overridesKey)
UnyriChatAppearanceSettings.adoptSavedPreset(in: defaults, domainName: suite)
let migrated = UnyriChatAppearanceSettings.read(from: defaults)
require(migrated.fontSize == 17 && migrated.patterned, "Current revision adopts approved text and pattern once")
require(migrated.fontFamily == "serif", "Migration preserves valid presentation overrides")
require(defaults.data(forKey: UnyriChatAppearanceSettings.overridesKey) == oldJSON, "Migration cannot replace JSON")
require(defaults.string(forKey: "coco.language") == "ru", "Migration preserves unrelated preferences")

let selected = UnyriChatAppearanceSettings(fontSize: 21, fontFamily: "serif", mediumText: true,
    lineHeight: 1.6, density: "compact", cornerRadius: 0, outgoingColor: "green", transparency: 0, patterned: false)
selected.save(to: defaults, domainName: suite)
require(UnyriChatAppearanceSettings.read(from: defaults) == selected, "Save reload roundtrip including explicit zero")
let afterSave = defaults.persistentDomain(forName: suite)!
let afterSaveJSON = defaults.data(forKey: UnyriChatAppearanceSettings.overridesKey)
UnyriChatAppearanceSettings.adoptSavedPreset(in: defaults, domainName: suite)
require(UnyriChatAppearanceSettings.read(from: defaults) == selected &&
    defaults.integer(forKey: UnyriChatAppearanceSettings.revisionKey) == UnyriSavedChatStyle.revision &&
    defaults.data(forKey: UnyriChatAppearanceSettings.overridesKey) == afterSaveJSON &&
    Set(afterSave.keys) == Set(defaults.persistentDomain(forName: suite)!.keys), "Migration idempotent and preserves every saved appearance field and key")
require(defaults.string(forKey: "coco.language") == "ru", "Save preserves unrelated preferences")
let draft = UnyriChatAppearanceSettings()
require(UnyriChatAppearanceSettings.read(from: defaults) == selected, "Reset draft cannot change saved theme")
draft.save(to: defaults, domainName: suite)
require(UnyriChatAppearanceSettings.read(from: defaults) == draft, "Apply default restores exact defaults")
require(defaults.object(forKey: UnyriChatAppearanceSettings.overridesKey) == nil, "Default save removes only overrides")
require(defaults.string(forKey: "coco.language") == "ru", "Reset preserves language")

defaults.set(Data("{ broken".utf8), forKey: UnyriChatAppearanceSettings.overridesKey)
require(UnyriChatAppearanceSettings.read(from: defaults) == draft, "Malformed JSON uses defaults")
defaults.set(json(["version": 2, "fontFamily": "monospace"]), forKey: UnyriChatAppearanceSettings.overridesKey)
require(UnyriChatAppearanceSettings.read(from: defaults) == draft, "Unsupported version cannot silently change settings")
defaults.set(json(["version": true, "fontFamily": "monospace"]), forKey: UnyriChatAppearanceSettings.overridesKey)
require(UnyriChatAppearanceSettings.read(from: defaults) == draft, "Boolean version must be rejected")
defaults.set(json(["version": 1, "fontFamily": "unknown", "mediumText": "false", "lineHeight": 999,
    "density": "broken", "cornerRadius": -22, "outgoingColor": "unknown", "transparency": 999]), forKey: UnyriChatAppearanceSettings.overridesKey)
let bounded = UnyriChatAppearanceSettings.read(from: defaults)
require(bounded.fontFamily == "system" && bounded.mediumText && bounded.lineHeight == 1.7, "Validate family/bool/line height without coercing a string flag")
require(bounded.cornerRadius == 0 && bounded.transparency == 85 && bounded.density == "relaxed" && bounded.outgoingColor == "default", "Clamp values without losing zero")
defaults.set(json(["version": 1, "cornerRadius": NSNull(), "transparency": NSNull()]), forKey: UnyriChatAppearanceSettings.overridesKey)
require(UnyriChatAppearanceSettings.read(from: defaults).cornerRadius == nil && UnyriChatAppearanceSettings.read(from: defaults).transparency == nil, "Null means preserve per-type approved corners/alpha")
defaults.set(json(["version": 1, "fontSize": 22, "patterned": false, "fontFamily": "monospace", "mediumText": true]), forKey: UnyriChatAppearanceSettings.overridesKey)
defaults.set(13, forKey: UnyriChatAppearanceSettings.fontSizeKey)
defaults.set(true, forKey: UnyriChatAppearanceSettings.patternKey)
let legacyWins = UnyriChatAppearanceSettings.read(from: defaults)
require(legacyWins.fontSize == 13 && legacyWins.patterned && legacyWins.fontFamily == "monospace" && legacyWins.mediumText, "Legacy keys authoritative with other overrides")
defaults.set("22", forKey: UnyriChatAppearanceSettings.fontSizeKey)
defaults.set("false", forKey: UnyriChatAppearanceSettings.patternKey)
require(UnyriChatAppearanceSettings.read(from: defaults).fontSize == 17 && UnyriChatAppearanceSettings.read(from: defaults).patterned, "Wrong storage types cannot coerce silently")
defaults.set(Data(repeating: 0, count: 16_385), forKey: UnyriChatAppearanceSettings.overridesKey)
require(UnyriChatAppearanceSettings.read(from: defaults) == draft, "Bound malformed payload allocation")
let invalid = UnyriChatAppearanceSettings(fontSize: Int.min, fontFamily: "bad", mediumText: false,
    lineHeight: .nan, density: "bad", cornerRadius: .infinity, outgoingColor: "bad", transparency: Int.max, patterned: true).sanitized()
require(invalid.fontSize == 13 && invalid.lineHeight == 1.3 && invalid.cornerRadius == nil && invalid.transparency == 85, "Nonfinite and extreme draft values sanitized")

let raceSuite = "COCO.appearance.race." + UUID().uuidString
let racing = RacingAppearanceDefaults(suiteName: raceSuite)!
defer { racing.removePersistentDomain(forName: raceSuite) }
racing.set("old", forKey: "unrelated-diagnostics")
racing.armed = true
let staleSnapshot = racing.persistentDomain(forName: raceSuite)!
racing.setPersistentDomain(staleSnapshot, forName: raceSuite)
require(racing.string(forKey: "unrelated-diagnostics") == "old" && racing.injections == 1, "Race fixture must reproduce the old whole-domain lost update")
racing.armed = true
selected.save(to: racing, domainName: raceSuite)
require(racing.string(forKey: "unrelated-diagnostics") == "racing-diagnostics-value" && racing.injections == 2, "Save must preserve unrelated writes interleaved at the defaults boundary")
require(UnyriChatAppearanceSettings.read(from: racing) == selected, "Race-safe save still persists every appearance field")
racing.set("old", forKey: "unrelated-diagnostics")
racing.armed = true
UnyriChatAppearanceSettings.adoptSavedPreset(in: racing, domainName: raceSuite)
require(racing.string(forKey: "unrelated-diagnostics") == "racing-diagnostics-value" && racing.injections == 3, "Migration must preserve unrelated concurrent writes")

var geometryCases = 0
require(UnyriAppearanceCornerMetrics.applyingOverride([25,25,25,0], radius: nil) == [25,25,25,0], "Production corner conversion preserves saved radii")
require(UnyriAppearanceCornerMetrics.applyingOverride([25,25,25,0], radius: 17.5) == [17.5,17.5,17.5,0], "Production Double to CGFloat override conversion preserves the notch")
require(UnyriAppearanceCornerMetrics.applyingOverride([25,25,0,25], radius: 0) == [0,0,0,0], "Production zero override is literal")
for width: CGFloat in [0, 12, 32, 120, 220, 390, 844] {
    for height: CGFloat in [0, 13, 22, 31, 50, 88, 300] {
        for input: [CGFloat] in [[25,25,25,0], [25,25,0,25], [42,42,42,42], [0,0,0,0], [0,42,12,0]] {
            let radii = UnyriAppearanceCornerMetrics.normalized(input, width: width, height: height)
            require(radii[0] + radii[1] <= width + 0.0001 && radii[3] + radii[2] <= width + 0.0001, "Top/bottom corner pairs fit")
            require(radii[0] + radii[3] <= height + 0.0001 && radii[1] + radii[2] <= height + 0.0001, "Side corner pairs fit")
            for index in 0..<4 where input[index] == 0 { require(radii[index] == 0, "Preserve intentional zero notch") }
            let factors = (0..<4).filter { input[$0] > 0 }.map { radii[$0] / input[$0] }
            if let first = factors.first { require(factors.allSatisfy { abs($0 - first) < 0.0001 }, "Uniform CSS scale") }
            geometryCases += 1
        }
    }
}
require(UnyriAppearanceCornerMetrics.normalized([25,25,25,0], width: 120, height: 30) == [15,15,15,0], "Approved short message uniform normalization")
print("PASS: appearance storage, race-safe owned-key writes, migration, validation and \(geometryCases) CSS corner geometry cases")
