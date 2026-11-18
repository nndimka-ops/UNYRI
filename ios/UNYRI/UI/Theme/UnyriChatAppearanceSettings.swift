import Foundation

/// Presentation only. Draft editing never writes defaults or changes account data.
struct UnyriChatAppearanceSettings: Equatable, Sendable {
    var fontSize: Int = Int(UnyriSavedChatStyle.fontSize)
    var fontFamily: String = UnyriSavedChatStyle.fontFamily
    var mediumText: Bool = UnyriSavedChatStyle.mediumText
    var lineHeight: Double = Double(UnyriSavedChatStyle.lineHeight)
    var density: String = "relaxed"
    var cornerRadius: Double? = nil
    var outgoingColor: String = "default"
    var transparency: Int? = nil
    var patterned: Bool = UnyriSavedChatStyle.pattern != "none"

    static let overridesKey = "cocoChatAppearanceOverridesV1"
    static let fontSizeKey = "cocoChatTextSize"
    static let patternKey = "cocoSubtlePattern"
    static let revisionKey = "cocoSavedAppearanceRevision"
    static let preferenceKeys = Set([overridesKey, fontSizeKey, patternKey])

    func sanitized() -> Self {
        var result = self
        result.fontSize = min(22, max(13, fontSize))
        if !["system", "serif", "monospace"].contains(fontFamily) { result.fontFamily = Self().fontFamily }
        result.lineHeight = lineHeight.isFinite ? min(1.7, max(1.05, lineHeight)) : Self().lineHeight
        if !["default", "compact", "relaxed"].contains(density) { result.density = Self().density }
        result.cornerRadius = cornerRadius.flatMap { $0.isFinite ? min(42, max(0, $0)) : nil }
        if !["default", "blue", "violet", "green", "graphite"].contains(outgoingColor) { result.outgoingColor = "default" }
        result.transparency = transparency.map { min(85, max(0, $0)) }
        return result
    }

    /// Legacy font/background keys remain authoritative for established controls.
    static func read(from defaults: UserDefaults = .standard) -> Self {
        var result = Self()
        if let value = finiteNumber(defaults.object(forKey: fontSizeKey)) {
            result.fontSize = Int(min(22, max(13, value)))
        }
        if let value = strictBool(defaults.object(forKey: patternKey)) { result.patterned = value }
        if let data = defaults.data(forKey: overridesKey), data.count <= 16_384,
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           finiteNumber(json["version"]) == 1 {
            result.fontFamily = json["fontFamily"] as? String ?? result.fontFamily
            result.mediumText = strictBool(json["mediumText"]) ?? result.mediumText
            result.lineHeight = finiteNumber(json["lineHeight"]) ?? result.lineHeight
            result.density = json["density"] as? String ?? result.density
            result.cornerRadius = finiteNumber(json["cornerRadius"])
            result.outgoingColor = json["outgoingColor"] as? String ?? result.outgoingColor
            result.transparency = finiteNumber(json["transparency"]).map { Int(min(85, max(0, $0))) }
        }
        return result.sanitized()
    }

    /// Only appearance-owned keys are changed. The MainActor store applies these
    /// synchronous writes before publishing one resolved snapshot to the UI.
    func save(to defaults: UserDefaults = .standard, domainName: String? = Bundle.main.bundleIdentifier) {
        let chosen = sanitized()
        var updates: [String: Any] = [Self.fontSizeKey: chosen.fontSize, Self.patternKey: chosen.patterned]
        var removals = Set<String>()
        if chosen == Self().sanitized() {
            removals.insert(Self.overridesKey)
        } else {
            var json: [String: Any] = ["version": 1, "fontFamily": chosen.fontFamily,
                "mediumText": chosen.mediumText, "lineHeight": chosen.lineHeight,
                "density": chosen.density, "outgoingColor": chosen.outgoingColor]
            if let radius = chosen.cornerRadius { json["cornerRadius"] = radius }
            if let transparency = chosen.transparency { json["transparency"] = transparency }
            // All numbers have already been bounded and checked for finiteness.
            guard let data = try? JSONSerialization.data(withJSONObject: json, options: [.sortedKeys]) else { return }
            updates[Self.overridesKey] = data
        }
        Self.update(defaults, domainName: domainName, values: updates, removing: removals)
    }

    /// Explicit boot migration, separate from the pure read path. Apply the approved
    /// preset once; choices made after this revision are never overwritten on launch.
    static func adoptSavedPreset(in defaults: UserDefaults = .standard, domainName: String? = Bundle.main.bundleIdentifier) {
        let previous = finiteNumber(defaults.object(forKey: revisionKey)) ?? 0
        guard previous < Double(UnyriSavedChatStyle.revision) else { return }
        var values: [String: Any] = [revisionKey: UnyriSavedChatStyle.revision,
            patternKey: UnyriSavedChatStyle.pattern != "none"]
        if previous < Double(UnyriSavedChatStyle.textSizeRevision) {
            values[fontSizeKey] = Int(UnyriSavedChatStyle.fontSize)
        }
        update(defaults, domainName: domainName, values: values, removing: [])
    }

    private static func finiteNumber(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, String(cString: number.objCType) != "c" else { return nil }
        let double = number.doubleValue
        return double.isFinite ? double : nil
    }

    private static func strictBool(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, String(cString: number.objCType) == "c" else { return nil }
        return number.boolValue
    }

    private static func update(_ defaults: UserDefaults, domainName: String?, values: [String: Any], removing: Set<String>) {
        // Do not read/replace the complete domain: diagnostics and language may
        // write unrelated preferences concurrently from another subsystem.
        // domainName is retained for compatibility with isolated suite tests.
        for key in removing { defaults.removeObject(forKey: key) }
        for (key, value) in values { defaults.set(value, forKey: key) }
    }
}
