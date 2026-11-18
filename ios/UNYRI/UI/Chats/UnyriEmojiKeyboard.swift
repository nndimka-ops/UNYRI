import SwiftUI
import UIKit

// MARK: - Emoji keyboard (Telegram-like panel in the keyboard's place)
//
// The composer keeps its text field; tapping 🙂 swaps the field's `inputView` for this
// keyboard-height panel and back (the chat and the composer never jump, like the
// system emoji keyboard). Emoji are inserted at the caret through UIKeyInput, so the
// SwiftUI binding, undo and marked text stay with the real text view.

/// Last docked system keyboard height, so the emoji panel matches it exactly.
enum UnyriKeyboardMetrics {
    private static let key = "unyri-keyboard-height-v1"
    static var lastHeight: CGFloat {
        let stored = CGFloat(UserDefaults.standard.double(forKey: key))
        return stored > 200 ? stored : 336
    }
    private static var observer: NSObjectProtocol?
    /// Learns the docked keyboard height (never the emoji panel's own frame).
    @MainActor static func startObserving() {
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(forName: UIResponder.keyboardDidShowNotification, object: nil, queue: .main) { note in
            guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            MainActor.assumeIsolated { if !UnyriEmojiInput.isShowingEmoji { record(frame.height) } }
        }
    }
    static func record(_ height: CGFloat) {
        guard height > 200, height < 600, abs(height - lastHeight) > 0.5 else { return }
        UserDefaults.standard.set(Double(height), forKey: key)
    }
}

/// Recently used emoji, shared by the composer panel and the reactions picker.
enum UnyriEmojiRecents {
    private static let key = "emoji-recents-v1"
    static let limit = 32
    static var all: [String] { UserDefaults.standard.stringArray(forKey: key) ?? [] }
    static func use(_ emoji: String) {
        var list = all.filter { $0 != emoji }
        list.insert(emoji, at: 0)
        UserDefaults.standard.set(Array(list.prefix(limit)), forKey: key)
    }
}

struct UnyriEmojiSection: Identifiable {
    let id: String
    let title: String
    let titleRU: String
    let titleUK: String
    let symbol: String
    let emojis: [String]
    var localizedTitle: String {
        switch UnyriL10n.language { case "ru": return titleRU; case "uk": return titleUK; default: return title }
    }
}

