import Foundation

@main struct AppColorStyleContracts {
    static func main() {
        var count = 0
        func expect(_ value: @autoclosure () -> Bool, _ description: String) {
            precondition(value(), description); count += 1
        }
        expect(UnyriAppColorStyle.parse(nil) == .monochrome, "Fresh installs use the requested monochrome default")
        for legacy in ["", "system", "dark", "light", "invalid"] {
            expect(UnyriAppColorStyle.parse(legacy) == .monochrome, "Unknown values use the approved default")
        }
        for style in UnyriAppColorStyle.allCases {
            expect(UnyriAppColorStyle.parse(style.rawValue) == style, "Both explicit values round trip")
        }
        expect(UnyriAppColorStyle.preferenceKey == "app-color-style-v1", "Cross-platform independent preference key")
        for (role, original) in zip(UnyriAppColorRole.allCases, [UInt32(0x7BC9EC),0x83D9B7,0xAFA4F7,0xE6BD73,0xE995AD]) {
            expect(UnyriAppColorStyle.colorful.accentRGB(role) == original, "Colorful follows current iOS role")
            expect(UnyriAppColorStyle.monochrome.accentRGB(role) == 0xDEDEDE, "Decoration is neutral in monochrome")
        }
        for (original, gray) in [(UInt32(0),UInt32(0)),(0xFFFFFF,0xFFFFFF),(0xFF0000,0x363636),
                                 (0x00FF00,0xB6B6B6),(0x0000FF,0x121212),(0xAFA4F7,0xACACAC)] {
            expect(UnyriAppColorStyle.colorful.materialRGB(original) == original, "Colorful materials remain byte-identical")
            expect(UnyriAppColorStyle.monochrome.materialRGB(original) == gray, "Material luminance is deterministic")
        }
        func luminance(_ rgb: UInt32) -> Double {
            let channels = [Double((rgb >> 16) & 255),Double((rgb >> 8) & 255),Double(rgb & 255)].map { channel -> Double in
                let value = channel / 255; return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055,2.4)
            }
            return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
        }
        func contrast(_ one: UInt32, _ two: UInt32) -> Double {
            let a = luminance(one), b = luminance(two); return (max(a,b) + 0.05) / (min(a,b) + 0.05)
        }
        expect(contrast(0xDEDEDE,0x222222) > 10, "Filled monochrome auth buttons use dark readable ink")
        expect(contrast(UnyriAppColorStyle.monochromeControlTint,0xFFFFFF) >= 3, "System switch white thumb remains distinguishable")
        expect(contrast(UnyriAppColorStyle.monochromeControlTint,0x000000) >= 4.5, "Neutral system tint remains readable on black")
        print("PASS: \(count) app color policy and contrast contracts")
    }
}
