package com.messenger.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.AppColorStyle
import com.messenger.app.ui.theme.LocalResolvedSidebarStyle
import com.messenger.app.ui.theme.LocalAppColorStyle
import com.messenger.app.ui.theme.SavedSidebarStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Generated tokens come from the user's saved sidebar, not app category defaults. */
internal fun savedSidebarColor(argb: Long, style: AppColorStyle): Color = Color(
    (argb and 0xFF000000L) or style.materialRgb((argb and 0xFFFFFFL).toInt()).toLong(),
)

@Composable internal fun sidebarInk(argb: Long): Color = savedSidebarColor(argb, LocalAppColorStyle.current)

internal fun sidebarFont(family: String): FontFamily = when (family) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.Default
}

/** Inject destinations around saved anchors without rewriting saved order/visibility. */
internal fun drawerItems(saved: List<SavedSidebarStyle.Item>, showCalls: Boolean, showSaved: Boolean): List<SavedSidebarStyle.Item> =
    saved.filter { it.visible && (it.id != "calls" || showCalls) && (it.id != "saved" || showSaved) }
        .sortedWith(compareBy({ it.order }, { it.id })).toMutableList().apply {
            fun insert(id: String, after: String, color: Long, icon: String) {
                // An explicitly hidden custom row must stay hidden too.
                if (saved.any { it.id == id }) return
                val position = indexOfFirst { it.id == after }.let { if (it < 0) 0 else it + 1 }
                add(position, SavedSidebarStyle.Item(id, id, icon, color, true, position, "main"))
            }
            insert("rooms", if (any { it.id == "calls" }) "calls" else "chats", 0xFFAC9CDDL, "users")
            insert("archive", "chats", 0xFF7BC9ECL, "archive")
            insert("transfers", "archive", firstOrNull { it.id == "chats" }?.color ?: 0xFF7BC9ECL, "arrow.up.arrow.down")
        }

/** iOS counts all nonterminal records, including paused/waiting transfers, not byte progress. */
private val drawerTerminalStates = setOf("cancelled", "rejected", "expired", "other_device")
internal fun drawerTransferCount(items: List<com.messenger.app.data.directtransfer.DirectTransferManagerItem>): Int =
    items.count { item -> item.presentation?.let { !it.completed && it.state !in drawerTerminalStates } == true }

/** Software-rasterized once per size/style: the same blur/offset also works below API 31. */
private fun Modifier.sidebarShadow(shadow: Dp, corner: Dp): Modifier = if (shadow <= 0.dp) this else drawWithCache {
    // Blur at one pixel per dp, then filter the soft mask at device density.
    val rasterScale = 1f / density.coerceAtLeast(1f)
    val radius = shadow.toPx() * rasterScale * 2f
    val pad = ceil(radius * 3f).toInt()
    val bitmap = android.graphics.Bitmap.createBitmap(ceil(size.width * rasterScale).toInt() + 2 * pad,
        ceil(size.height * rasterScale).toInt() + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val path = android.graphics.Path().apply {
        val r = corner.toPx() * rasterScale
        addRoundRect(android.graphics.RectF(pad.toFloat(), pad.toFloat(), pad + size.width * rasterScale, pad + size.height * rasterScale),
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f), android.graphics.Path.Direction.CW)
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        setShadowLayer(radius, shadow.toPx() * rasterScale / 2f, 0f, 0x66000000)
    }
    canvas.drawPath(path, paint)
    paint.clearShadowLayer()
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    canvas.drawPath(path, paint)
    val cached = bitmap.asImageBitmap()
    onDrawBehind { drawImage(cached,
        dstOffset = IntOffset((-pad / rasterScale).roundToInt(), (-pad / rasterScale).roundToInt()),
        dstSize = IntSize((bitmap.width / rasterScale).roundToInt(), (bitmap.height / rasterScale).roundToInt()),
        filterQuality = FilterQuality.Low) }
}

