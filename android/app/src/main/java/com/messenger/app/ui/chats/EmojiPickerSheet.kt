package com.messenger.app.ui.chats

import com.messenger.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext

internal data class EmojiCategory(val id: String, val name: String, val ru: String, val uk: String,
    val label: Int, val emojis: List<String>, val keywords: String = "$name $ru $uk")

internal fun recentEmojiCategory(emojis: List<String>) =
    EmojiCategory("recent", "Recently used", "Недавние", "Нещодавні", R.string.emoji_recent, emojis)

// Catalogue and order from the read-only iOS UnyriEmojiKeyboard.swift (Unicode 14).
internal val emojiCategories = listOf(
    EmojiCategory("people", "Smileys & People", "Смайлики и люди", "Смайлики та люди", R.string.emoji_people, "😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😮‍💨 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🫣 🤗 🫡 🤔 🫢 🤭 🤫 🤥 😶 😶‍🌫️ 😐 😑 😬 🫠 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 😵‍💫 🫥 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 👹 👺 🤡 💩 👻 💀 ☠️ 👽 👾 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀 😿 😾 👋 🤚 🖐️ ✋ 🖖 🫱 🫲 🫳 🫴 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 🖕 👇 ☝️ 🫵 👍 👎 ✊ 👊 🤛 🤜 👏 🫶 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🦿 🦵 🦶 👂 🦻 👃 🧠 🫀 🫁 🦷 🦴 👀 👁️ 👅 👄 🫦 💋 👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 🧑‍⚕️ 🧑‍🎓 🧑‍🏫 🧑‍⚖️ 🧑‍🌾 🧑‍🍳 🧑‍🔧 🧑‍🏭 🧑‍💼 🧑‍🔬 🧑‍💻 🧑‍🎤 🧑‍🎨 🧑‍✈️ 🧑‍🚀 🧑‍🚒 👮 🕵️ 💂 🥷 👷 🫅 🤴 👸 👳 👲 🧕 🤵 👰 🤰 🫃 🤱 👼 🎅 🤶 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 🧌 💆 💇 🚶 🧍 🧎 🏃 💃 🕺 🕴️ 👯 🧖 🧗 🤺 🏇 ⛷️ 🏂 🏌️ 🏄 🚣 🏊 ⛹️ 🏋️ 🚴 🚵 🤸 🤼 🤽 🤾 🤹 🧘 🛀 🛌 🧑‍🤝‍🧑 👭 👫 👬 💏 💑 👪 🗣️ 👤 👥 🫂 👣 🧳 🌂 ☂️ 🧵 🧶 👓 🕶️ 🥽 🥼 🦺 👔 👕 👖 🧣 🧤 🧥 🧦 👗 👘 🥻 🩱 🩲 🩳 👙 👚 👛 👜 👝 🎒 🩴 👞 👟 🥾 🥿 👠 👡 🩰 👢 👑 👒 🎩 🎓 🧢 🪖 ⛑️ 💄 💍 💼".split(" ")),
    EmojiCategory("nature", "Animals & Nature", "Животные и природа", "Тварини та природа", R.string.emoji_nature, "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🐣 🐥 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🪱 🐛 🦋 🐌 🐞 🐜 🪰 🪲 🪳 🦟 🦗 🕷️ 🕸️ 🦂 🐢 🐍 🦎 🦖 🦕 🐙 🦑 🦐 🦞 🦀 🪸 🐡 🐠 🐟 🐬 🐳 🐋 🦈 🦭 🐊 🐅 🐆 🦓 🦍 🦧 🦣 🐘 🦛 🦏 🐪 🐫 🦒 🦘 🦬 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🦙 🐐 🦌 🐕 🐩 🦮 🐕‍🦺 🐈 🐈‍⬛ 🪶 🐓 🦃 🦤 🦚 🦜 🦢 🦩 🕊️ 🐇 🦝 🦨 🦡 🦫 🦦 🦥 🐁 🐀 🐿️ 🦔 🐾 🐉 🐲 🌵 🎄 🌲 🌳 🌴 🪵 🌱 🌿 ☘️ 🍀 🎍 🪴 🎋 🍃 🍂 🍁 🪺 🪹 🍄 🐚 🪨 🌾 💐 🌷 🌹 🥀 🪷 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 🌎 🌍 🌏 🪐 💫 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌪️ 🌈 ☀️ 🌤️ ⛅ 🌥️ ☁️ 🌦️ 🌧️ ⛈️ 🌩️ 🌨️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 🫧 ☔ ☂️ 🌊 🌫️".split(" ")),
    EmojiCategory("food", "Food & Drink", "Еда и напитки", "Їжа та напої", R.string.emoji_food, "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🫑 🌽 🥕 🫒 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🥨 🧀 🥚 🍳 🧈 🥞 🧇 🥓 🥩 🍗 🍖 🦴 🌭 🍔 🍟 🍕 🫓 🥪 🥙 🧆 🌮 🌯 🫔 🥗 🥘 🫕 🥫 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🦪 🍤 🍙 🍚 🍘 🍥 🥠 🥮 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🫘 🍯 🥛 🍼 🫖 ☕ 🍵 🧃 🥤 🧋 🍶 🍺 🍻 🥂 🍷 🫗 🥃 🍸 🍹 🧉 🍾 🧊 🥄 🍴 🍽️ 🥣 🥡 🥢 🧂".split(" ")),
    EmojiCategory("activity", "Activity", "Активность", "Активність", R.string.emoji_activity, "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🥏 🎱 🪀 🏓 🏸 🏒 🏑 🥍 🏏 🪃 🥅 ⛳ 🪁 🏹 🎣 🤿 🥊 🥋 🎽 🛹 🛼 🛷 ⛸️ 🥌 🎿 ⛷️ 🏂 🪂 🏋️ 🤼 🤸 ⛹️ 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚵 🚴 🏆 🥇 🥈 🥉 🏅 🎖️ 🏵️ 🎗️ 🎫 🎟️ 🎪 🤹 🎭 🩰 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🪘 🎷 🎺 🪗 🎸 🪕 🎻 🎲 ♟️ 🎯 🎳 🎮 🎰 🧩 🎉 🎊 🎈 🎁 🎀 🪅 🪩".split(" ")),
    EmojiCategory("travel", "Travel & Places", "Путешествия", "Подорожі", R.string.emoji_travel, "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🛻 🚚 🚛 🚜 🦯 🦽 🦼 🛴 🚲 🛵 🏍️ 🛺 🚨 🚔 🚍 🚘 🚖 🛞 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🛰️ 🚀 🛸 🚁 🛶 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ 🛟 🪝 ⛽ 🚧 🚦 🚥 🚏 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🛝 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🛖 🏠 🏡 🏘️ 🏚️ 🏗️ 🏭 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🕍 🛕 🕋 ⛩️ 🛤️ 🛣️ 🗾 🎑 🏞️ 🌅 🌄 🌠 🎇 🎆 🌇 🌆 🏙️ 🌃 🌌 🌉 🌁".split(" ")),
    EmojiCategory("objects", "Objects", "Предметы", "Предмети", R.string.emoji_objects, "⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🖲️ 🕹️ 🗜️ 💽 💾 💿 📀 📼 📷 📸 📹 🎥 📽️ 🎞️ 📞 ☎️ 📟 📠 📺 📻 🎙️ 🎚️ 🎛️ 🧭 ⏱️ ⏲️ ⏰ 🕰️ ⌛ ⏳ 📡 🔋 🪫 🔌 💡 🔦 🕯️ 🪔 🧯 🛢️ 💸 💵 💴 💶 💷 🪙 💰 💳 💎 ⚖️ 🪜 🧰 🪛 🔧 🔨 ⚒️ 🛠️ ⛏️ 🪚 🔩 ⚙️ 🪤 🧱 ⛓️ 🧲 🔫 💣 🧨 🪓 🔪 🗡️ ⚔️ 🛡️ 🚬 ⚰️ 🪦 ⚱️ 🏺 🔮 📿 🧿 🪬 💈 ⚗️ 🔭 🔬 🕳️ 🩹 🩺 🩻 🩼 💊 💉 🩸 🧬 🦠 🧫 🧪 🌡️ 🧹 🪠 🧺 🧻 🚽 🚰 🚿 🛁 🛀 🧼 🪥 🪒 🧽 🪣 🧴 🛎️ 🔑 🗝️ 🚪 🪑 🛋️ 🛏️ 🛌 🧸 🪆 🖼️ 🪞 🪟 🛍️ 🛒 🎁 🎈 🎏 🎀 🪄 🪅 🎊 🎉 🎎 🏮 🎐 🧧 ✉️ 📩 📨 📧 💌 📥 📤 📦 🏷️ 🪧 📪 📫 📬 📭 📮 📯 📜 📃 📄 📑 🧾 📊 📈 📉 🗒️ 🗓️ 📆 📅 🗑️ 📇 🗃️ 🗳️ 🗄️ 📋 📁 📂 🗂️ 🗞️ 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🧷 🔗 📎 🖇️ 📐 📏 🧮 📌 📍 ✂️ 🖊️ 🖋️ ✒️ 🖌️ 🖍️ 📝 ✏️ 🔍 🔎 🔏 🔐 🔒 🔓".split(" ")),
    EmojiCategory("symbols", "Symbols", "Символы", "Символи", R.string.emoji_symbols, "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ☮️ ✝️ ☪️ 🕉️ ☸️ ✡️ 🔯 🕎 ☯️ ☦️ 🛐 ⛎ ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ 🆔 ⚛️ 🉑 ☢️ ☣️ 📴 📳 🈶 🈚 🈸 🈺 🈷️ ✴️ 🆚 💮 🉐 ㊙️ ㊗️ 🈴 🈵 🈹 🈲 🅰️ 🅱️ 🆎 🆑 🅾️ 🆘 ❌ ⭕ 🛑 ⛔ 📛 🚫 💯 💢 ♨️ 🚷 🚯 🚳 🚱 🔞 📵 🚭 ❗ ❕ ❓ ❔ ‼️ ⁉️ 🔅 🔆 〽️ ⚠️ 🚸 🔱 ⚜️ 🔰 ♻️ ✅ 🈯 💹 ❇️ ✳️ ❎ 🌐 💠 Ⓜ️ 🌀 💤 🏧 🚾 ♿ 🅿️ 🛗 🈳 🈂️ 🛂 🛃 🛄 🛅 🚹 🚺 🚼 ⚧️ 🚻 🚮 🎦 📶 🈁 🔣 ℹ️ 🔤 🔡 🔠 🆖 🆗 🆙 🆒 🆕 🆓 0️⃣ 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣ 6️⃣ 7️⃣ 8️⃣ 9️⃣ 🔟 🔢 #️⃣ *️⃣ ⏏️ ▶️ ⏸️ ⏯️ ⏹️ ⏺️ ⏭️ ⏮️ ⏩ ⏪ ⏫ ⏬ ◀️ 🔼 🔽 ➡️ ⬅️ ⬆️ ⬇️ ↗️ ↘️ ↙️ ↖️ ↕️ ↔️ ↪️ ↩️ ⤴️ ⤵️ 🔀 🔁 🔂 🔄 🔃 🎵 🎶 ➕ ➖ ➗ ✖️ 🟰 ♾️ 💲 💱 ™️ ©️ ®️ 〰️ ➰ ➿ 🔚 🔙 🔛 🔝 🔜 ✔️ ☑️ 🔘 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🟫 🔈 🔇 🔉 🔊 🔔 🔕 📣 📢 👁️‍🗨️ 💬 💭 🗯️ ♠️ ♣️ ♥️ ♦️ 🃏 🎴 🀄 🕐 🕑 🕒 🕓 🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛".split(" ")),
    EmojiCategory("flags", "Flags", "Флаги", "Прапори", R.string.emoji_flags, "🏳️ 🏴 🏁 🚩 🏳️‍🌈 🏳️‍⚧️ 🏴‍☠️ 🇺🇦 🇺🇳 🇪🇺 🇦🇫 🇦🇱 🇩🇿 🇦🇩 🇦🇴 🇦🇷 🇦🇲 🇦🇺 🇦🇹 🇦🇿 🇧🇸 🇧🇭 🇧🇩 🇧🇾 🇧🇪 🇧🇿 🇧🇯 🇧🇹 🇧🇴 🇧🇦 🇧🇼 🇧🇷 🇧🇳 🇧🇬 🇧🇫 🇧🇮 🇰🇭 🇨🇲 🇨🇦 🇨🇻 🇨🇫 🇹🇩 🇨🇱 🇨🇳 🇨🇴 🇰🇲 🇨🇬 🇨🇩 🇨🇷 🇨🇮 🇭🇷 🇨🇺 🇨🇾 🇨🇿 🇩🇰 🇩🇯 🇩🇲 🇩🇴 🇪🇨 🇪🇬 🇸🇻 🇬🇶 🇪🇷 🇪🇪 🇸🇿 🇪🇹 🇫🇯 🇫🇮 🇫🇷 🇬🇦 🇬🇲 🇬🇪 🇩🇪 🇬🇭 🇬🇷 🇬🇩 🇬🇹 🇬🇳 🇬🇼 🇬🇾 🇭🇹 🇭🇳 🇭🇰 🇭🇺 🇮🇸 🇮🇳 🇮🇩 🇮🇷 🇮🇶 🇮🇪 🇮🇱 🇮🇹 🇯🇲 🇯🇵 🇯🇴 🇰🇿 🇰🇪 🇰🇮 🇽🇰 🇰🇼 🇰🇬 🇱🇦 🇱🇻 🇱🇧 🇱🇸 🇱🇷 🇱🇾 🇱🇮 🇱🇹 🇱🇺 🇲🇴 🇲🇬 🇲🇼 🇲🇾 🇲🇻 🇲🇱 🇲🇹 🇲🇭 🇲🇷 🇲🇺 🇲🇽 🇫🇲 🇲🇩 🇲🇨 🇲🇳 🇲🇪 🇲🇦 🇲🇿 🇲🇲 🇳🇦 🇳🇷 🇳🇵 🇳🇱 🇳🇿 🇳🇮 🇳🇪 🇳🇬 🇰🇵 🇲🇰 🇳🇴 🇴🇲 🇵🇰 🇵🇼 🇵🇸 🇵🇦 🇵🇬 🇵🇾 🇵🇪 🇵🇭 🇵🇱 🇵🇹 🇵🇷 🇶🇦 🇷🇴 🇷🇺 🇷🇼 🇰🇳 🇱🇨 🇻🇨 🇼🇸 🇸🇲 🇸🇹 🇸🇦 🇸🇳 🇷🇸 🇸🇨 🇸🇱 🇸🇬 🇸🇰 🇸🇮 🇸🇧 🇸🇴 🇿🇦 🇰🇷 🇸🇸 🇪🇸 🇱🇰 🇸🇩 🇸🇷 🇸🇪 🇨🇭 🇸🇾 🇹🇼 🇹🇯 🇹🇿 🇹🇭 🇹🇱 🇹🇬 🇹🇴 🇹🇹 🇹🇳 🇹🇷 🇹🇲 🇹🇻 🇺🇬 🇦🇪 🇬🇧 🇺🇸 🇺🇾 🇺🇿 🇻🇺 🇻🇦 🇻🇪 🇻🇳 🇾🇪 🇿🇲 🇿🇼".split(" ")),
)
// Preserve the pre-B04 semantic search vocabulary independently of iOS display grouping.
internal data class EmojiSearchAlias(val terms: String, val emojis: Set<String>)
internal val emojiSearchAliases = listOf(
    EmojiSearchAlias("Faces Лица радость смех улыбка грусть злость happy laugh sad angry радість сміх усмішка смуток злість", "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕".split(" ").toSet()),
    EmojiSearchAlias("Hearts Сердца любовь сердце love heart кохання серце", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💋 💌".split(" ").toSet()),
    EmojiSearchAlias("Gestures Жесты рука спасибо привет да нет hand thanks hello yes no рука дякую привіт так ні", "👍 👎 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✊ 👊 🤛 🤜 💅 🤳".split(" ").toSet()),
    EmojiSearchAlias("Cats and animals Животные кот кошка собака cat dog animal pet кіт кішка собака", "😺 😸 😹 😻 😼 😽 🙀 😿 😾 🐱 🐈 🐈‍⬛ 🐶 🐕 🐩 🐺 🦊 🦝 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐘 🦒 🦔 🐿️ 🐇".split(" ").toSet()),
    EmojiSearchAlias("Nature Природа цветок солнце погода луна flower sun weather moon квітка сонце погода місяць", "💐 🌸 🌹 🌺 🌻 🌼 🌷 🌱 🌲 🌳 🌴 🌵 🌿 ☘️ 🍀 🍁 🍂 🍃 🍄 🌍 🌎 🌏 🌕 🌙 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌈 ☀️ 🌤️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 🌊".split(" ").toSet()),
    EmojiSearchAlias("Food Еда еда кофе чай торт пицца food coffee tea cake pizza їжа кава чай торт піца", "🍎 🍏 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥒 🌶️ 🥕 🌽 🥔 🍞 🥐 🧀 🥚 🍳 🥓 🥩 🍗 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🍝 🍜 🍣 🍤 🍚 🍙 🍿 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍯 🥛 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🍹".split(" ").toSet()),
    EmojiSearchAlias("Activities Занятия спорт праздник музыка игра sports party music game спорт свято музика гра", "🎉 🎊 🎈 🎁 🎀 🎂 🏆 🥇 🥈 🥉 🏅 ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🎣 🎿 🛷 🏂 🏋️ 🤸 🏊 🚴 🧘 🎮 🕹️ 🎲 🧩 ♟️ 🎨 🎭 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻".split(" ").toSet()),
    EmojiSearchAlias("Travel and objects Предметы машина самолет дом телефон работа car plane home phone work машина літак дім телефон робота", "🚗 🚕 🚌 🚎 🏎️ 🚓 🚑 🚒 🚚 🚲 🛵 🏍️ 🚂 🚆 ✈️ 🚀 🛸 🚁 ⛵ 🚢 🏠 🏡 🏢 🏥 🏫 🏰 🗼 🗽 🏖️ 🏝️ ⛰️ 🏕️ 📱 💻 ⌨️ 🖥️ 📷 🎥 📺 💡 🔦 🔋 🔌 📚 📖 📝 ✏️ 🖊️ 📌 📎 ✂️ 🔒 🔑 🔨 🛠️ 💎 💰 💸 ⏰ ⌚ 📦".split(" ").toSet()),
    EmojiSearchAlias("Symbols Символы символ галочка огонь сто внимание yes check warning символ галочка вогонь сто увага", "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ❓ ❗ ⁉️ ‼️ 💯 🆗 🆕 🆒 🆘 🆓 🔝 🔙 🔜 🔛 🔚 ♻️ ⚠️ 🚫 ⛔ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔶 🔷 🔺 🔻 🔸 🔹 💠 ♦️ ♠️ ♥️ ♣️ 🎯 💤 💬 👀 🧠 🫶".split(" ").toSet()),
)
internal fun searchEmoji(query: String, groups: List<EmojiCategory> = emojiCategories): List<String> {
    val needle = query.trim()
    val aliases = emojiSearchAliases.filter { it.terms.contains(needle, ignoreCase = true) }.flatMap { it.emojis }.toSet()
    return groups.flatMap { group ->
        if (group.keywords.contains(needle, ignoreCase = true)) group.emojis
        else group.emojis.filter { it.contains(needle) || it in aliases }
    }.distinct()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(onDismiss: () -> Unit, initiallyExpanded: Boolean = true, embedded: Boolean = false, bottomAnchorY: Float? = null, onChoose: (String) -> Unit) {
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val recents = rememberEmojiRecentsStore()
    val choose: (String) -> Unit = { emoji -> recents.use(emoji); onChoose(emoji) }
    var originY by remember { mutableFloatStateOf(0f) }
    val screenBottom = remember(context) { if (android.os.Build.VERSION.SDK_INT >= 30) context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds.bottom else context.resources.displayMetrics.heightPixels }
    val content: @Composable () -> Unit = {
        val keyboardBottom = WindowInsets.ime.getBottom(density)
        val visibleBottom = minOf(bottomAnchorY ?: screenBottom.toFloat(), screenBottom.toFloat() - keyboardBottom)
        // Composer insertion keeps the draft readable; reaction menus own their backdrop.
        androidx.activity.compose.BackHandler { if (expanded && !initiallyExpanded) expanded = false else onDismiss() }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { originY = it.localToScreen(androidx.compose.ui.geometry.Offset.Zero).y }) {
        Box(Modifier.fillMaxWidth().height(minOf(maxHeight, with(density) { (visibleBottom - originY).coerceAtLeast(1f).toDp() })).then(if (bottomAnchorY == null && keyboardBottom == 0) Modifier.navigationBarsPadding() else Modifier)) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = if (expanded) .30f else .08f)).clickable(onClick = onDismiss))
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = if (keyboardBottom > 0) 48.dp else if (bottomAnchorY != null) 8.dp else if (embedded) 64.dp else 88.dp)
                .fillMaxWidth().animateContentSize(tween(160)).voicePhaseReveal(expanded),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = colors.deepPanel, contentColor = colors.primaryText,
                border = androidx.compose.foundation.BorderStroke(.7.dp, colors.divider.copy(alpha = .65f))) {
                if (expanded) EmojiCatalogue(onBack = { if (!initiallyExpanded) expanded = false else onDismiss() }, onDismiss, choose)
                else Column(Modifier.padding(top = 10.dp)) {
                    listOf("😊", "😂", "❤️", "👍", "😍", "🔥", "🙏", "😎", "😘", "🥰", "😢", "🎉").chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                            row.forEach { emoji -> Box(Modifier.weight(1f).height(48.dp).clickable { choose(emoji) }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 28.sp) } }
                        }
                    }
                    HorizontalDivider(color = colors.divider.copy(alpha = .22f))
                    TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        EmojiGridIcon(); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.emoji_all), Modifier.weight(1f)); Text("›", fontSize = 24.sp)
                    }
                }
            }
        }
    }
    }
    if (embedded) content() else androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { content() }
}

