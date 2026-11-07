/// Independent from saved chat appearance. Unknown/missing preferences keep the
/// monochrome presentation; an explicitly chosen colorful theme is preserved.
enum UnyriSymbols {
    /// Use the same Rooms glyph in navigation, invitations and room menus.
    static let rooms = "person.3"
}

enum UnyriAppColorStyle: String, CaseIterable, Sendable {
    case colorful, monochrome

    static let preferenceKey = "app-color-style-v1"
    static func parse(_ raw: String?) -> Self { raw.flatMap(Self.init(rawValue:)) ?? .monochrome }

    var isMonochrome: Bool { self == .monochrome }
    static let monochromeAccent: UInt32 = 0xDEDEDE
    /// UISwitch keeps a white thumb: a middle-gray track preserves its contour.
    static let monochromeControlTint: UInt32 = 0x858585

    func accentRGB(_ role: UnyriAppColorRole) -> UInt32 {
        isMonochrome ? Self.monochromeAccent : role.rgb
    }

    /// Desaturate only decorative materials, never photos/video, avatars or
    /// functional danger/success/presence/read-receipt colors.
    func materialRGB(_ rgb: UInt32) -> UInt32 {
        guard isMonochrome else { return rgb }
        let red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255
        let gray = (2126 * red + 7152 * green + 722 * blue + 5000) / 10000
        return gray * 0x010101
    }
}

enum UnyriAppColorRole: CaseIterable, Sendable {
    case categoryBlue, categoryMint, categoryViolet, categoryAmber, categoryRose
    var rgb: UInt32 {
        switch self {
        case .categoryBlue: return 0x7BC9EC
        case .categoryMint: return 0x83D9B7
        case .categoryViolet: return 0xAFA4F7
        case .categoryAmber: return 0xE6BD73
        case .categoryRose: return 0xE995AD
        }
    }
}