@Composable
internal fun GraphiteDrawer(
    width: Dp,
    userId: String?, displayName: String, username: String?, avatarVersion: Long, hasAvatar: Boolean,
    selected: RootDestination, unread: Int, showCalls: Boolean, showSaved: Boolean,
    onClose: () -> Unit, onEdit: () -> Unit, onSearch: () -> Unit,
    onDestination: (RootDestination) -> Unit, onSaved: () -> Unit,
    onServer: () -> Unit, onDevices: () -> Unit, onHelp: () -> Unit,
    onReport: () -> Unit, onLogout: () -> Unit, onPeople: () -> Unit = {},
    isOnline: Boolean = false,
    avatar: (@Composable (Dp, Color, Color) -> Unit)? = null,
    onRooms: () -> Unit = {},
    onArchive: () -> Unit = {},
    showingArchive: Boolean = false,
    transferCount: Int = 0,
    onQR: (() -> Unit)? = null,
    profileControls: (@Composable () -> Unit)? = null,
) {
    val style = LocalResolvedSidebarStyle.current
    val metrics = remember(width, style) { style.metrics(width.value * 100f / style.geometryWidth) }
    val shape = RoundedCornerShape(topEnd = style.geometryCorner.dp, bottomEnd = style.geometryCorner.dp)
    val top = sidebarInk(style.materialTop).copy(alpha = style.materialOpacity / 100f)
    val middle = sidebarInk(style.materialBackground).copy(alpha = style.materialOpacity / 100f)
    val bottom = sidebarInk(style.materialBottom).copy(alpha = style.materialOpacity / 100f)
    val outline = sidebarInk(style.materialBorderColor).copy(alpha = style.materialBorderOpacity / 100f)
    val patternInk = sidebarInk(style.patternColor).copy(alpha = style.patternOpacity / 100f)
    val items = remember(style, showCalls, showSaved) { drawerItems(style.items, showCalls, showSaved) }
    ModalDrawerSheet(
        modifier = Modifier.width(width).sidebarShadow(style.materialShadow.dp, style.geometryCorner.dp)
            .clip(shape).drawWithCache {
                val angle = style.materialAngle * Math.PI / 180.0
                val dx = sin(angle).toFloat(); val dy = -cos(angle).toFloat()
                val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val brush = if (style.materialGradient) Brush.linearGradient(
                    0f to top, .55f to middle, 1f to bottom,
                    start = center - Offset(dx * half, dy * half), end = center + Offset(dx * half, dy * half),
                ) else Brush.linearGradient(listOf(middle, middle))
                val stroke = style.materialBorderWidth.dp.toPx()
                val radius = style.geometryCorner.dp.toPx().coerceAtMost(size.height / 2f)
                val edge = Path().apply {
                    moveTo(size.width - radius, stroke / 2f)
                    quadraticBezierTo(size.width - stroke / 2f, stroke / 2f, size.width - stroke / 2f, radius)
                    lineTo(size.width - stroke / 2f, size.height - radius)
                    quadraticBezierTo(size.width - stroke / 2f, size.height - stroke / 2f, size.width - radius, size.height - stroke / 2f)
                }
                // One cached path, rebuilt only for actual size/style changes.
                val pattern = Path()
                val spacing = style.patternSpacing.dp.toPx().coerceAtLeast(20.dp.toPx())
                val mark = style.patternSize.dp.toPx() / 2f
                if (style.patternKind != "none") {
                    var y = 0f
                    while (y <= size.height + spacing) {
                        var x = 0f
                        while (x <= size.width + spacing) {
                            if (style.patternKind == "lines") { pattern.moveTo(x - mark, y); pattern.lineTo(x + mark, y) }
                            else if (style.patternKind == "dots") pattern.addOval(Rect(x - mark / 5f, y - mark / 5f, x + mark / 5f, y + mark / 5f))
                            else if (style.patternKind == "diamonds") {
                                pattern.moveTo(x, y - mark); pattern.lineTo(x + mark, y)
                                pattern.lineTo(x, y + mark); pattern.lineTo(x - mark, y); pattern.close()
                            }
                            x += spacing
                        }
                        y += spacing
                    }
                }
                val patternStroke = Stroke(style.patternStroke.dp.toPx())
                onDrawWithContent {
                    drawRect(brush)
                    if (style.patternKind != "none") rotate(style.patternRotation) {
                        if (style.patternKind == "dots") drawPath(pattern, patternInk)
                        else drawPath(pattern, patternInk, style = patternStroke)
                    }
                    drawContent()
                    if (stroke > 0f) drawPath(edge, outline, style = Stroke(stroke))
                }
            }.testTag("saved-sidebar"),
        drawerShape = shape, drawerContainerColor = Color.Transparent, drawerTonalElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // One scroll owner includes the footer, so large text never strands sign-out.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = metrics.inset.dp)
                .padding(top = style.geometryTop.dp, bottom = style.geometryBottom.dp)) {
                SavedSidebarProfile(displayName, username, metrics, isOnline, onEdit, onClose, onQR) { size, fill, ink ->
                    val base = MaterialTheme.typography
                    val avatarTypography = remember(base, size, style.typographyFamily) { base.copy(titleMedium = base.titleMedium.copy(
                        fontSize = maxOf(12f, size.value * .42f).sp, fontWeight = FontWeight.Medium,
                        fontFamily = sidebarFont(style.typographyFamily))) }
                    MaterialTheme(typography = avatarTypography) {
                        if (avatar != null) avatar(size, fill, ink)
                        else if (hasAvatar) UserAvatar(userId = userId.orEmpty(), avatarVersion = avatarVersion, hasAvatar = true,
                            name = displayName, color = fill, contentColor = ink, size = size)
                        else Box(Modifier.size(size).background(fill, RoundedCornerShape((style.avatarRadius / 100f * size.value).dp)),
                            contentAlignment = Alignment.Center) {
                            Text(displayName.take(1).uppercase(), color = ink, fontSize = (size.value * .4f).sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                profileControls?.invoke()
                Spacer(Modifier.height(8.dp))
                if (style.quickVisible) {
                    DrawerSearchActions(onSearch, onPeople, metrics.quickSideBySide)
                    Spacer(Modifier.height(style.quickBottom.dp))
                }
                @Composable fun drawItem(item: SavedSidebarStyle.Item) {
                    val active = when (item.id) {
                        "chats" -> selected == RootDestination.CHATS && !showingArchive
                        "archive" -> selected == RootDestination.CHATS && showingArchive
                        "calls" -> selected == RootDestination.CALLS
                        "rooms" -> selected == RootDestination.ROOMS
                        "transfers" -> selected == RootDestination.TRANSFERS
                        "settings" -> selected == RootDestination.SETTINGS
                        else -> false
                    }
                    val action: () -> Unit = when (item.id) {
                        "chats" -> { { onDestination(RootDestination.CHATS) } }
                        "calls" -> { { onDestination(RootDestination.CALLS) } }
                        "rooms" -> onRooms
                        "transfers" -> { { onDestination(RootDestination.TRANSFERS) } }
                        "archive" -> onArchive
                        "saved" -> onSaved
                        "settings" -> { { onDestination(RootDestination.SETTINGS) } }
                        "server" -> onServer
                        "devices" -> onDevices
                        "help" -> onHelp
                        "report" -> onReport
                        else -> ({})
                    }
                    SavedSidebarRow(item, metrics, active, when (item.id) { "chats" -> unread; "transfers" -> transferCount; else -> 0 }, action)
                }
                if (style.rowsGrouped) {
                    items.filter { it.section == "main" }.forEach { drawItem(it) }
                    if (items.any { it.section == "service" }) {
                        SavedSidebarDivider(); Spacer(Modifier.height(style.rowsGroupGap.dp))
                        items.filter { it.section == "service" }.forEach { drawItem(it) }
                    }
                } else items.forEach { drawItem(it) }
                Spacer(Modifier.weight(1f))
                SavedSidebarFooter(onLogout)
            }
        }
    }
}

/** Actual header with an avatar slot, so geometry tests need no account or network. */
@Composable
internal fun SavedSidebarProfile(
    displayName: String, username: String?, metrics: SavedSidebarStyle.Metrics, isOnline: Boolean,
    onEdit: () -> Unit, onClose: () -> Unit,
    onQR: (() -> Unit)? = null,
    avatar: @Composable (Dp, Color, Color) -> Unit,
) {
    val style = LocalResolvedSidebarStyle.current
    val foreground = sidebarInk(style.typographyText)
    val muted = sidebarInk(style.typographyMuted)
    val avatarFill = sidebarInk(style.avatarColor)
    val avatarInk = sidebarInk(style.avatarInk)
    val onlineDescription = stringResource(R.string.connection_header_online)
    val closeDescription = stringResource(R.string.dismiss)
    val qrDescription = stringResource(R.string.sidebar_qr_title)
    val centered = metrics.profileLayout == "center" || LocalDensity.current.fontScale >= 1.6f
    // The saved shortcut preference now controls the existing compact header action.
    // Hiding it leaves the identity and independent QR action intact, with no extra row.
    val profileAction = if (style.profileShowEdit) Modifier.clickable(role = Role.Button, onClick = onEdit)
        .testTag("saved-sidebar-profile-edit") else Modifier.testTag("saved-sidebar-profile-identity")
    @Composable fun avatarContent() {
        if (style.profileShowAvatar) Box(Modifier.size(metrics.avatarSize.dp).testTag("saved-sidebar-avatar")) {
            avatar(metrics.avatarSize.dp, avatarFill, avatarInk)
            if (style.avatarOnline && isOnline) Box(Modifier.align(Alignment.BottomEnd).size(maxOf(6f, metrics.avatarSize * .18f).dp)
                .background(Color(style.avatarOnlineColor), CircleShape)
                .border(2.dp, sidebarInk(style.materialBackground), CircleShape)
                .semantics { contentDescription = onlineDescription }.testTag("saved-sidebar-online"))
        }
    }
    @Composable fun nameContent(modifier: Modifier = Modifier, centered: Boolean = false) {
        Column(modifier, horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
            Text(displayName, color = foreground, fontSize = style.profileNameSize.sp,
                fontFamily = sidebarFont(style.typographyFamily),
                lineHeight = (style.profileNameSize * 1.2f).sp, fontWeight = FontWeight.SemiBold,
                maxLines = if (onQR != null) 2 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.testTag("saved-sidebar-name"))
            if (style.profileShowUsername && !username.isNullOrBlank() && onQR != null) SidebarUsername("@$username", centered)
            else if (style.profileShowUsername && !username.isNullOrBlank()) Text("@$username", color = muted,
                fontFamily = sidebarFont(style.typographyFamily),
                fontSize = style.profileUserSize.sp, lineHeight = (style.profileUserSize * 1.2f).sp,
                maxLines = if (onQR != null) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.padding(top = 2.dp).testTag("saved-sidebar-username"))
        }
    }
    @Composable fun closeContent(modifier: Modifier = Modifier) {
        if (onQR != null || style.profileShowClose) Box(modifier.size(44.dp).clickable(role = Role.Button, onClick = onQR ?: onClose)
            .semantics { contentDescription = if (onQR != null) qrDescription else closeDescription }
            .testTag(if (onQR != null) "saved-sidebar-qr" else "saved-sidebar-close"), contentAlignment = Alignment.Center) {
            if (onQR != null) ProfileQrIcon(muted, style.iconsSize.dp)
            else SavedSidebarIcon(R.drawable.coco_sidebar_close, muted, 18.dp)
        }
    }
    Box(Modifier.fillMaxWidth().background(sidebarInk(style.profileFill)
        .copy(alpha = style.profileFillOpacity / 100f), RoundedCornerShape(style.rowsRadius.dp))
        .padding(metrics.profilePadding.dp).padding(bottom = style.profileBottom.dp).testTag("saved-sidebar-profile")) {
        if (centered) {
            Column(Modifier.fillMaxWidth().padding(top = if (onQR != null || style.profileShowClose) 44.dp else 0.dp)
                .then(profileAction), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(style.profileGap.dp)) {
                avatarContent(); nameContent(centered = true)
            }
            closeContent(Modifier.align(Alignment.TopEnd))
        } else Row(Modifier.fillMaxWidth().heightIn(min = if (metrics.profileLayout == "compact") 44.dp else 58.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(style.profileGap.dp)) {
            Row(Modifier.weight(1f).then(profileAction), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(style.profileGap.dp)) {
                avatarContent(); nameContent(Modifier.weight(1f))
            }
            closeContent()
        }
    }
}

internal fun middleEllipsized(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text
    if (!fits("…")) return ""
    var low = 0; var high = text.length - 1; var best = "…"
    while (low <= high) {
        val count = (low + high) / 2
        val candidate = text.take((count + 1) / 2) + "…" + text.takeLast(count / 2)
        if (fits(candidate)) { best = candidate; low = count + 1 } else high = count - 1
    }
    return best
}

/** Mirrors iOS's 0.8 minimum scale followed by middle truncation, measured only when inputs change. */
@Composable private fun SidebarUsername(text: String, centered: Boolean) {
    val style = LocalResolvedSidebarStyle.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = TextStyle(fontFamily = sidebarFont(style.typographyFamily), fontSize = style.profileUserSize.sp,
        lineHeight = (style.profileUserSize * 1.2f).sp)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        val (shown, font) = remember(text, maxWidth, baseStyle, density) {
            val available = with(density) { maxWidth.toPx() }
            val fullWidth = measurer.measure(text, baseStyle, softWrap = false).size.width.coerceAtLeast(1)
            val chosen = baseStyle.copy(fontSize = baseStyle.fontSize * (available / fullWidth).coerceIn(.8f, 1f))
            middleEllipsized(text) { measurer.measure(it, chosen, softWrap = false).size.width <= available } to chosen
        }
        Text(shown, style = font, color = sidebarInk(style.typographyMuted), maxLines = 1,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = text }.testTag("saved-sidebar-username"))
    }
}

/** Narrow viewports stack complete labels rather than truncating two buttons. */
@Composable
internal fun DrawerSearchActions(onSearch: () -> Unit, onPeople: () -> Unit, sideBySide: Boolean? = null) {
    val style = LocalResolvedSidebarStyle.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = (sideBySide ?: (style.quickLayout == "row" && maxWidth.value >= 136f + style.quickGap)) && LocalDensity.current.fontScale < 1.6f
        @Composable fun action(people: Boolean, modifier: Modifier) {
            Surface(onClick = if (people) onPeople else onSearch, modifier = modifier,
                color = sidebarInk(style.quickFill), shape = RoundedCornerShape(style.quickRadius.dp),
                border = BorderStroke(style.quickBorderWidth.dp, sidebarInk(style.quickBorder))) {
                Row(Modifier.heightIn(min = style.quickHeight.dp).padding(horizontal = 4.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    SavedSidebarIcon(if (people) R.drawable.coco_sidebar_people else R.drawable.coco_sidebar_search,
                        sidebarInk(style.quickInk), style.quickIconSize.dp)
                    Text(stringResource(if (people) R.string.search_scope_people else R.string.chats_search),
                        fontFamily = sidebarFont(style.typographyFamily),
                        color = sidebarInk(style.quickInk), fontSize = style.quickSize.sp,
                        lineHeight = (style.quickSize * 1.35f).sp, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        if (horizontal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(style.quickGap.dp)) {
            action(false, Modifier.weight(1f)); action(true, Modifier.weight(1f))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(style.quickGap.dp)) {
            action(false, Modifier.fillMaxWidth()); action(true, Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun SavedSidebarDivider() {
    val style = LocalResolvedSidebarStyle.current
    if (style.dividerVisible) HorizontalDivider(Modifier.padding(horizontal = style.dividerInset.dp,
        vertical = style.dividerGap.dp), thickness = style.dividerThickness.dp,
        color = sidebarInk(style.dividerColor).copy(alpha = style.dividerOpacity / 100f))
}

@Composable
internal fun SavedSidebarFooter(onLogout: () -> Unit) {
    val style = LocalResolvedSidebarStyle.current
    Column(Modifier.fillMaxWidth().padding(top = style.footerGap.dp).testTag("saved-sidebar-footer")) {
        SavedSidebarDivider()
        if (style.footerShowLogout) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(RoundedCornerShape(style.footerRadius.dp))
            .background(sidebarInk(style.footerFill).copy(alpha = style.footerFillOpacity / 100f))
            .clickable(role = Role.Button, onClick = onLogout).padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("saved-sidebar-logout"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // The user's rose exit color remains semantic in monochrome.
            SavedSidebarIcon(R.drawable.coco_sidebar_logout, Color(style.footerColor), style.iconsSize.dp)
            Text(stringResource(R.string.sidebar_saved_sign_out), color = Color(style.footerColor),
                fontFamily = sidebarFont(style.typographyFamily),
                fontSize = style.footerSize.sp, lineHeight = (style.footerSize * 1.35f).sp,
                modifier = Modifier.weight(1f).testTag("saved-sidebar-logout-label"))
        }
        if (style.footerShowVersion) Text("${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
            fontFamily = sidebarFont(style.typographyFamily),
            color = sidebarInk(style.typographyMuted), fontSize = style.footerVersionSize.sp,
            lineHeight = (style.footerVersionSize * 1.35f).sp,
            textAlign = when (style.footerVersionAlign) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp).testTag("saved-sidebar-version"))
    }
}

@Composable internal fun SavedSidebarRow(item: SavedSidebarStyle.Item, metrics: SavedSidebarStyle.Metrics,
    selected: Boolean, badge: Int, onClick: () -> Unit) {
    val style = LocalResolvedSidebarStyle.current
    val secondary = item.section != "main"
    val accessibleLabel = if (item.id == "report") stringResource(R.string.feedback_title) else null
    val tint = sidebarInk(if (style.iconsColored) item.color else style.iconsColor)
    val ink = sidebarInk(if (selected) style.selectedInk else if (secondary)
        style.typographyMuted else style.typographyText)
    val background = sidebarInk(style.rowsFill).copy(alpha = style.rowsFillOpacity / 100f)
    // The exported defaults predate the iOS content chip. Explicit saved overrides still win.
    val defaultSelection = style.selectedFill == SavedSidebarStyle.selectedFill && style.selectedOpacity == SavedSidebarStyle.selectedOpacity
    val selection = if (defaultSelection) Color.White.copy(alpha = .09f)
        else sidebarInk(style.selectedFill).copy(alpha = style.selectedOpacity / 100f)
    val fontSize = if (secondary) style.typographySecondarySize else style.typographySize
    Row(Modifier.fillMaxWidth().padding(bottom = style.geometryRowGap.dp)
        .clip(RoundedCornerShape(style.rowsRadius.dp)).background(background)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected; accessibleLabel?.let { contentDescription = it } }
        .heightIn(min = (if (secondary) style.geometrySecondaryHeight else style.geometryRowHeight).dp)
        .padding(horizontal = metrics.rowPaddingX.dp, vertical = style.geometryRowPaddingY.dp)
        .testTag("saved-sidebar-item-${item.id}"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.iconGap.dp)) {
        Box(Modifier.weight(1f)) {
            // Visual chip extends 10dp before the icon; the full row remains the touch target.
            Row(Modifier.offset(x = if (selected) (-10).dp else 0.dp)
                .background(if (selected) selection else Color.Transparent, CircleShape)
                .padding(horizontal = if (selected) 10.dp else 0.dp, vertical = if (selected) 6.dp else 0.dp)
                .testTag("saved-sidebar-chip-${item.id}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(metrics.iconGap.dp)) {
                val tile = if (style.iconsTiles) Modifier.background(tint.copy(alpha = style.iconsTileOpacity / 100f),
                    RoundedCornerShape(style.iconsTileRadius.dp)).padding(metrics.tilePadding.dp) else Modifier
                Box(tile) {
                    SavedSidebarIcon(sidebarItemIcon(item.id), if (selected) ink else tint, style.iconsSize.dp)
                }
                Text(sidebarItemTitle(item.id, item.title), fontSize = fontSize.sp, lineHeight = (fontSize * 1.2f).sp,
                    color = ink, letterSpacing = style.typographyLetterSpacing.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight(style.typographyWeight.toInt()),
                    fontFamily = sidebarFont(style.typographyFamily), modifier = Modifier.weight(1f, fill = false))
            }
        }
        if (badge > 0) {
            // Match iOS's readable neutral pair; keep the approved colorful export exact.
            val monochrome = LocalAppColorStyle.current == AppColorStyle.MONOCHROME
            val counter = item.id != "chats"
            val badgeFill = if (counter) Color.White.copy(alpha = .08f) else if (monochrome) Color(0xFFDEDEDE) else sidebarInk(style.selectedBadge)
            val badgeInk = if (counter) sidebarInk(style.typographyMuted) else if (monochrome) Color(0xFF222222) else sidebarInk(style.selectedBadgeInk)
            val badgeDescription = stringResource(if (counter) R.string.sidebar_transfer_count else R.string.sidebar_unread_count, badge)
            Text(if (!counter && badge > 99) "99+" else "$badge", color = badgeInk,
                fontSize = style.selectedBadgeSize.sp, lineHeight = (style.selectedBadgeSize * 1.35f).sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(if (counter) "saved-sidebar-transfer-badge" else "saved-sidebar-unread-badge")
                    .semantics { contentDescription = badgeDescription }.widthIn(min = 18.dp)
                    .background(badgeFill, RoundedCornerShape(style.selectedBadgeRadius.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp))
        }
        if (style.iconsChevrons) SavedSidebarIcon(R.drawable.coco_sidebar_chevron,
            sidebarInk(style.typographyMuted), 12.dp)
    }
}

@Composable private fun sidebarItemTitle(id: String, fallback: String): String = when (id) {
    "chats" -> stringResource(R.string.nav_chats)
    "calls" -> stringResource(R.string.nav_calls)
    "rooms" -> stringResource(R.string.conference_rooms_title)
    "transfers" -> stringResource(R.string.direct_manager_title)
    "archive" -> stringResource(R.string.chats_archive_title)
    "saved" -> stringResource(R.string.saved_messages)
    "settings" -> stringResource(R.string.nav_settings)
    "server" -> stringResource(R.string.server_label)
    "devices" -> stringResource(R.string.devices_title)
    "help" -> stringResource(R.string.drawer_help)
    "report" -> stringResource(R.string.drawer_report_short)
    else -> fallback
}

@DrawableRes private fun sidebarItemIcon(id: String): Int = when (id) {
    "chats" -> R.drawable.coco_sidebar_chats
    "calls" -> R.drawable.coco_sidebar_calls
    "rooms" -> R.drawable.coco_sidebar_rooms
    "transfers" -> R.drawable.coco_sidebar_transfers
    "archive" -> R.drawable.coco_sidebar_archive
    "saved" -> R.drawable.coco_sidebar_saved
    "settings" -> R.drawable.coco_sidebar_settings
    "server" -> R.drawable.coco_sidebar_server
    "devices" -> R.drawable.coco_sidebar_devices
    "help" -> R.drawable.coco_sidebar_help
    else -> R.drawable.coco_sidebar_report
}

@Composable private fun SavedSidebarIcon(@DrawableRes resource: Int, color: Color, size: Dp) {
    Icon(painterResource(resource), contentDescription = null, tint = color, modifier = Modifier.size(size))
}