/// Emoji up to Unicode 14 (iOS 15.4+), in the system keyboard's group order.
let unyriEmojiKeyboardSections: [UnyriEmojiSection] = [
    UnyriEmojiSection(id: "people", title: "Smileys & People", titleRU: "Смайлики и люди", titleUK: "Смайлики та люди", symbol: "face.smiling", emojis: """
😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😮‍💨 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🫣 🤗 🫡 🤔 🫢 🤭 🤫 🤥 😶 😶‍🌫️ 😐 😑 😬 🫠 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 😵‍💫 🫥 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾 \
👋 🤚 🖐️ ✋ 🖖 🫱 🫲 🫳 🫴 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🫶 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 🦻 👃 🧠 🫀 🫁 🦷 🦴 👀 👁️ 👅 👄 🫦 💋 \
👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 🧑‍⚕️ 🧑‍🎓 🧑‍🏫 🧑‍⚖️ 🧑‍🌾 🧑‍🍳 🧑‍🔧 🧑‍🏭 🧑‍💼 🧑‍🔬 🧑‍💻 🧑‍🎤 🧑‍🎨 🧑‍✈️ 🧑‍🚀 🧑‍🚒 👮 🕵️ 💂 🥷 👷 🫅 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🫃 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 🧌 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 🕴️ 👯 🧖 🧗 🤺 🏇 ⛷️ 🏂 🏌️ 🏄 🚣 🏊 ⛹️ 🏋️ 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘 🛀 🛌 🧑‍🤝‍🧑 👭 👫 👬 💏 💑 👪 🗣️ 👤 👥 🫂 👣 \
🧳 🌂 ☂️ 🧵 🧶 👓 🕶️ 🥽 🥼 🦺 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 🥻 🩱 🩲 🩳 👙 👚 👛 👜 👝 🎒 🩴 👞 👟 🥾 🥿 👠 👡 🩰 👢 👑 👒 🎩 🎓 🧢 🪖 ⛑️ 💄 💍 💼
""".unyriEmojiList),
    UnyriEmojiSection(id: "nature", title: "Animals & Nature", titleRU: "Животные и природа", titleUK: "Тварини та природа", symbol: "pawprint", emojis: """
🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🪸 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🦭 🐊 🐅 🐆 🦓 🦍 🦧 🦣 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐕‍🦺 🐈 🐈‍⬛ 🪶 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾 🐉 🐲 \
🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🪺 🪹 🍄 🐚 🪨 🌾 💐 🌷 🌹 🥀 🪷 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 🫧 ☔ ☂️ 🌊 🌫️
""".unyriEmojiList),
    UnyriEmojiSection(id: "food", title: "Food & Drink", titleRU: "Еда и напитки", titleUK: "Їжа та напої", symbol: "fork.knife", emojis: """
🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🫘 🍯 🥛 🍼 🫖 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🫗 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽️ 🥣 🥡 🥢 🧂
""".unyriEmojiList),
    UnyriEmojiSection(id: "activity", title: "Activity", titleRU: "Активность", titleUK: "Активність", symbol: "soccerball", emojis: """
⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🪂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🎉 🎊 🎈 🎁 🎀 🪅 🪩
""".unyriEmojiList),
    UnyriEmojiSection(id: "travel", title: "Travel & Places", titleRU: "Путешествия", titleUK: "Подорожі", symbol: "car", emojis: """
🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🛞 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🛟 🪝 ⛽ 🚧 🚦 🚥 🚏 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🛝 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🛖 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🛕 🕋 ⛩️ 🛤️ 🛣️ 🗾 🎑 🏞️ 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁
""".unyriEmojiList),
    UnyriEmojiSection(id: "objects", title: "Objects", titleRU: "Предметы", titleUK: "Предмети", symbol: "lightbulb", emojis: """
⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🖲️ 🕹️ 🗜️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🎚️ 🎛️ 🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🪫 🔌 💡 🔦 🕯️ 🪔 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🪜 🧰 🪛 🔧 🔨 ⚒️ 🛠️ ⛏️ 🪚 🔩 ⚙️ 🪤 🧱 ⛓️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🪦 ⚱️ 🏺 🔮 📿 🧿 🪬 💈 ⚗️ 🔭 🔬 🕳️ 🩹 🩺 🩻 🩼 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🪠 🧺 🧻 🚽 🚰 🚿 🛁 🛀 🧼 🪥 🪒 🧽 🪣 🧴 🛎️ 🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🛌 🧸 🪆 🖼️ 🪞 🪟 🛍️ 🛒 🎁 🎈 🎏 🎀 🪄 🪅 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯 📜 📃 📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓
""".unyriEmojiList),
    UnyriEmojiSection(id: "symbols", title: "Symbols", titleRU: "Символы", titleUK: "Символи", symbol: "heart", emojis: """
❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️ ✴️ 🆚 💮 🉐 ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 💤 🏧 🚾 ♿ 🅿️ 🛗 🈳 🈂️ 🛂 🛃 🛄 🛅 🚹 🚺 🚼 ⚧️ 🚻 🚮 🎦 📶 🈁 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶 ➕ ➖ ➗ ✖️ 🟰 ♾️ 💲 💱 ™️ ©️ ®️ 〰️ ➰ ➿ 🔚 🔙 🔛 🔝 🔜 ✔️ ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🟫 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 👁️‍🗨️ 💬 💭 🗯️ ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛
""".unyriEmojiList),
    UnyriEmojiSection(id: "flags", title: "Flags", titleRU: "Флаги", titleUK: "Прапори", symbol: "flag", emojis: """
🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇺🇦 🇺🇳 🇪🇺 🇦🇫 🇦🇱 🇩🇿 🇦🇩 🇦🇴 🇦🇷 🇦🇲 🇦🇺 🇦🇹 🇦🇿 🇧🇸 🇧🇭 🇧🇩 🇧🇾 🇧🇪 🇧🇿 🇧🇯 🇧🇹 🇧🇴 🇧🇦 🇧🇼 🇧🇷 🇧🇳 🇧🇬 🇧🇫 🇧🇮 🇰🇭 🇨🇲 🇨🇦 🇨🇻 🇨🇫 🇹🇩 🇨🇱 🇨🇳 🇨🇴 🇰🇲 🇨🇬 🇨🇩 🇨🇷 🇨🇮 🇭🇷 🇨🇺 🇨🇾 🇨🇿 🇩🇰 🇩🇯 🇩🇲 🇩🇴 🇪🇨 🇪🇬 🇸🇻 🇬🇶 🇪🇷 🇪🇪 🇸🇿 🇪🇹 🇫🇯 🇫🇮 🇫🇷 🇬🇦 🇬🇲 🇬🇪 🇩🇪 🇬🇭 🇬🇷 🇬🇩 🇬🇹 🇬🇳 🇬🇼 🇬🇾 🇭🇹 🇭🇳 🇭🇰 🇭🇺 🇮🇸 🇮🇳 🇮🇩 🇮🇷 🇮🇶 🇮🇪 🇮🇱 🇮🇹 🇯🇲 🇯🇵 🇯🇴 🇰🇿 🇰🇪 🇰🇮 🇽🇰 🇰🇼 🇰🇬 🇱🇦 🇱🇻 🇱🇧 🇱🇸 🇱🇷 🇱🇾 🇱🇮 🇱🇹 🇱🇺 🇲🇴 🇲🇬 🇲🇼 🇲🇾 🇲🇻 🇲🇱 🇲🇹 🇲🇭 🇲🇷 🇲🇺 🇲🇽 🇫🇲 🇲🇩 🇲🇨 🇲🇳 🇲🇪 🇲🇦 🇲🇿 🇲🇲 🇳🇦 🇳🇷 🇳🇵 🇳🇱 🇳🇿 🇳🇮 🇳🇪 🇳🇬 🇰🇵 🇲🇰 🇳🇴 🇴🇲 🇵🇰 🇵🇼 🇵🇸 🇵🇦 🇵🇬 🇵🇾 🇵🇪 🇵🇭 🇵🇱 🇵🇹 🇵🇷 🇶🇦 🇷🇴 🇷🇺 🇷🇼 🇰🇳 🇱🇨 🇻🇨 🇼🇸 🇸🇲 🇸🇹 🇸🇦 🇸🇳 🇷🇸 🇸🇨 🇸🇱 🇸🇬 🇸🇰 🇸🇮 🇸🇧 🇸🇴 🇿🇦 🇰🇷 🇸🇸 🇪🇸 🇱🇰 🇸🇩 🇸🇷 🇸🇪 🇨🇭 🇸🇾 🇹🇼 🇹🇯 🇹🇿 🇹🇭 🇹🇱 🇹🇬 🇹🇴 🇹🇹 🇹🇳 🇹🇷 🇹🇲 🇹🇻 🇺🇬 🇦🇪 🇬🇧 🇺🇸 🇺🇾 🇺🇿 🇻🇺 🇻🇦 🇻🇪 🇻🇳 🇾🇪 🇿🇲 🇿🇼
""".unyriEmojiList),
]

