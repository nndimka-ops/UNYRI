import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

/// Portable production-policy contracts. No UIKit, app account or server is used.
@main
enum ReactionPickerContracts {
    static func require(_ condition: @autoclosure () -> Bool, _ message: String) {
        guard condition() else { fatalError("ReactionPickerContracts: " + message) }
    }

    static func main() throws {
        // Compare actual geometry values, including offscreen/tall bubbles and
        // landscape side insets, rather than checking source-code spellings.
        let viewports: [(CGSize, CGFloat, CGFloat, CGFloat, CGFloat)] = [
            (CGSize(width: 390, height: 844), 59, 34, 0, 0),
            (CGSize(width: 320, height: 568), 20, 0, 0, 0),
            (CGSize(width: 844, height: 390), 0, 21, 59, 59),
            (CGSize(width: 1024, height: 1366), 24, 20, 0, 0),
            (CGSize(width: 390, height: 844), 59, 344, 0, 0)
        ]
        for (size, top, bottom, left, right) in viewports {
            for bubble in [CGRect(x: 12, y: top + 10, width: 200, height: 52),
                           CGRect(x: size.width - 232, y: size.height - bottom - 80, width: 220, height: 60),
                           CGRect(x: -40, y: -100, width: 300, height: 50),
                           CGRect(x: size.width - 40, y: size.height + 50, width: 300, height: 50),
                           CGRect(x: 10, y: -20, width: 300, height: size.height + 40)] {
                for outgoing in [false, true] {
                    let panel = UnyriReactionPanelGeometry.frame(viewport: size, bubble: bubble, top: top, bottom: bottom,
                                                               left: left, right: right, outgoing: outgoing)
                    require(panel.minX >= left + 16 && panel.maxX <= size.width - right - 16,
                            "panel stays inside horizontal safe margins")
                    require(panel.minY >= top + 8 && panel.maxY <= size.height - bottom - 8,
                            "B-057: panel stays inside both vertical safe edges")
                    require(panel.width == min(420, size.width - left - right - 32),
                            "panel uses screen minus margins with 420 pt cap")
                    require(panel.height >= 0 && panel.height <= size.height * 0.6,
                            "panel height never exceeds sixty percent")
                }
            }
        }
        let lastBubble = CGRect(x: 150, y: 700, width: 224, height: 60)
        let lastPanel = UnyriReactionPanelGeometry.frame(viewport: CGSize(width: 390, height: 844), bubble: lastBubble,
                                                        top: 59, bottom: 34, left: 0, right: 0, outgoing: true)
        require(lastPanel.maxY <= lastBubble.minY - 8 && lastPanel.height >= 240,
                "owner's last-bubble case has room for search and the first grid row above the bubble")
        let topBubble = CGRect(x: 16, y: 72, width: 224, height: 60)
        let lowerPanel = UnyriReactionPanelGeometry.frame(viewport: CGSize(width: 390, height: 844), bubble: topBubble,
                                                         top: 59, bottom: 34, left: 0, right: 0, outgoing: false)
        require(lowerPanel.minY >= topBubble.maxY + 8 && lowerPanel.height >= 240,
                "a bubble near the status bar uses the available lower side")

        let now = Date(timeIntervalSince1970: 2_000_000_000)
        var history = UnyriReactionHistory()
        require(history.ranked(quick: "👍", now: now) == ["👍", "❤️", "😂", "😮", "😢", "🙏", "🔥"],
                "defaults retain the owner's order")
        require(history.ranked(quick: "", now: now) == UnyriReactionHistory.defaults,
                "disabled quick reaction leaves seven default choices")

        history.recordSend("🦊", at: now.addingTimeInterval(-UnyriReactionHistory.window - 1))
        history.recordSend("🚀", at: now.addingTimeInterval(-20))
        history.recordSend("🐱", at: now.addingTimeInterval(-10))
        history.recordSend("🚀", at: now.addingTimeInterval(-5))
        require(history.ranked(quick: "❤️", now: now) == ["❤️", "🚀", "🐱", "👍", "😂", "😮", "😢"],
                "quick first, descending 30-day counts, ordered default fill")
        require(!history.ranked(quick: "👍", now: now).contains("🦊"), "use older than thirty days expires")
        require(Set(history.ranked(quick: "🚀", now: now)).count == 7, "quick/personal duplicates removed")
        require(Array(history.recent.prefix(2)) == ["🚀", "🐱"], "recents newest first without duplicate sends")

        var boundary = UnyriReactionHistory()
        boundary.recordSend("🚀", at: now.addingTimeInterval(-UnyriReactionHistory.window))
        require(boundary.ranked(quick: "👍", now: now)[1] == "🚀", "30-day lower boundary is inclusive")
        require(!boundary.ranked(quick: "👍", now: now.addingTimeInterval(1)).contains("🚀"),
                "ranking expires history even when no new send occurs")
        var tied = UnyriReactionHistory()
        tied.recordSend("🐱", at: now.addingTimeInterval(-2))
        tied.recordSend("🚀", at: now.addingTimeInterval(-1))
        require(Array(tied.ranked(quick: "👍", now: now).prefix(3)) == ["👍", "🚀", "🐱"],
                "equal counts prefer the latest use deterministically")
        let emoji = ["😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😎", "🤩"]
        for (index, value) in emoji.enumerated() { history.recordSend(value, at: now.addingTimeInterval(Double(index))) }
        require(history.recent.count == 16 && history.recent == Array(emoji.suffix(16).reversed()),
                "recents retain exactly the last sixteen unique sends")
        let unchanged = history
        history.recordSend("", at: now)
        require(history == unchanged, "empty send cannot pollute history")

        require(UnyriReactionSearch.normalize("ЁЖИК") == UnyriReactionSearch.normalize("ежик"),
                "case and Russian yo/e normalize identically")
        require(UnyriReactionSearch.matches(query: "КОТ", emoji: "🐱", keywords: ["кот кошка кіт cat face"]),
                "Russian uppercase prefix matches an emoji keyword")
        require(UnyriReactionSearch.matches(query: "еж", emoji: "🦔", keywords: ["ёжик"]),
                "e query matches yo keyword")
        require(UnyriReactionSearch.matches(query: "дяку", emoji: "🙏", keywords: ["дякую дяка thank you"]),
                "Ukrainian word prefix matches")
        require(UnyriReactionSearch.matches(query: "rock", emoji: "🚀", keywords: ["rocket"]),
                "English scalar-name prefix matches")
        require(!UnyriReactionSearch.matches(query: "ock", emoji: "🚀", keywords: ["rocket"]),
                "substring inside a word is not a prefix")
        require(UnyriReactionSearch.matches(query: "cat fa", emoji: "🐱", keywords: ["cat face"]),
                "all query words can prefix separate words")
        require(!UnyriReactionSearch.matches(query: "cat rocket", emoji: "🐱", keywords: ["cat face"]),
                "every query word must match")
        require(UnyriReactionSearch.matches(query: "🚀", emoji: "🚀", keywords: []), "pasted emoji finds itself")
        require(UnyriReactionSearch.matches(query: "❤", emoji: "❤️", keywords: []),
                "presentation selector does not break a pasted emoji")
        require(!UnyriReactionSearch.matches(query: "  ", emoji: "🚀", keywords: ["rocket"]),
                "empty search is handled by the category UI rather than all matches")

        let tones = UnyriReactionTones.variants(of: "👍")
        require(tones == ["👍", "👍🏻", "👍🏼", "👍🏽", "👍🏾", "👍🏿"], "default plus five supported skin tones")
        require(UnyriReactionTones.base(of: "👍🏽") == "👍", "tone canonicalizes to its base")
        require(UnyriReactionTones.variants(of: "🚀").isEmpty, "objects have no skin-tone popover")
        var toneHistory = UnyriReactionHistory()
        toneHistory.setTone("👍🏽", for: "👍")
        require(toneHistory.tone(for: "👍") == "👍🏽", "tone stored per base")
        require(toneHistory.ranked(quick: "👍", now: now).first == "👍🏽",
                "quick and default choices honor the remembered tone")
        toneHistory.setTone("👎🏿", for: "👍")
        require(toneHistory.tone(for: "👍") == "👍🏽", "a variant from a different base is rejected")
        require(toneHistory.tone(for: "👎") == "👎", "one base does not change another base")
        toneHistory.setTone("👍", for: "👍")
        require(toneHistory.tone(for: "👍") == "👍", "default tone can be restored")
        let roundTrip = try JSONDecoder().decode(UnyriReactionHistory.self, from: JSONEncoder().encode(toneHistory))
        require(roundTrip == toneHistory, "history round trips without app dependencies")
        var aggregate = UnyriReactionHistory()
        aggregate.setTone("👍🏽", for: "👍")
        aggregate.recordSend("🚀", at: now.addingTimeInterval(-3))
        aggregate.recordSend("👍🏻", at: now.addingTimeInterval(-2))
        aggregate.recordSend("👍🏿", at: now.addingTimeInterval(-1))
        let aggregated = aggregate.ranked(quick: "❤️", now: now)
        require(Array(aggregated.prefix(3)) == ["❤️", "👍🏿", "🚀"],
                "different tones aggregate into one base's rank with the last sent tone")
        require(Set(aggregated.map { UnyriReactionTones.base(of: $0) }).count == 7,
                "preferred variant, quick variant and base defaults cannot duplicate a pill slot")
        require(Array(aggregate.recent.prefix(2)) == ["👍🏿", "👍🏻"],
                "recent sends preserve their exact chosen tones")
        aggregate.recordSend("👍", at: now)
        require(aggregate.tone(for: "👍") == "👍", "sending the default restores default tone memory")

        let suite = "unyri-reaction-picker-contracts." + UUID().uuidString
        let preferences = UserDefaults(suiteName: suite)!
        defer { preferences.removePersistentDomain(forName: suite) }
        let accountA = UnyriReactionStore(account: "https://test.invalid|A", defaults: preferences)
        let accountB = UnyriReactionStore(account: "https://test.invalid|B", defaults: preferences)
        let otherServer = UnyriReactionStore(account: "https://other.invalid|A", defaults: preferences)
        accountA.recordSend("🚀", at: now)
        accountA.setTone("👍🏾", for: "👍")
        let reopened = UnyriReactionStore(account: "https://test.invalid|A", defaults: preferences)
        require(reopened.history.recent == ["🚀"] && reopened.preferred("👍") == "👍🏾",
                "send and tone choices persist on reopening")
        accountA.recordSend("👍🏻", at: now.addingTimeInterval(1))
        require(reopened.preferred("👍") == "👍🏻" && reopened.history.recent.first == "👍🏻",
                "a pasted or recent variant send persists that exact tone preference")
        require(accountB.history.recent.isEmpty && accountB.preferred("👍") == "👍",
                "account switch isolates recent usage and tone memory")
        require(otherServer.history.recent.isEmpty && otherServer.preferred("👍") == "👍",
                "same user ID on another server is isolated")
        accountB.recordSend("🔥", at: now)
        otherServer.recordSend("🐱", at: now)
        UnyriReactionStore.erase(account: "https://test.invalid|A", defaults: preferences)
        require(reopened.history.recent.isEmpty && reopened.preferred("👍") == "👍",
                "account erasure removes recent usage and remembered tones")
        require(accountB.history.recent == ["🔥"] && otherServer.history.recent == ["🐱"],
                "account erasure preserves other users and servers")

        require(CommandLine.arguments.count == 2, "pass the checked-in keyword resource path")
        let keywordData = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let annotations = try JSONDecoder().decode([String: [String: [String]]].self, from: keywordData)
        // Generated from Unicode CLDR 46 by tools/cldr/generate_reaction_keywords.py (third_party/cldr46).
        require(annotations.count >= 1_800, "CLDR resource covers every fully-qualified emoji up to Emoji 15.0")
        for (emoji, languages) in annotations {
            for language in ["ru", "uk", "en"] {
                require(languages[language]?.contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } == true,
                        "keyword resource includes " + language + " for " + emoji)
            }
        }
        let catalog = UnyriReactionCatalog.build(keywordData: keywordData)
        require(catalog.entries.count > 1_500, "offline catalog expands beyond the old curated set")
        let unnamed = catalog.entries.filter { $0.names["ru"] == nil || $0.names["uk"] == nil }.map(\.emoji)
        require(unnamed.isEmpty, "every catalog cell has a Russian and Ukrainian name: " + unnamed.prefix(12).joined(separator: " "))
        let keys = catalog.entries.map(\.id)
        require(Set(keys).count == keys.count, "catalog keys are unique")
        let canonicalKeys = catalog.entries.map { UnyriReactionSearch.baseKey($0.emoji) }
        require(Set(canonicalKeys).count == canonicalKeys.count, "VS16 aliases are not duplicate cells")
        require(Set(catalog.categories.map(\.id)) == Set(["people", "nature", "food", "activity", "travel", "objects", "symbols", "flags"]),
                "all existing standard categories remain")
        for category in catalog.categories {
            require(catalog.entries.contains { $0.categoryID == category.id }, "category is nonempty: " + category.id)
            require(!category.title.isEmpty && !category.titleRU.isEmpty && !category.titleUK.isEmpty,
                    "category has English, Russian and Ukrainian names: " + category.id)
        }
        for entry in catalog.entries {
            require(!entry.emoji.isEmpty && !entry.englishName.isEmpty, "every emoji has a glyph and spoken fallback name")
            require(catalog.categories.contains { $0.id == entry.categoryID }, "no orphan category: " + entry.emoji)
            let components = entry.emoji.unicodeScalars.filter { $0.value != 0xFE0F }
            if components.count == 1, let scalar = components.first {
                require(!scalar.properties.isEmojiModifier && !(0x1F1E6...0x1F1FF).contains(scalar.value)
                        && !(0x1F9B0...0x1F9B3).contains(scalar.value),
                        "modifiers, regional indicators and hair components never form standalone cells")
            }
            for scalar in entry.emoji.unicodeScalars {
                let age = scalar.properties.age
                require(age != nil && (age!.major < 15 || (age!.major == 15 && age!.minor == 0)),
                        "no scalar newer than Unicode 15.0: " + entry.emoji)
            }
        }
        require(UnyriReactionCatalog.supportsIOS17(Unicode.Scalar(0x1FAE8)!), "Unicode 15.0 shaking face is allowed")
        require(!UnyriReactionCatalog.supportsIOS17(Unicode.Scalar(0x1FAE9)!),
                "Unicode 16.0 face with bags under eyes is rejected, including on newer hosts")
        require(catalog.search("кот").contains { $0.emoji == "🐱" }, "bundled Russian cat keyword")
        require(catalog.search("ракета").contains { $0.emoji == "🚀" }, "bundled Russian rocket keyword")
        require(catalog.search("дякую").contains { $0.emoji == "🙏" }, "bundled Ukrainian thanks keyword")
        require(catalog.search("rocket").contains { $0.emoji == "🚀" }, "Unicode English name lookup")
        // Ranking: the emoji a person means comes first, not whatever a keyword prefix hits first.
        for (query, first) in [("огонь", "🔥"), ("ракета", "🚀"), ("rocket", "🚀"), ("сердце", "❤️"), ("вогонь", "🔥"),
                               ("кошка", "🐈"), ("спасибо", "🙏"), ("fire", "🔥"), ("thumbs up", "👍")] {
            require(catalog.search(query).first?.emoji == first,
                    query + " ranks " + first + " first, got " + catalog.search(query).prefix(5).map(\.emoji).joined())
        }
        require(UnyriReactionSearch.rank(queryWords: ["огонь"], names: ["огонь"], tokens: ["огонь", "пламя"]) == 0
                && UnyriReactionSearch.rank(queryWords: ["сердце"], names: ["красное сердце"], tokens: ["красное", "сердце"]) == 1
                && UnyriReactionSearch.rank(queryWords: ["кот"], names: ["морда кошки"], tokens: ["морда", "кошки", "кот"]) == 2
                && UnyriReactionSearch.rank(queryWords: ["рак"], names: ["ракета"], tokens: ["ракета"]) == 3
                && UnyriReactionSearch.rank(queryWords: ["пла"], names: ["огонь"], tokens: ["огонь", "пламя"]) == 4,
                "rank order: whole name, name word, keyword, name prefix, keyword prefix")
        require(catalog.search("животн").contains { $0.emoji == "🐱" }, "existing Russian category alias remains searchable")
        require(catalog.search("food").contains { $0.emoji == "🍎" }, "existing English category alias remains searchable")
        require(catalog.search("🐱").contains { $0.emoji == "🐱" }, "catalog pasted emoji lookup")
        require(catalog.search("👍🏽").contains { $0.emoji == "👍🏽" }, "pasted skin-tone variant returns that exact variant")
        require(catalog.search("zzz-no-such-reaction-123").isEmpty, "unmatched query returns the empty result state")
        print("PASS: reaction ranking, recents, search, tone memory, account isolation and \(catalog.entries.count)-emoji catalog")
    }
}
