package com.messenger.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.messenger.app.BuildConfig
import com.messenger.app.R
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.theme.*
import kotlin.math.roundToInt

internal enum class DrawerAccountsLayout { COMPACT, FULL, NARROW }

/** Both variants consume these same values and callbacks; only the selected layout is composed. */
@Composable
internal fun SidebarDrawer(
    width: Dp,
    userId: String?, displayName: String, username: String?, avatarVersion: Long, hasAvatar: Boolean,
    selected: RootDestination, unread: Int, showCalls: Boolean, showSaved: Boolean,
    onClose: () -> Unit, onEdit: () -> Unit, onSearch: () -> Unit,
    onDestination: (RootDestination) -> Unit, onSaved: () -> Unit,
    onServer: () -> Unit, onDevices: () -> Unit, onHelp: () -> Unit,
    onReport: () -> Unit, onLogout: () -> Unit, onPeople: () -> Unit = {},
    isOnline: Boolean = false,
    avatar: (@Composable (Dp, Color, Color) -> Unit)? = null,
    onRooms: () -> Unit = {}, onArchive: () -> Unit = {}, showingArchive: Boolean = false,
    transferCount: Int = 0, onQR: (() -> Unit)? = null,
    host: String = "", badges: DrawerBadges = DrawerBadges(chats = unread, transfers = transferCount),
    variant: DrawerChoice = LocalDrawerChoice.current,
    status: @Composable () -> Unit = { DrawerPresenceControl(DrawerPresence.ONLINE, false, {}) },
    accounts: @Composable (DrawerAccountsLayout) -> Unit = {},
    publicNumber: String? = null,
) {
    val large = LocalDensity.current.fontScale >= 1.6f
    // Owner, 14 Nov: drawer A is 40% of the screen; everything fits by stacking and wrapping.
    val narrow = variant == DrawerChoice.A && !large
    val shape = RoundedCornerShape(40.dp)
    ModalDrawerSheet(
        modifier = Modifier.width(width).fillMaxHeight().statusBarsPadding().navigationBarsPadding()
            .padding(start = 8.dp, top = 8.dp, bottom = 24.dp),
        drawerShape = shape, drawerContainerColor = Color.Transparent, drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().glassPanel(shape).testTag("drawer-${variant.name.lowercase()}")) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = if (narrow) 8.dp else 12.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 12.dp)) {
                val profileAvatar: @Composable (Dp) -> Unit = { size ->
                    if (avatar != null) avatar(size, Color(0xFF424242), Color(0xFFDEDEDE))
                    else UserAvatar(userId.orEmpty(), avatarVersion, hasAvatar, displayName,
                        Color(0xFF424242), Color(0xFFDEDEDE), size = size)
                }
                val identity: @Composable () -> Unit = {
                    DrawerIdentity(displayName, username, if (variant == DrawerChoice.C) host else null,
                        variant == DrawerChoice.C, large, onEdit, onServer, onQR, profileAvatar, publicNumber)
                }
                when {
                    narrow -> DrawerNarrowHeader(displayName, username, onEdit, onQR, profileAvatar, status, accounts, publicNumber)
                    variant == DrawerChoice.A -> DrawerColumnHeader(identity, status, accounts, large)
                    else -> DrawerCardHeader(identity, status, accounts, onQR, large)
                }
                val rowHeight = if (narrow) 44.dp else if (variant == DrawerChoice.A) 48.dp else 52.dp
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DrawerMenuRow("chats", R.string.nav_chats, R.drawable.coco_sidebar_chats,
                        { onDestination(RootDestination.CHATS) }, selected == RootDestination.CHATS && !showingArchive,
                        badges.chats, minHeight = rowHeight, narrow = narrow)
                    DrawerMenuRow("contacts", R.string.contacts_title, R.drawable.coco_sidebar_people,
                        onPeople, minHeight = rowHeight, narrow = narrow)
                    if (showCalls) DrawerMenuRow("calls", R.string.nav_calls, R.drawable.coco_sidebar_calls,
                        { onDestination(RootDestination.CALLS) }, selected == RootDestination.CALLS,
                        badges.missed, danger = true, minHeight = rowHeight, narrow = narrow)
                    DrawerMenuRow("rooms", R.string.conference_rooms_title, R.drawable.coco_sidebar_rooms,
                        onRooms, selected == RootDestination.ROOMS, minHeight = rowHeight, narrow = narrow)
                    if (showSaved) DrawerMenuRow("saved", R.string.saved_messages, R.drawable.coco_sidebar_saved,
                        onSaved, minHeight = rowHeight, narrow = narrow)
                    if (variant == DrawerChoice.A) {
                        DrawerMenuRow("archive", R.string.chats_archive_title, R.drawable.coco_sidebar_archive,
                            onArchive, showingArchive, badges.archive, minHeight = rowHeight, narrow = narrow)
                        DrawerMenuRow("transfers", R.string.direct_manager_title, R.drawable.coco_sidebar_transfers,
                            { onDestination(RootDestination.TRANSFERS) }, selected == RootDestination.TRANSFERS,
                            badges.transfers, plainCount = true, minHeight = rowHeight, narrow = narrow)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = if (narrow) 10.dp else 14.dp), .5.dp, Color(0xFF353535))
                val settings: @Composable (Modifier) -> Unit = { modifier ->
                    DrawerMenuRow("settings", R.string.nav_settings, R.drawable.coco_sidebar_settings,
                        { onDestination(RootDestination.SETTINGS) }, selected == RootDestination.SETTINGS,
                        secondary = true, filled = variant == DrawerChoice.C, narrow = narrow, modifier = modifier)
                }
                val help: @Composable (Modifier) -> Unit = { modifier ->
                    DrawerMenuRow("help", R.string.drawer_help, R.drawable.coco_sidebar_help,
                        onHelp, secondary = true, filled = variant == DrawerChoice.C, narrow = narrow, modifier = modifier)
                }
                if (variant == DrawerChoice.C) {
                    val archive: @Composable (Modifier) -> Unit = { modifier ->
                        DrawerMenuRow("archive", R.string.chats_archive_title, R.drawable.coco_sidebar_archive,
                            onArchive, showingArchive, badges.archive, secondary = true, filled = true, modifier = modifier)
                    }
                    val transfers: @Composable (Modifier) -> Unit = { modifier ->
                        DrawerMenuRow("transfers", R.string.direct_manager_title, R.drawable.coco_sidebar_transfers,
                            { onDestination(RootDestination.TRANSFERS) }, selected == RootDestination.TRANSFERS,
                            badges.transfers, secondary = true, filled = true, plainCount = true, modifier = modifier)
                    }
                    if (large) { archive(Modifier); transfers(Modifier); settings(Modifier); help(Modifier) }
                    else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { archive(Modifier.weight(1f)); transfers(Modifier.weight(1f)) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { settings(Modifier.weight(1f)); help(Modifier.weight(1f)) }
                    }
                } else if (narrow) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    settings(Modifier); help(Modifier)
                    DrawerMenuRow("report", R.string.drawer_report_short, R.drawable.coco_sidebar_report,
                        onReport, secondary = true, narrow = true)
                } else { settings(Modifier); help(Modifier) }
                if (!narrow) DrawerMenuRow("report", R.string.drawer_report_short, R.drawable.coco_sidebar_report,
                    onReport, secondary = true, filled = variant == DrawerChoice.C)
                Spacer(Modifier.weight(1f))
                if (variant == DrawerChoice.A) DrawerHost(host, onServer, narrow)
                Text(stringResource(R.string.drawer_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFFA3A3A3), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("drawer-version"))
            }
        }
    }
}