private extension String {
    var unyriEmojiList: [String] {
        split(whereSeparator: { $0 == " " || $0 == "\n" }).map(String.init).filter { !$0.isEmpty && $0 != "\\" }
    }
}

/// The panel itself (hosted in a keyboard-style UIInputView).
struct UnyriEmojiKeyboard: View {
    let insert: (String) -> Void
    let backspace: () -> Void
    @State private var recents = UnyriEmojiRecents.all
    @State private var selected = "recent"
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 8)

    private var sections: [UnyriEmojiSection] {
        let recent = UnyriEmojiSection(id: "recent", title: "Recently used", titleRU: "Недавние", titleUK: "Нещодавні", symbol: "clock", emojis: recents)
        return (recents.isEmpty ? [] : [recent]) + unyriEmojiKeyboardSections
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(alignment: .leading, spacing: 0, pinnedViews: []) {
                        ForEach(sections) { section in
                            Text(section.localizedTitle)
                                .font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                                .padding(.horizontal, 14).padding(.top, 10).padding(.bottom, 4)
                                .id(section.id)
                            LazyVGrid(columns: columns, spacing: 0) {
                                ForEach(Array(section.emojis.enumerated()), id: \.offset) { _, emoji in
                                    Button { choose(emoji) } label: {
                                        Text(emoji).font(.system(size: 31))
                                            .frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle())
                                    }
                                    .buttonStyle(UnyriEmojiKeyStyle())
                                }
                            }
                            .padding(.horizontal, 6)
                        }
                    }
                    .padding(.bottom, 8)
                }
                .accessibilityIdentifier("emoji-keyboard-grid")
                HStack(spacing: 0) {
                    ForEach(sections) { section in
                        Button {
                            UnyriHaptics.selection(); selected = section.id
                            proxy.scrollTo(section.id, anchor: .top)
                        } label: {
                            Image(systemName: section.symbol).font(.system(size: 17, weight: .medium))
                                .foregroundStyle(selected == section.id ? Color.primary : Color.secondary)
                                .frame(maxWidth: .infinity, minHeight: 38)
                                .background(selected == section.id ? Color.primary.opacity(0.12) : .clear, in: Capsule())
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(Text(section.localizedTitle))
                    }
                    UnyriBackspaceKey(action: backspace)
                }
                .padding(.horizontal, 6).padding(.top, 4).padding(.bottom, 2)
            }
        }
        .accessibilityIdentifier("emoji-keyboard")
    }

    private func choose(_ emoji: String) {
        UIDevice.current.playInputClick()
        insert(emoji)
        UnyriEmojiRecents.use(emoji)
    }
}

