import Foundation

@main
struct DrawerChoiceContracts {
    @MainActor static func main() {
        precondition(DrawerChoice.defaultValue == .a)
        precondition(UnyriSidebarAppearanceSettings.defaults.drawerChoice == "a")
        for raw in ["", "unknown", "A", "column"] {
            precondition(DrawerChoice(storedValue: raw) == .a)
        }
        let legacy = UnyriSidebarAppearanceCodec.decode(#"{"version":1,"behaviorDuration":150,"hiddenItems":["calls"]}"#)
        precondition(legacy.drawerChoice == "a" && legacy.behaviorDuration == 150 && legacy.hiddenItems.contains("calls"))
        for raw in [#"{"version":1,"drawerChoice":"unknown"}"#, #"{"version":1,"drawerChoice":2}"#, #"{"version":1,"drawerChoice":null}"#] {
            precondition(UnyriSidebarAppearanceCodec.decode(raw).drawerChoice == "a")
        }
        for choice in DrawerChoice.allCases {
            var settings = legacy
            settings.drawerChoice = choice.rawValue
            precondition(UnyriSidebarAppearanceCodec.decode(UnyriSidebarAppearanceCodec.encode(settings)) == settings)
        }
        let suite = "UNYRI.drawer.choice.contracts." + UUID().uuidString
        let prefs = UserDefaults(suiteName: suite)!
        defer { prefs.removePersistentDomain(forName: suite) }
        let key = UnyriSidebarAppearanceSettings.preferenceKey
        let fresh = UnyriSidebarAppearanceStore(defaults: prefs)
        precondition(fresh.settings.drawerChoice == "a" && prefs.object(forKey: key) == nil, "Reading defaults must not write")
        precondition(fresh.apply(legacy))
        precondition(fresh.chooseDrawer(.c))
        var expected = legacy
        expected.drawerChoice = "c"
        precondition(fresh.settings == expected, "Immediate choice preserves unrelated applied preferences")
        precondition(UnyriSidebarAppearanceCodec.decode(prefs.string(forKey: key)) == expected)
        let reopened = UnyriSidebarAppearanceStore(defaults: prefs)
        precondition(reopened.settings == expected, "A new store reads the device's persisted C choice")
        precondition(reopened.chooseDrawer(.a) && reopened.settings == legacy)
        precondition(UnyriSidebarAppearanceStore(defaults: prefs).settings == legacy)
        // Owner, 14 Nov 2026: drawer A is 40% of the screen, at least 150 pt, widened by larger text.
        precondition(DrawerChoice.a.width(screenWidth: 390, accessibility: false) == 156)
        precondition(abs(DrawerChoice.a.width(screenWidth: 430, accessibility: false) - 172) < 0.001)
        precondition(DrawerChoice.a.width(screenWidth: 320, accessibility: false) == 150)
        precondition(abs(DrawerChoice.a.width(screenWidth: 390, accessibility: false, textScale: 1.35) - 210.6) < 0.001)
        precondition(DrawerChoice.a.width(screenWidth: 390, accessibility: false, textScale: 3) == 234)
        precondition(DrawerChoice.a.width(screenWidth: 390, accessibility: false, textScale: .nan) == 156)
        precondition(DrawerChoice.a.width(screenWidth: 390, accessibility: false, textScale: 0.8) == 156)
        precondition(DrawerChoice.c.width(screenWidth: 390, accessibility: false, textScale: 1.35) == 318)
        precondition(DrawerChoice.narrowHostLabel("8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud") == "8c86b74c…scw.cloud")
        precondition(DrawerChoice.narrowHostLabel("home.unyri.ru") == "home.unyri.ru")
        precondition(DrawerChoice.narrowHostLabel("192.168.0.106") == "192.168.0.106")
        for width in [320.0, 390, 430, 768] {
            precondition(DrawerChoice.a.width(screenWidth: width, accessibility: false) == min(max(width * 0.4, 150), width - 64))
            precondition(DrawerChoice.c.width(screenWidth: width, accessibility: false) == DrawerCPolicy.width(screenWidth: width, accessibility: false))
            precondition(DrawerChoice.a.width(screenWidth: width, accessibility: true) == width - 32)
            precondition(DrawerChoice.c.width(screenWidth: width, accessibility: true) == width - 32)
        }
        for width in [Double.nan, .infinity, -.infinity, -100, 0, 20] {
            for choice in DrawerChoice.allCases {
                precondition(choice.width(screenWidth: width, accessibility: false) == 0)
                precondition(choice.width(screenWidth: width, accessibility: true) == 0)
            }
        }
        for canAdd in [true, false] {
            precondition(DrawerCPolicy.accountRow(signedInCount: 2, hasOther: true, canAdd: canAdd) == .other)
            precondition(DrawerCPolicy.accountRow(signedInCount: 2, hasOther: false, canAdd: canAdd) == .none)
            precondition(DrawerCPolicy.accountRow(signedInCount: 1, hasOther: false, canAdd: canAdd) == (canAdd ? .add : .none))
        }
        precondition(DrawerCPolicy.accountRow(signedInCount: 1, hasOther: false, canAdd: true, limit: 1) == .none)
        precondition(DrawerCPolicy.accountRow(signedInCount: 0, hasOther: false, canAdd: true) == .none)
        precondition(DrawerCPolicy.accountRow(signedInCount: 1, hasOther: true, canAdd: true) == .none)
        print("PASS: A default and legacy migration; A/C codec and real store persistence; immediate isolated choice; width and exclusive other/add/none account policy")
    }
}
