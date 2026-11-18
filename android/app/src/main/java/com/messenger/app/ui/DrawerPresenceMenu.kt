package com.messenger.app.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.LocalResolvedSidebarStyle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class DrawerPresence { ONLINE, INVISIBLE, DO_NOT_DISTURB }

internal fun drawerPresence(messages: Boolean, visibility: String?): DrawerPresence = when {
    !messages -> DrawerPresence.DO_NOT_DISTURB
    visibility == "nobody" -> DrawerPresence.INVISIBLE
    else -> DrawerPresence.ONLINE
}

/** Recheck inside the repository's serialized transform, after waiting for its mutex/network. */
internal fun drawerPresenceUpdate(snapshot: com.messenger.app.data.profile.PrivacySnapshot,
    commandOwner: String?, currentOwner: String?, visibility: String): com.messenger.app.data.profile.PrivacySnapshot {
    check(commandOwner != null && commandOwner == currentOwner) { "Session changed" }
    return snapshot.copy(onlineVisibility = visibility)
}

/** One privacy write at a time. Like iOS, the local alert choice takes effect offline. */
internal class DrawerPresenceController(
    private val owner: String?,
    private val currentOwner: () -> String?,
    private val updateVisibility: suspend (String) -> Result<Unit>,
    private val setMessages: (Boolean) -> Unit,
    private val currentVisibility: () -> String? = { null },
) {
    var busy by mutableStateOf(false)
        private set

    suspend fun choose(value: DrawerPresence): Boolean {
        if (busy || owner == null || currentOwner() != owner) return false
        busy = true
        return try {
            // This store belongs to the current account before the first suspension.
            // Privacy failure must not undo a deliberate local Do not disturb choice.
            setMessages(value != DrawerPresence.DO_NOT_DISTURB)
            val visibility = if (value == DrawerPresence.INVISIBLE) "nobody" else "everyone"
            if (currentVisibility() == visibility) true
            else updateVisibility(visibility).isSuccess && currentOwner() == owner
        } finally { busy = false }
    }
}

/** Owned by the root navigation entry, rather than the disposable drawer composition. */
internal class DrawerPresenceViewModel(
    val controller: DrawerPresenceController,
    private val refresh: suspend () -> Boolean,
) : ViewModel() {
    var failed by mutableStateOf(false)
        private set
    private var command: Job? = null
    private var hydration: Job? = null
    private var commandIssued = false

    fun choose(value: DrawerPresence) {
        if (command?.isActive == true) return
        commandIssued = true
        command = viewModelScope.launch { failed = !controller.choose(value) }
    }

    fun refreshIfNeeded() {
        if (commandIssued || hydration?.isActive == true) return
        hydration = viewModelScope.launch {
            val succeeded = refresh()
            if (!commandIssued) failed = !succeeded
        }
    }
}