private struct UnyriEmojiKeyStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 1.25 : 1)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

/// Backspace with auto-repeat while held, like the system key.
private struct UnyriBackspaceKey: View {
    let action: () -> Void
    @State private var repeatTask: Task<Void, Never>?
    var body: some View {
        Image(systemName: "delete.left").font(.system(size: 19, weight: .medium))
            .foregroundStyle(Color.primary)
            .frame(width: 52, height: 38).contentShape(Rectangle())
            .onLongPressGesture(minimumDuration: 0.35, maximumDistance: 30, perform: {}, onPressingChanged: { pressing in
                if pressing {
                    UIDevice.current.playInputClick(); action()
                    repeatTask = Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 450_000_000)
                        while !Task.isCancelled {
                            UIDevice.current.playInputClick(); action()
                            try? await Task.sleep(nanoseconds: 90_000_000)
                        }
                    }
                } else { repeatTask?.cancel(); repeatTask = nil }
            })
            .accessibilityElement().accessibilityLabel(Text("Delete")).accessibilityAddTraits(.isButton)
            .accessibilityAction { action() }
            .accessibilityIdentifier("emoji-keyboard-backspace")
    }
}

/// Keyboard-style container: system keyboard background, input clicks, fixed height.
final class UnyriEmojiInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
    private var host: UIHostingController<UnyriEmojiKeyboard>?

    init(insert: @escaping (String) -> Void, backspace: @escaping () -> Void) {
        let width = UIScreen.main.bounds.width
        super.init(frame: CGRect(x: 0, y: 0, width: width, height: UnyriKeyboardMetrics.lastHeight), inputViewStyle: .keyboard)
        allowsSelfSizing = true
        let host = UIHostingController(rootView: UnyriEmojiKeyboard(insert: insert, backspace: backspace))
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: trailingAnchor),
            host.view.topAnchor.constraint(equalTo: topAnchor),
            host.view.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),
        ])
        self.host = host
    }
    required init?(coder: NSCoder) { nil }

    override var intrinsicContentSize: CGSize {
        // The stored height already includes the home-indicator area, like the keyboard's frame.
        CGSize(width: UIView.noIntrinsicMetric, height: UnyriKeyboardMetrics.lastHeight - (window?.safeAreaInsets.bottom ?? 0))
    }
}

/// Finds the focused composer text view and swaps its keyboard.
fileprivate final class UnyriResponderCapture: NSObject { weak var responder: UIResponder? }

@MainActor enum UnyriEmojiInput {
    private static weak var emojiView: UnyriEmojiInputView?

    static var currentEditor: (UIResponder & UITextInput)? {
        let capture = UnyriResponderCapture()
        UIApplication.shared.sendAction(#selector(UIResponder.unyriCaptureFirstResponder(_:)), to: nil, from: capture, for: nil)
        return capture.responder as? (UIResponder & UITextInput)
    }

    /// Shows the emoji panel (true) or the system keyboard (false) for the focused field.
    private static weak var lastEditor: UIResponder?
    /// The field lost focus with the panel up: restore the keyboard for next time.
    static func resetAfterBlur() {
        (lastEditor as? UITextView)?.inputView = nil
        (lastEditor as? UITextField)?.inputView = nil
    }

    @discardableResult static func setEmoji(_ on: Bool) -> Bool {
        guard let editor = currentEditor else { return false }
        lastEditor = editor
        if let textView = editor as? UITextView { textView.inputView = on ? makeView(for: editor) : nil; textView.reloadInputViews() }
        else if let field = editor as? UITextField { field.inputView = on ? makeView(for: editor) : nil; field.reloadInputViews() }
        else { return false }
        return true
    }

    private static func makeView(for editor: UIResponder & UITextInput) -> UnyriEmojiInputView {
        let view = UnyriEmojiInputView(insert: { emoji in
            (currentEditor as? UIKeyInput)?.insertText(emoji)
        }, backspace: {
            (currentEditor as? UIKeyInput)?.deleteBackward()
        })
        emojiView = view
        return view
    }

    static var isShowingEmoji: Bool {
        guard let editor = currentEditor else { return false }
        return (editor as? UITextView)?.inputView is UnyriEmojiInputView || (editor as? UITextField)?.inputView is UnyriEmojiInputView
    }
}

extension UIResponder {
    @objc fileprivate func unyriCaptureFirstResponder(_ sender: Any) {
        (sender as? UnyriResponderCapture)?.responder = self
    }
}
