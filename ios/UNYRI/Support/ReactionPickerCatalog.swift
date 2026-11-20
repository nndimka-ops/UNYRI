import Foundation

struct UnyriReactionCategory: Identifiable, Sendable {
    let id: String
    let title: String
    let titleRU: String
    let titleUK: String
    let symbol: String

    func localizedTitle(language: String) -> String {
        switch language { case "ru": return titleRU; case "uk": return titleUK; default: return title }
    }

    /// Names from the original 484-entry reaction catalog remain searchable when
    /// the eight standard groups combine its nine narrower sections.
    var aliases: [String] {
        switch id {
        case "people": return ["Faces", "Лица", "Обличчя", "Gestures", "Жесты", "Жести"]
        case "nature": return ["Cats and animals", "Животные", "Тварини", "Nature", "Природа"]
        case "food": return ["Food", "Еда", "Їжа"]
        case "activity": return ["Activities", "Занятия", "Заняття"]
        case "travel", "objects": return ["Travel and objects", "Предметы", "Предмети"]
        case "symbols": return ["Hearts", "Сердца", "Серця", "Symbols", "Символы", "Символи"]
        default: return []
        }
    }
}

struct UnyriReactionEmoji: Identifiable, Sendable {
    let emoji: String
    let categoryID: String
    let englishName: String
    let keywords: [String]
    let names: [String: String]
    let searchTokens: [String]
    var id: String { emoji }

    func localizedName(language: String) -> String { names[language] ?? englishName }
}

/// The resource is deliberately independent of catalog generation. CLDR import can
/// replace this JSON dictionary with the same schema without changing search or UI.
/// First string in each language is the accessible name; remaining strings are aliases.
struct UnyriReactionCatalog: Sendable {
    let entries: [UnyriReactionEmoji]
    let categories: [UnyriReactionCategory]
    let entriesByCategory: [String: [UnyriReactionEmoji]]

    private static let prepared: Task<UnyriReactionCatalog, Never> = Task.detached(priority: .userInitiated) {
        let data = Bundle.main.url(forResource: "ReactionEmojiKeywords", withExtension: "json")
            .flatMap { try? Data(contentsOf: $0) }
        return build(keywordData: data)
    }

    static func load() async -> UnyriReactionCatalog { await prepared.value }