@Composable
internal fun EmojiGridIcon(modifier: Modifier = Modifier) {
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    androidx.compose.foundation.Canvas(modifier.size(18.dp)) {
        val gap = size.width * .16f
        val side = (size.width - gap) / 2
        repeat(4) { i -> drawRoundRect(colors.accent,
            topLeft = androidx.compose.ui.geometry.Offset((i % 2) * (side + gap), (i / 2) * (side + gap)),
            size = androidx.compose.ui.geometry.Size(side, side), cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * .24f)) }
    }
}

@Composable
private fun EmojiCatalogue(onBack: () -> Unit, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    var category by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val store = rememberEmojiRecentsStore()
    val saved by store.state.collectAsState()
    val groups = remember(saved.saved.recents) {
        (if (saved.saved.recents.isEmpty()) emptyList() else listOf(recentEmojiCategory(saved.saved.recents))) + emojiCategories
    }
    val visible = remember(category, query, groups) {
        if (query.isBlank()) groups[category.coerceAtMost(groups.lastIndex)].emojis else searchEmoji(query, groups)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .58f).dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.messenger.app.ui.components.CocoBackButton(onClick = onBack)
            Text(stringResource(R.string.emoji_title), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) { Icon(com.messenger.app.ui.theme.CocoIcons.Close, stringResource(R.string.emoji_close), tint = colors.accent) }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            leadingIcon = { Icon(com.messenger.app.ui.theme.CocoIcons.Search, null, tint = colors.secondaryText) },
            placeholder = { Text(stringResource(R.string.emoji_find)) })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            items(groups.indices.toList()) { index ->
                val label = stringResource(groups[index].label)
                TextButton(onClick = { category = index; query = "" }, modifier = Modifier.width(44.dp).height(44.dp)
                    .background(if (category == index && query.isBlank()) colors.accent.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                    .semantics { contentDescription = label }, contentPadding = PaddingValues(0.dp)) {
                        Icon(EmojiPanelIcons.categories.getValue(groups[index].id), null, tint = colors.primaryText, modifier = Modifier.size(20.dp))
                    }
            }
        }
        Text(stringResource(if (query.isBlank()) groups[category.coerceAtMost(groups.lastIndex)].label else R.string.emoji_results), color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
        if (visible.isEmpty()) Text(stringResource(R.string.emoji_no_results), Modifier.padding(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.weight(1f)) {
            items(visible, key = { it }) { emoji -> Box(Modifier.height(48.dp).clickable { onChoose(emoji) }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 28.sp) } }
        }
    }
}