@Composable private fun DrawerColumnHeader(identity: @Composable () -> Unit, status: @Composable () -> Unit,
    accounts: @Composable (DrawerAccountsLayout) -> Unit, large: Boolean) {
    identity()
    if (large) { status(); accounts(DrawerAccountsLayout.COMPACT) }
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { status() }; accounts(DrawerAccountsLayout.COMPACT)
    }
}

/** Drawer A at 40% width: everything stacks; the name may take two lines, QR sits by the avatar.
 *  The account button sits next to the status when both fit and wraps under it otherwise. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DrawerNarrowHeader(name: String, username: String?, onEdit: () -> Unit, onQR: (() -> Unit)?,
    avatar: @Composable (Dp) -> Unit, status: @Composable () -> Unit, accounts: @Composable (DrawerAccountsLayout) -> Unit,
    publicNumber: String?) {
    val hasNumber = drawerPublicNumber(publicNumber) != null
    val hasUsername = !username.isNullOrBlank()
    val halfTextGap = (DrawerCaptionPolicy.textGapDp / 2).dp
    val rowGap = DrawerCaptionPolicy.rowGapDp.dp
    Column(Modifier.padding(horizontal = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(role = Role.Button, onClick = onEdit).testTag("drawer-profile")) { avatar(48.dp) }
            Spacer(Modifier.weight(1f))
            DrawerQr(onQR, compact = true)
        }
        // The existing visual gaps belong to the tap targets instead of minimum-height rows.
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onEdit)
            .padding(top = rowGap, bottom = if (hasNumber || hasUsername) halfTextGap else rowGap)) {
            DrawerMeasuredCaptionName(name)
        }
        DrawerPublicNumber(publicNumber, compactCaption = true, bottomGap = if (hasUsername) halfTextGap else rowGap)
        if (hasUsername) {
            Box(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onEdit)
                .padding(top = halfTextGap, bottom = rowGap)) { DrawerMeasuredUsername("@$username") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.align(Alignment.CenterVertically)) { status() }
            Box(Modifier.align(Alignment.CenterVertically)) { accounts(DrawerAccountsLayout.COMPACT) }
        }
    }
}

@Composable private fun DrawerMeasuredCaptionName(name: String) {
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current.merge(TextStyle(fontWeight = FontWeight.Bold,
        fontSize = DrawerCaptionPolicy.nameSizeSp.sp, lineHeight = DrawerCaptionPolicy.nameLineHeightSp.sp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chosen = remember(name, maxWidth, density, measure, baseStyle) {
            val constraints = Constraints(maxWidth = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(0) })
            fun style(size: Float) = baseStyle.copy(fontSize = size.sp,
                lineHeight = (DrawerCaptionPolicy.nameLineHeightSp * size / DrawerCaptionPolicy.nameSizeSp).sp)
            val size = DrawerCaptionPolicy.nameSize { fontSize, lines ->
                !measure.measure(name, style(fontSize), constraints = constraints, maxLines = lines,
                    overflow = TextOverflow.Clip, softWrap = true).hasVisualOverflow
            }
            style(size)
        }
        Text(name, style = chosen, color = Color(0xFFF1F1F1), maxLines = DrawerCaptionPolicy.nameMaxLines,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("drawer-name"))
    }
}

@Composable private fun DrawerCardHeader(identity: @Composable () -> Unit, status: @Composable () -> Unit,
    accounts: @Composable (DrawerAccountsLayout) -> Unit, onQR: (() -> Unit)?, large: Boolean) {
    Surface(color = Color.White.copy(alpha = .05f), shape = RoundedCornerShape(30.dp),
        border = BorderStroke(.5.dp, Color.White.copy(alpha = .09f)), modifier = Modifier.testTag("drawer-profile-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            identity()
            val qr: @Composable () -> Unit = { DrawerQr(onQR, compact = false) }
            if (large) { status(); qr() }
            else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { status() }; qr()
            }
            HorizontalDivider(thickness = .5.dp, color = Color.White.copy(alpha = .09f))
            accounts(DrawerAccountsLayout.FULL)
        }
    }
}

@Composable private fun DrawerIdentity(name: String, username: String?, host: String?, card: Boolean, large: Boolean,
    onEdit: () -> Unit, onServer: () -> Unit, onQR: (() -> Unit)?, avatar: @Composable (Dp) -> Unit, publicNumber: String?) {
    val identity: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Box(Modifier.clickable(role = Role.Button, onClick = onEdit).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                DrawerMeasuredName(name, card, large)
            }
            DrawerPublicNumber(publicNumber)
            if (!username.isNullOrBlank()) DrawerMeasuredUsername("@$username")
            if (host != null) DrawerHost(host, onServer)
        }
    }
    val profileAvatar: @Composable () -> Unit = {
        Box(Modifier.clickable(role = Role.Button, onClick = onEdit).testTag("drawer-profile")) { avatar(if (card) 64.dp else 56.dp) }
    }
    if (large) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profileAvatar(); identity(Modifier.fillMaxWidth()); if (!card) DrawerQr(onQR, true)
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        profileAvatar(); identity(Modifier.weight(1f)); if (!card) DrawerQr(onQR, true)
    }
}

@Composable private fun DrawerMeasuredName(name: String, card: Boolean, large: Boolean) {
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        val textStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = (if (card) 20 else 18).sp)
        val chosen = remember(name, maxWidth, density, card, large) {
            val available = with(density) { maxWidth.toPx() }
            val textWidth = measure.measure(name, textStyle, softWrap = false).size.width.coerceAtLeast(1)
            val scale = if (large || !card) 1f else (available / textWidth).coerceIn(.8f, 1f)
            textStyle.copy(fontSize = textStyle.fontSize * scale)
        }
        Text(name, style = chosen, color = Color(0xFFF1F1F1), maxLines = if (large) Int.MAX_VALUE else if (card && chosen.fontSize.value <= 16f) 2 else 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("drawer-name"))
    }
}

@Composable private fun DrawerMeasuredUsername(username: String) {
    val measure = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val (text, style) = remember(username, maxWidth, density) {
            val available = with(density) { maxWidth.toPx() }
            val base = TextStyle(fontSize = 13.sp)
            val full = measure.measure(username, base, softWrap = false).size.width.coerceAtLeast(1)
            val chosen = base.copy(fontSize = (13f * (available / full).coerceIn(11f / 13f, 1f)).sp)
            middleEllipsized(username) { measure.measure(it, chosen, softWrap = false).size.width <= available } to chosen
        }
        Text(text, style = style, maxLines = 1, color = Color(0xFFB0B0B0), modifier = Modifier.semantics { contentDescription = username })
    }
}

@Composable private fun DrawerQr(onQR: (() -> Unit)?, compact: Boolean) {
    Surface(onClick = { onQR?.invoke() }, shape = CircleShape, color = Color.White.copy(alpha = .06f),
        border = BorderStroke(.5.dp, Color.White.copy(alpha = .12f)), modifier = Modifier.testTag("drawer-qr")) {
        Row(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.coco_drawer_qr), stringResource(R.string.sidebar_qr_title), tint = Color(0xFFDEDEDE), modifier = Modifier.size(20.dp))
            if (!compact) Text(stringResource(R.string.drawer_my_qr), color = Color(0xFFDEDEDE), fontSize = 13.sp)
        }
    }
}

@Composable private fun DrawerHost(host: String, onServer: () -> Unit, narrow: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(18.dp))
        .clickable(role = Role.Button, onClick = onServer).padding(horizontal = 8.dp, vertical = 6.dp).testTag("drawer-server"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.coco_sidebar_server), null, tint = Color(0xFFB0B0B0), modifier = Modifier.size(if (narrow) 14.dp else 16.dp))
        Text(drawerHostLabel(host, narrow), color = Color(0xFFB0B0B0), fontSize = if (narrow) 11.sp else 12.sp,
            lineHeight = if (narrow) 14.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            maxLines = if (narrow) 2 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("›", color = Color(0xFFB0B0B0))
    }
}

@Composable private fun DrawerMenuRow(id: String, title: Int, icon: Int?, onClick: () -> Unit,
    selected: Boolean = false, count: Int = 0, danger: Boolean = false, secondary: Boolean = false,
    filled: Boolean = false, plainCount: Boolean = false, minHeight: Dp = 44.dp, narrow: Boolean = false, modifier: Modifier = Modifier) {
    val label = stringResource(title)
    val accessibleLabel = if (id == "report") stringResource(R.string.feedback_title) else label
    val description = if (count > 0) stringResource(R.string.drawer_count_description, accessibleLabel, count) else accessibleLabel
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = onClick, shape = RoundedCornerShape(if (secondary) 22.dp else 26.dp),
        interactionSource = interaction, color = when { selected -> Color(0xFF323232); filled -> Color.White.copy(alpha = .05f); else -> Color.Transparent },
        modifier = modifier.fillMaxWidth().then(cocoQuietPressFeedback(interaction, haptic = false))
            .semantics { this.selected = selected; contentDescription = description }.testTag("drawer-$id")) {
        Row(Modifier.heightIn(min = minHeight).padding(horizontal = if (narrow) 10.dp else 14.dp, vertical = if (narrow) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 10.dp else 14.dp)) {
            if (icon != null) Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, tint = Color(0xFFB7B7B7), modifier = Modifier.size(if (secondary) 18.dp else 20.dp)) }
            else Icon(CocoIcons.File, null, tint = Color(0xFFB7B7B7), modifier = Modifier.size(20.dp))
            // The short report label fits drawer A without changing its approved 40% width.
            Text(label, color = Color(if (secondary) 0xFFDBDBDB else 0xFFF1F1F1),
                fontSize = (if (narrow) (if (secondary) 14 else 15) else if (secondary) 15 else 16).sp,
                lineHeight = if (narrow) 18.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                maxLines = if (id == "report" && LocalDensity.current.fontScale < 1.6f) 1 else Int.MAX_VALUE,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
            drawerBadge(count)?.let { badge ->
                Text(if (plainCount) count.toString() else badge, color = if (plainCount) Color(0xFFB0B0B0) else if (danger) Color.White else Color(0xFF222222),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = if (plainCount) Modifier else Modifier.background(if (danger) Color(0xFFFF4D5F) else LocalCocoColors.current.accent, CircleShape)
                        .padding(horizontal = 7.dp, vertical = 3.dp))
            }
        }
    }
}

/** Like the iOS middle truncation: a long generated first label (a cloud instance id) is cut
 *  to 8 characters so the domain stays visible ("8c86b74c….pub.instances.scw.cloud"). */
internal fun drawerHostLabel(host: String, narrow: Boolean = false): String {
    val labels = host.split('.')
    if (labels.size < 2 || labels.first().length <= 12) return host
    // Narrow drawer: keep the instance prefix and the registrable domain ("8c86b74c…scw.cloud").
    if (narrow && labels.size > 3) return labels.first().take(8) + "…" + labels.takeLast(2).joinToString(".")
    return labels.first().take(8) + "…." + labels.drop(1).joinToString(".")
}