    /// Pure entry point for the portable contracts. Production calls only load(),
    /// whose detached task enumerates Unicode exactly once per process, never on main.
    static func build(keywordData: Data? = nil) -> UnyriReactionCatalog {
        let annotations = keywordData.flatMap { try? JSONDecoder().decode([String: [String: [String]]].self, from: $0) } ?? [:]
        let groups = categoryDefinitions
        var result: [UnyriReactionEmoji] = []
        var keys: Set<String> = []
        func append(_ emoji: String, category: String, name: String? = nil) {
            guard emoji.unicodeScalars.allSatisfy(supportsIOS17), keys.insert(UnyriReactionSearch.baseKey(emoji)).inserted else { return }
            let labels = annotations[emoji] ?? annotations[UnyriReactionSearch.baseKey(emoji)] ?? [:]
            let english = name ?? emoji.unicodeScalars.filter { ![0x200D, 0xFE0F, 0x20E3].contains($0.value) }
                .compactMap { $0.properties.name?.lowercased() }.joined(separator: " ")
            var names: [String: String] = [:]
            for (language, values) in labels { if let first = values.first { names[language] = first } }
            let group = groups.first { $0.id == category }
            let groupNames = group.map { [$0.title, $0.titleRU, $0.titleUK] } ?? []
            let words = [english] + labels.values.flatMap { $0 } + groupNames
            result.append(UnyriReactionEmoji(emoji: emoji, categoryID: category, englishName: english,
                                             keywords: words, names: names,
                                             searchTokens: Array(Set(words.flatMap(UnyriReactionSearch.words)))))
        }

        // Enumerate property-backed emoji rather than relying on a finite curated
        // scalar list. Text-default emoji receive VS16 so iOS uses the color glyph.
        for value in UInt32(0)...UInt32(0x10FFFF) {
            guard let scalar = Unicode.Scalar(value), scalar.properties.isEmoji, supportsIOS17(scalar),
                  !scalar.properties.isEmojiModifier, !(0x1F1E6...0x1F1FF).contains(value),
                  !(0x1F9B0...0x1F9B3).contains(value),
                  ![0x23, 0x2A].contains(value), !(0x30...0x39).contains(value) else { continue }
            let emoji = String(scalar) + (scalar.properties.isEmojiPresentation ? "" : "\u{FE0F}")
            append(emoji, category: categoryID(for: value))
        }
        for emoji in commonSequences.split(separator: " ").map(String.init) {
            let category = emoji.hasPrefix("🏳") || emoji.hasPrefix("🏴") ? "flags" :
                (emoji.hasPrefix("🐻") || emoji.hasPrefix("🐈") || emoji.hasPrefix("🐕") ? "nature" :
                    (emoji.hasPrefix("❤") || emoji.hasPrefix("👁") ? "symbols" : "people"))
            append(emoji, category: category)
        }
        // Gender-neutral, woman and man professions introduced by Emoji 12.1 or
        // earlier; no Emoji 15.1 direction-facing combinations are synthesized.
        let professions = ["⚕️", "🎓", "🏫", "⚖️", "🌾", "🍳", "🔧", "🏭", "💼", "🔬", "💻", "🎤", "🎨", "✈️", "🚀", "🚒"]
        for person in ["🧑", "👩", "👨"] {
            for profession in professions { append(person + "\u{200D}" + profession, category: "people") }
            for hair in ["🦰", "🦱", "🦳", "🦲"] { append(person + "\u{200D}" + hair, category: "people") }
            for aid in ["🦯", "🦼", "🦽"] { append(person + "\u{200D}" + aid, category: "people") }
        }
        let gendered = "🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 🕵️ 💂 👷 👳 🤵 👰 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🧍 🧎 🏃 👯 🧖 🧗 🏌️ 🏄 🚣 🏊 ⛹️ 🏋️ 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘".split(separator: " ")
        for base in gendered {
            for gender in ["♀️", "♂️"] { append(String(base) + "\u{200D}" + gender, category: "people") }
        }
        for key in ["#", "*"] + (0...9).map(String.init) { append(key + "\u{FE0F}\u{20E3}", category: "symbols", name: "keycap " + key) }
        let english = Locale(identifier: "en")
        for country in countryCodes.split(separator: " ") {
            let flag = String(String.UnicodeScalarView(country.unicodeScalars.compactMap { Unicode.Scalar(0x1F1E6 + $0.value - 0x41) }))
            append(flag, category: "flags", name: "flag " + (english.localizedString(forRegionCode: String(country)) ?? String(country)).lowercased())
        }
        // Stable category grouping and scalar order, independent of dictionary order.
        let order = Dictionary(uniqueKeysWithValues: groups.enumerated().map { ($0.element.id, $0.offset) })
        result.sort {
            let a = order[$0.categoryID] ?? 0, b = order[$1.categoryID] ?? 0
            if a != b { return a < b }
            return $0.emoji.unicodeScalars.lexicographicallyPrecedes($1.emoji.unicodeScalars) { $0.value < $1.value }
        }
        return UnyriReactionCatalog(entries: result, categories: groups,
                                    entriesByCategory: Dictionary(grouping: result, by: \.categoryID))
    }

