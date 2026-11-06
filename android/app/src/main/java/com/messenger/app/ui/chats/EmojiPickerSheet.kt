package com.messenger.app.ui.chats

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

internal data class EmojiCategory(val name: String, val ru: String, val keywords: String, val emojis: List<String>)
internal val emojiCategories = listOf(
    EmojiCategory("Faces", "Лица", "радость смех улыбка грусть злость happy laugh sad angry", "😀 😃 😄 😁 😆 😅 😂 🤣 😊 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨 🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 🤥 😶 😐 😑 😬 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤 😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕".split(" ")),
    EmojiCategory("Hearts", "Сердца", "любовь сердце love heart", "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❤️‍🔥 ❤️‍🩹 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💋 💌".split(" ")),
    EmojiCategory("Gestures", "Жесты", "рука спасибо привет да нет hand thanks hello yes no", "👍 👎 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💪 🦾 👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✊ 👊 🤛 🤜 💅 🤳".split(" ")),
    EmojiCategory("Cats and animals", "Животные", "кот кошка собака cat dog animal pet", "😺 😸 😹 😻 😼 😽 🙀 😿 😾 🐱 🐈 🐈‍⬛ 🐶 🐕 🐩 🐺 🦊 🦝 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐽 🐸 🐵 🙈 🙉 🙊 🐒 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐘 🦒 🦔 🐿️ 🐇".split(" ")),
    EmojiCategory("Nature", "Природа", "цветок солнце погода луна flower sun weather moon", "💐 🌸 🌹 🌺 🌻 🌼 🌷 🌱 🌲 🌳 🌴 🌵 🌿 ☘️ 🍀 🍁 🍂 🍃 🍄 🌍 🌎 🌏 🌕 🌙 ⭐ 🌟 ✨ ⚡ ☄️ 💥 🔥 🌈 ☀️ 🌤️ ⛅ ☁️ 🌧️ ⛈️ 🌩️ ❄️ ☃️ ⛄ 🌬️ 💨 💧 💦 🌊".split(" ")),
    EmojiCategory("Food", "Еда", "еда кофе чай торт пицца food coffee tea cake pizza", "🍎 🍏 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥒 🌶️ 🥕 🌽 🥔 🍞 🥐 🧀 🥚 🍳 🥓 🥩 🍗 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🍝 🍜 🍣 🍤 🍚 🍙 🍿 🍩 🍪 🎂 🍰 🧁 🍫 🍬 🍭 🍯 🥛 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🍹".split(" ")),
    EmojiCategory("Activities", "Занятия", "спорт праздник музыка игра sports party music game", "🎉 🎊 🎈 🎁 🎀 🎂 🏆 🥇 🥈 🥉 🏅 ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ 🎣 🎿 🛷 🏂 🏋️ 🤸 🏊 🚴 🧘 🎮 🕹️ 🎲 🧩 ♟️ 🎨 🎭 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻".split(" ")),
    EmojiCategory("Travel and objects", "Предметы", "машина самолет дом телефон работа car plane home phone work", "🚗 🚕 🚌 🚎 🏎️ 🚓 🚑 🚒 🚚 🚲 🛵 🏍️ 🚂 🚆 ✈️ 🚀 🛸 🚁 ⛵ 🚢 🏠 🏡 🏢 🏥 🏫 🏰 🗼 🗽 🏖️ 🏝️ ⛰️ 🏕️ 📱 💻 ⌨️ 🖥️ 📷 🎥 📺 💡 🔦 🔋 🔌 📚 📖 📝 ✏️ 🖊️ 📌 📎 ✂️ 🔒 🔑 🔨 🛠️ 💎 💰 💸 ⏰ ⌚ 📦".split(" ")),
    EmojiCategory("Symbols", "Символы", "символ галочка огонь сто внимание yes check warning", "✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ❓ ❗ ⁉️ ‼️ 💯 🆗 🆕 🆒 🆘 🆓 🔝 🔙 🔜 🔛 🔚 ♻️ ⚠️ 🚫 ⛔ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔶 🔷 🔺 🔻 🔸 🔹 💠 ♦️ ♠️ ♥️ ♣️ 🎯 💤 💬 👀 🧠 🫶".split(" ")),
 )
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiPickerSheet(onDismiss: () -> Unit, initiallyExpanded: Boolean = true, embedded: Boolean = false, bottomAnchorY: Float? = null, onChoose: (String) -> Unit) {
    val colors = com.messenger.app.ui.theme.LocalCocoColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val density = LocalDensity.current
    val context = LocalContext.current
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
                if (expanded) EmojiCatalogue(onBack = { if (!initiallyExpanded) expanded = false else onDismiss() }, onDismiss, onChoose)
                else Column(Modifier.padding(top = 10.dp)) {
                    listOf("😊", "😂", "❤️", "👍", "😍", "🔥", "🙏", "😎", "😘", "🥰", "😢", "🎉").chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                            row.forEach { emoji -> Box(Modifier.weight(1f).height(48.dp).clickable { onChoose(emoji) }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 28.sp) } }
                        }
                    }
                    HorizontalDivider(color = colors.divider.copy(alpha = .22f))
                    TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        EmojiGridIcon(); Spacer(Modifier.width(12.dp)); Text(if (LocalConfiguration.current.locales[0].language == "ru") "Все эмодзи" else "All emoji", Modifier.weight(1f)); Text("›", fontSize = 24.sp)
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
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    val icons = listOf("☺", "♡", "✋", "🐾", "☀", "🍎", "⚽", "✈", "✓")
    val visible = remember(category, query) {
        if (query.isBlank()) emojiCategories[category].emojis else emojiCategories.filter {
            (it.name + " " + it.ru + " " + it.keywords).contains(query.trim(), ignoreCase = true) || it.emojis.any { emoji -> emoji.contains(query.trim()) }
        }.flatMap { group -> if (group.emojis.any { it.contains(query.trim()) }) group.emojis.filter { it.contains(query.trim()) } else group.emojis }.distinct()
    }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .58f).dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.messenger.app.ui.components.CocoBackButton(onClick = onBack)
            Text(if (russian) "Эмодзи" else "Emoji", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) { Icon(com.messenger.app.ui.theme.CocoIcons.Close, if (russian) "Закрыть" else "Close", tint = colors.accent) }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            leadingIcon = { Icon(com.messenger.app.ui.theme.CocoIcons.Search, null, tint = colors.secondaryText) },
            placeholder = { Text(if (russian) "Найти эмодзи" else "Find emoji") })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            items(emojiCategories.indices.toList()) { index ->
                TextButton(onClick = { category = index; query = "" }, modifier = Modifier.width(44.dp).height(44.dp)
                    .background(if (category == index && query.isBlank()) colors.accent.copy(alpha = .14f) else androidx.compose.ui.graphics.Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                    .semantics { contentDescription = if (russian) emojiCategories[index].ru else emojiCategories[index].name }, contentPadding = PaddingValues(0.dp)) { Text(icons[index], fontSize = 24.sp, color = colors.primaryText) }
            }
        }
        Text(if (query.isBlank()) (if (russian) emojiCategories[category].ru else emojiCategories[category].name) else if (russian) "Результаты" else "Results", color = colors.secondaryText, style = MaterialTheme.typography.labelMedium)
        if (visible.isEmpty()) Text(if (russian) "Ничего не найдено" else "No results", Modifier.padding(16.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.weight(1f)) {
            items(visible, key = { it }) { emoji -> Box(Modifier.height(48.dp).clickable { onChoose(emoji) }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 28.sp) } }
        }
    }
}