/** Only UI readers are mounted in the visible drawer. Commands outlive closing/backgrounding. */
@Composable
internal fun DrawerPresenceMenu(app: MessengerApp, compact: Boolean = true) {
    val repository = app.privacyRepository
    val owner = repository.ownerKey()
    val model = viewModel<DrawerPresenceViewModel>(key = "drawer-presence/$owner", factory = remember(app, owner) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DrawerPresenceViewModel(
                DrawerPresenceController(owner, repository::ownerKey,
                    { visibility -> repository.update { drawerPresenceUpdate(it, owner, repository.ownerKey(), visibility) }.map { Unit } },
                    { messages -> app.notificationSettings.update { it.copy(messages = messages) } },
                    { repository.state.value?.onlineVisibility }),
                { repository.ownerKey() == owner && (repository.state.value != null || repository.refresh().isSuccess) },
            ) as T
        }
    })
    val privacy by repository.state.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    val alerts by app.notificationSettings.settings.collectAsStateWithLifecycle(minActiveState = androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(model) { if (owner != null && privacy == null) model.refreshIfNeeded() }
    Column {
        DrawerPresenceControl(drawerPresence(alerts.messages, privacy?.onlineVisibility), model.controller.busy, compact, model::choose)
        if (model.failed) Text(stringResource(R.string.sidebar_status_error), color = sidebarInk(LocalResolvedSidebarStyle.current.typographyMuted),
            fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun DrawerPresenceControl(value: DrawerPresence, busy: Boolean, onChoose: (DrawerPresence) -> Unit) =
    DrawerPresenceControl(value, busy, true, onChoose)

@Composable
internal fun DrawerPresenceControl(value: DrawerPresence, busy: Boolean, compact: Boolean, onChoose: (DrawerPresence) -> Unit) {
    val style = LocalResolvedSidebarStyle.current
    val ink = sidebarInk(style.typographyText)
    val muted = sidebarInk(style.typographyMuted)
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(presenceTitle(value))
    Box(Modifier.testTag("drawer-presence")) {
        // Preserve the 32dp capsule; foundation click handling expands its touch hit area.
        Box(Modifier.heightIn(min = 44.dp).clickable(enabled = !busy, role = Role.Button, onClick = { expanded = true })
            .semantics { stateDescription = title }.testTag("drawer-status"), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.background(Color.White.copy(alpha = .07f), CircleShape)
                .heightIn(min = if (compact) 32.dp else 44.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(when (value) {
                    DrawerPresence.ONLINE -> Color(style.avatarOnlineColor)
                    DrawerPresence.INVISIBLE -> Color(0xFF8E8E93)
                    DrawerPresence.DO_NOT_DISTURB -> Color(0xFFDB5C5C)
                }, CircleShape))
                Text(title, color = ink, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                    fontFamily = sidebarFont(style.typographyFamily), modifier = Modifier.weight(1f, fill = false))
                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height * .3f); lineTo(size.width / 2f, size.height * .8f); lineTo(size.width, size.height * .3f)
                    }
                    drawPath(path, ink, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
        if (expanded) Popup(alignment = Alignment.TopStart, onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true)) {
            Column(Modifier.widthIn(max = 250.dp).background(Color(0xFA282828), RoundedCornerShape(32.dp))
                .border(.5.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(32.dp))
                .padding(vertical = 10.dp).testTag("drawer-status-menu")) {
                DrawerPresence.entries.forEach { option ->
                    val optionTitle = stringResource(presenceTitle(option))
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .clickable(enabled = !busy, role = Role.RadioButton) {
                            expanded = false; CocoHaptics.select(view); onChoose(option)
                        }.semantics { selected = option == value }.padding(horizontal = 24.dp, vertical = 9.dp)
                        .testTag("drawer-status-${option.name}"), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (option == value) Icon(CocoIcons.Check, null, tint = muted, modifier = Modifier.size(22.dp))
                        else if (option == DrawerPresence.INVISIBLE) Icon(CocoIcons.EyeOff, null, tint = muted, modifier = Modifier.size(22.dp))
                        else if (option == DrawerPresence.DO_NOT_DISTURB) DrawerMoonIcon(muted)
                        else Box(Modifier.size(22.dp).padding(5.dp).background(muted, CircleShape))
                        Text(optionTitle, color = ink, fontSize = 17.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun presenceTitle(value: DrawerPresence): Int = when (value) {
    DrawerPresence.ONLINE -> R.string.sidebar_status_online
    DrawerPresence.INVISIBLE -> R.string.sidebar_status_invisible
    DrawerPresence.DO_NOT_DISTURB -> R.string.sidebar_status_dnd
}

@Composable private fun DrawerMoonIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * .7f, size.height * .8f)
            cubicTo(-size.width * .1f, size.height, -size.width * .1f, size.height * .1f, size.width * .45f, 0f)
            cubicTo(size.width * .2f, size.height * .5f, size.width * .5f, size.height * .75f, size.width * .9f, size.height * .6f)
            close()
        }
        drawPath(path, color)
    }
}