    func search(_ query: String) -> [UnyriReactionEmoji] {
        let raw = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return [] }
        let queryWords = UnyriReactionSearch.words(raw)
        // An exact old section title preserves that category shortcut. Partial
        // words still match individual names: "cat" does not match every plant
        // merely because the former animal section was "Cats and animals".
        let normalized = UnyriReactionSearch.normalize(raw)
        let legacyGroups = Set(categories.filter { category in
            category.aliases.contains { UnyriReactionSearch.normalize($0) == normalized }
        }.map(\.id))
        let pastedKey = UnyriReactionSearch.baseKey(raw)
        let untonedKey = UnyriReactionSearch.baseKey(UnyriReactionTones.base(of: raw))
        // (rank, default reaction position, name length, catalog position): see UnyriReactionSearch.rank.
        var found: [(order: (Int, Int, Int, Int), entry: UnyriReactionEmoji)] = []
        for (index, entry) in entries.enumerated() {
            if Task.isCancelled { return [] }
            let key = UnyriReactionSearch.baseKey(entry.emoji)
            let names = Array(entry.names.values) + [entry.englishName]
            let favourite = UnyriReactionHistory.defaults.firstIndex(of: entry.emoji) ?? UnyriReactionHistory.defaults.count
            let length = names.map { UnyriReactionSearch.words($0).count }.min() ?? 0
            if pastedKey == key {
                found.append(((-1, 0, 0, index), entry))
            } else if untonedKey == key, UnyriReactionTones.variants(of: entry.emoji).contains(raw) {
                found.append(((-1, 0, 0, index), UnyriReactionEmoji(emoji: raw, categoryID: entry.categoryID, englishName: entry.englishName,
                                                keywords: entry.keywords, names: entry.names, searchTokens: entry.searchTokens)))
            } else if UnyriReactionSearch.matches(queryWords: queryWords, tokens: entry.searchTokens) {
                let rank = UnyriReactionSearch.rank(queryWords: queryWords, names: names, tokens: entry.searchTokens)
                found.append(((rank, favourite, length, index), entry))
            } else if legacyGroups.contains(entry.categoryID) {
                found.append(((5, favourite, length, index), entry))
            }
        }
        return found.sorted { $0.order < $1.order }.map(\.entry)
    }

    static func supportsIOS17(_ scalar: Unicode.Scalar) -> Bool {
        guard let age = scalar.properties.age else { return false }
        return age.major < 15 || (age.major == 15 && age.minor == 0)
    }

    /// Standard groups by Unicode block/range; narrow exceptions precede blocks.
    private static func categoryID(for value: UInt32) -> String {
        switch value {
        case 0x1F3F3...0x1F3F4, 0x1F3C1, 0x1F6A9: return "flags"
        case 0x1F600...0x1F64F, 0x1F466...0x1F487, 0x1F440...0x1F465,
             0x1F48B, 0x1F48F...0x1F491, 0x1F4AA, 0x1F590...0x1F596,
             0x1F90C...0x1F93A, 0x1F970...0x1F97F, 0x1F9B0...0x1F9BF,
             0x1F9D0...0x1F9DF, 0x1FAE0...0x1FAEF, 0x1FAF0...0x1FAFF,
             0x261D, 0x270A...0x270D: return "people"
        case 0x1F300...0x1F302, 0x1F30C...0x1F321, 0x1F330...0x1F343, 0x1F400...0x1F43F,
             0x1F980...0x1F9AF, 0x1FAB0...0x1FABF, 0x1F54A,
             0x1F525, 0x1F4A7...0x1F4A8,
             0x2600...0x2604, 0x2614, 0x2744, 0x26C4: return "nature"
        case 0x1F344...0x1F37F, 0x1F950...0x1F96F, 0x1F9C0,
             0x1FAD0...0x1FADF, 0x2615: return "food"
        case 0x1F380...0x1F3AF, 0x1F3B1...0x1F3C5, 0x1F3C6...0x1F3D3,
             0x1F3F8...0x1F3FF, 0x1F93C...0x1F94F, 0x1F9E9,
             0x1FA70...0x1FA74, 0x26BD...0x26BE, 0x26F3, 0x26F8: return "activity"
        case 0x1F680...0x1F6FF, 0x1F3D4...0x1F3F0, 0x1F5FA...0x1F5FF,
             0x1F30B, 0x1F303...0x1F309, 0x26EA, 0x26F0...0x26F2,
             0x26F4...0x26F5, 0x26FA, 0x26FD, 0x2708: return "travel"
        case 0x1F48C...0x1F48E, 0x1F4B0...0x1F4FD, 0x1F56F...0x1F579,
             0x1F5A5...0x1F5F3, 0x1F9E0...0x1F9FF, 0x1FA78...0x1FA7C,
             0x1FA80...0x1FAAF, 0x1FAC0...0x1FACF, 0x231A...0x231B,
             0x2328, 0x23F0...0x23F3, 0x260E, 0x2692...0x2697,
             0x2699, 0x26B0...0x26B1, 0x2702, 0x2709, 0x270F...0x2712: return "objects"
        default: return "symbols"
        }
    }

    static let categoryDefinitions = [
        UnyriReactionCategory(id: "people", title: "Smileys & People", titleRU: "Смайлики и люди", titleUK: "Смайлики та люди", symbol: "face.smiling"),
        UnyriReactionCategory(id: "nature", title: "Animals & Nature", titleRU: "Животные и природа", titleUK: "Тварини та природа", symbol: "pawprint"),
        UnyriReactionCategory(id: "food", title: "Food & Drink", titleRU: "Еда и напитки", titleUK: "Їжа та напої", symbol: "fork.knife"),
        UnyriReactionCategory(id: "activity", title: "Activity", titleRU: "Активность", titleUK: "Активність", symbol: "soccerball"),
        UnyriReactionCategory(id: "travel", title: "Travel & Places", titleRU: "Путешествия", titleUK: "Подорожі", symbol: "car"),
        UnyriReactionCategory(id: "objects", title: "Objects", titleRU: "Предметы", titleUK: "Предмети", symbol: "lightbulb"),
        UnyriReactionCategory(id: "symbols", title: "Symbols", titleRU: "Символы", titleUK: "Символи", symbol: "heart"),
        UnyriReactionCategory(id: "flags", title: "Flags", titleRU: "Флаги", titleUK: "Прапори", symbol: "flag")
    ]

    // ISO 3166-1 alpha-2, plus established emoji region flags EU/UN/XK/AC/CP/DG/EA/IC/TA.
    private static let countryCodes = "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW EU UN XK AC CP DG EA IC TA"

    // Known iOS 17 ZWJ forms only. Full CLDR order/annotations are the follow-up.
    // Untoned 👩‍🤝‍👩 👨‍🤝‍👨 👩‍🤝‍👨 are not RGI (only their toned forms are); the RGI cells are the
    // single scalars 👭 👬 👫, which the property enumeration already adds.
    private static let commonSequences = "😮‍💨 😵‍💫 😶‍🌫️ ❤️‍🔥 ❤️‍🩹 🐻‍❄️ 🐈‍⬛ 🐕‍🦺 👁️‍🗨️ 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🧑‍🤝‍🧑 👨‍👩‍👦 👨‍👩‍👧 👨‍👩‍👧‍👦 👨‍👩‍👦‍👦 👨‍👩‍👧‍👧 👨‍👨‍👦 👨‍👨‍👧 👨‍👨‍👧‍👦 👨‍👨‍👦‍👦 👨‍👨‍👧‍👧 👩‍👩‍👦 👩‍👩‍👧 👩‍👩‍👧‍👦 👩‍👩‍👦‍👦 👩‍👩‍👧‍👧 👨‍👦 👨‍👦‍👦 👨‍👧 👨‍👧‍👦 👨‍👧‍👧 👩‍👦 👩‍👦‍👦 👩‍👧 👩‍👧‍👦 👩‍👧‍👧 👩‍❤️‍👨 👨‍❤️‍👨 👩‍❤️‍👩 👩‍❤️‍💋‍👨 👨‍❤️‍💋‍👨 👩‍❤️‍💋‍👩 🧑‍🎄"
}
