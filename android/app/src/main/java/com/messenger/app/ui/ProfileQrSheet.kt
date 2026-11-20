package com.messenger.app.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.messenger.app.R
import com.messenger.app.domain.model.PublicUser
import com.messenger.app.domain.model.SearchResult
import com.messenger.app.domain.model.UserProfile
import com.messenger.app.security.PublicProfileLink
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.floor

/** Accept only the public URI emitted by this sheet, never conference capabilities or arbitrary paths. */
internal fun profileLinkUsername(value: String?): String? = PublicProfileLink.parse(Intent.ACTION_VIEW, value)?.username

internal data class ProfileLinkRequest(val username: String, val scope: String, val owner: String? = null,
    val id: String = java.util.UUID.randomUUID().toString())

internal data class ProfileLinkCard(val user: PublicUser, val scope: String, val owner: String)

/** Scope and owner come only from local state at ingress, never from a link or extras. */
internal class ProfileLinkInbox(initial: ProfileLinkRequest? = null,
    private val persist: (ProfileLinkRequest?) -> Unit = {}) {
    var pending by mutableStateOf(initial)
        private set
    var failed by mutableStateOf(false)
        private set
    var card by mutableStateOf<ProfileLinkCard?>(null)
        private set
    private fun update(request: ProfileLinkRequest?) { pending = request; persist(request) }
    fun offer(link: PublicProfileLink, scope: String, owner: String?) {
        card = null
        failed = false; update(ProfileLinkRequest(link.username, scope, owner))
    }
    fun claim(scope: String, owner: String?): ProfileLinkRequest? {
        if (card?.let { it.scope != scope || it.owner != owner } == true) card = null
        val request = pending ?: return null
        if (request.scope != scope || request.owner != null && request.owner != owner) {
            update(null); failed = false; return null
        }
        if (owner == null) return null
        return if (request.owner == null) request.copy(owner = owner).also(::update) else request
    }
    fun isCurrent(request: ProfileLinkRequest, scope: String, owner: String?): Boolean =
        owner != null && pending?.id == request.id && pending?.scope == scope && request.scope == scope &&
            pending?.owner == owner && request.owner == owner
    fun present(request: ProfileLinkRequest, user: PublicUser, scope: String, owner: String?): Boolean {
        if (!isCurrent(request, scope, owner) || !profileLinkMatches(request.username, user)) return false
        card = ProfileLinkCard(user, scope, checkNotNull(owner))
        consume(request) // Persist consumption before showing; a dismissed link cannot replay on recreation.
        return true
    }
    fun visibleCard(scope: String, owner: String?): PublicUser? = card
        ?.takeIf { it.scope == scope && it.owner == owner }?.user
    fun dismissCard() { card = null }
    fun consume(request: ProfileLinkRequest): Boolean {
        if (pending?.id != request.id) return false
        update(null); failed = false; return true
    }
    fun fail(request: ProfileLinkRequest) { if (pending?.id == request.id) failed = true }
    fun retry() { pending?.let { failed = false; update(it.copy(id = java.util.UUID.randomUUID().toString())) } }
}

/** Activity-scoped saved-state inbox covers both onCreate and onNewIntent without holding an Activity. */
internal class ProfileLinkViewModel(state: SavedStateHandle) : ViewModel() {
    val inbox = ProfileLinkInbox(state.get<String>("profile-link-username")?.let { username ->
        state.get<String>("profile-link-scope")?.let { scope ->
            ProfileLinkRequest(username, scope, state["profile-link-owner"],
                state["profile-link-id"] ?: java.util.UUID.randomUUID().toString())
        }
    }) { request ->
        state["profile-link-username"] = request?.username
        state["profile-link-scope"] = request?.scope
        state["profile-link-owner"] = request?.owner
        state["profile-link-id"] = request?.id
    }
}

/** Old link paths are interpreted as public @usernames. Never match a private login.
 * Follow the result cursor, never substitute a fuzzy result.
 * Twenty pages bound work below the server's 30-search/minute quota (50 results per page). */
internal suspend fun findProfileLinkUser(username: String, owner: String, currentOwner: () -> String?,
    lookup: suspend (String, Long) -> Result<SearchResult>, maxPages: Int = 20): PublicUser {
    require(maxPages > 0)
    var offset = 0L
    repeat(maxPages) {
        check(currentOwner() == owner)
        val page = lookup("@" + username.removePrefix("@"), offset).getOrThrow()
        check(currentOwner() == owner)
        check(!page.cached) { "Profile lookup requires a current server result" }
        val matches = page.items.filter { it.publicUsername?.equals(username.removePrefix("@"), ignoreCase = true) == true }
        check(matches.size <= 1) { "Ambiguous profile" }
        matches.singleOrNull()?.let { return it }
        val next = page.nextOffset ?: error("Profile not found")
        check(next > offset) { "Profile search cursor did not advance" }
        offset = next
    }
    error("Profile lookup page limit reached")
}

/** Search excludes self. Resolve the current authenticated profile first, including a renamed username. */
internal suspend fun resolveProfileLink(username: String, owner: String,
    currentOwner: () -> String?, selfId: String, getMe: suspend () -> Result<UserProfile>,
    lookup: suspend (String, Long) -> Result<SearchResult>,
    serverOrigin: String = com.messenger.app.data.api.ServerEndpoint.current()): Result<PublicUser> { return try {
    check(currentOwner() == owner)
    if (com.messenger.app.data.identity.IdentityPolicy.parse(username) != null) {
        check(com.messenger.app.data.identity.IdentityPolicy.lookup(username, serverOrigin) != null)
        val result = lookup(username, 0L).getOrThrow()
        check(currentOwner() == owner && !result.cached)
        return Result.success(result.items.single().also { check(profileLinkMatches(username, it)) })
    }
    val self = getMe().getOrThrow()
    check(currentOwner() == owner && self.id == selfId) { "Profile owner changed" }
    val user = if (self.publicUsername?.equals(username.removePrefix("@"), ignoreCase = true) == true) {
        PublicUser(self.id, self.publicUsername.orEmpty(), self.displayName, self.avatarUrl, self.bio,
            self.profileVersion, self.avatarVersion, self.hasAvatar, self.unyriNumber, self.publicUsername)
    } else findProfileLinkUser(username, owner, currentOwner, lookup)
    Result.success(user)
} catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { Result.failure(failure) } }

@Composable
internal fun ProfileLinkLanding(model: ProfileLinkViewModel, owner: String?, scope: String, canShow: Boolean,
    currentOwner: () -> String?, selfId: String?, getMe: suspend () -> Result<UserProfile>,
    lookup: suspend (String, Long) -> Result<SearchResult>, onOpenChat: (String) -> Unit) {
    val inbox = model.inbox
    val request = inbox.pending
    LaunchedEffect(request?.id, owner, scope, selfId, canShow) {
        val claimed = inbox.claim(scope, owner) ?: return@LaunchedEffect
        if (!canShow || inbox.failed || selfId == null) return@LaunchedEffect
        val result = resolveProfileLink(claimed.username, checkNotNull(owner), currentOwner, selfId, getMe, lookup)
        if (!inbox.isCurrent(claimed, scope, currentOwner())) return@LaunchedEffect
        result.onSuccess { user ->
            inbox.present(claimed, user, scope, currentOwner())
        }.onFailure { inbox.fail(claimed) }
    }
    if (canShow) inbox.visibleCard(scope, owner)?.let { user ->
        ProfileLinkActionCard(user, checkNotNull(owner), selfId, currentOwner,
            { inbox.visibleCard(scope, currentOwner()) == user }, inbox::dismissCard, onOpenChat)
    }
    if (canShow && inbox.failed && inbox.pending != null) AlertDialog(
        onDismissRequest = { inbox.pending?.let(inbox::consume) },
        title = { Text(stringResource(R.string.sidebar_saved_profile)) },
        text = { Text(stringResource(R.string.sidebar_profile_link_failed)) },
        confirmButton = { TextButton(onClick = inbox::retry) { Text(stringResource(R.string.retry)) } },
        dismissButton = { TextButton(onClick = { inbox.pending?.let(inbox::consume) }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun ProfileLinkActionCard(user: PublicUser, owner: String, selfId: String?, currentOwner: () -> String?,
    currentCard: () -> Boolean, onClose: () -> Unit, onOpenChat: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    val contacts = remember(owner) { app.contactsRepository.store }
    val ids by contacts.ids.collectAsState()
    val nonMembers by contacts.nonMembers.collectAsState()
    val changing by contacts.changing.collectAsState()
    val revision by contacts.revision.collectAsState()
    val scope = rememberCoroutineScope()
    val peer = !isOwnPublicProfile(user.id, selfId)
    val actions = remember(owner, user.id, selfId) { ProfileLinkActions(owner, currentOwner, currentCard,
        { id -> app.chatRepository.createOrOpenDirectChat(id).map { it.chatId } },
        { contacts.set(user, true) }, { !isOwnPublicProfile(user.id, selfId) }) }
    val member = user.id in ids
    val known = member || user.id in nonMembers
    LaunchedEffect(contacts, user.id, revision, peer) { if (peer) contacts.check(user) }
        AlertDialog(onDismissRequest = onClose,
            modifier = Modifier.testTag("profile-link-card"),
            title = { Text(user.visibleName) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(com.messenger.app.ui.publicHandle(user))
                user.bio?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (!peer) Text(stringResource(R.string.contacts_self), Modifier.testTag("profile-link-self"))
                if (peer) {
                    TextButton(enabled = !actions.busy, onClick = { scope.launch {
                        actions.message(user.id)?.let { onClose(); onOpenChat(it) }
                    } }) { Text(stringResource(R.string.contacts_write)) }
                    TextButton(enabled = known && !member && !actions.busy && user.id !in changing,
                        onClick = { scope.launch { actions.addContact(member) } }) {
                        Text(stringResource(if (member) R.string.login2_in_contacts else R.string.contacts_add))
                    }
                }
                if (actions.failed) Text(stringResource(R.string.error_generic))
            } },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.dismiss)) } })
}

internal fun profileQrMatrix(link: String): BitMatrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8"))

internal data class ProfileQrContent(val link: String?, val origin: String?)

/** The caption and QR payload share the identity's advertised origin, including explicit overrides. */
/** B-033: only the public number makes a QR (a legacy username may be the private login); B-039: its server address under it. */
internal fun profileQrContent(number: String?, origin: String?): ProfileQrContent {
    val link = if (number != null && origin != null) com.messenger.app.data.identity.IdentityPolicy.link(number, origin) else null
    return ProfileQrContent(link, origin.takeIf { link != null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileQrSheet(displayName: String, username: String?, onEdit: () -> Unit, onClose: () -> Unit,
    number: String? = null, origin: String? = null, title: String? = null, instruction: String? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as com.messenger.app.MessengerApp
    val identity by app.identityRepository.identity.collectAsState()
    val legacy by app.identityRepository.legacy.collectAsState()
    val n = if (legacy) null else number ?: identity?.number
    val server = origin ?: identity?.publicOrigin
    // B-033: never a legacy username (it may be the private login). B-039: the server address under the QR, only with a QR.
    val publicName = if (number != null) username else if (n != null) identity?.username else null
    val content = remember(n, server) { profileQrContent(n, server) }
    val link = content.link
    val qr by produceState<BitMatrix?>(null, link) {
        value = link?.let { withContext(Dispatchers.Default) { profileQrMatrix(it) } }
    }
    var shareFailed by remember { mutableStateOf(false) }
    val shareTitle = stringResource(R.string.sidebar_qr_share)
    // 14 Nov (owner photo): the sheet ran under the status bar, so the clock and battery were
    // drawn over "Мой QR-код" and "Готово". Like the iOS large sheet, it starts below the bar.
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.statusBarsPadding().padding(top = 8.dp),
        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp), containerColor = Color(0xFF1C1C1E),
        contentColor = Color(0xFFF1F1F1), dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding().testTag("profile-qr-sheet")) {
            ProfileQrHeader(onClose, title ?: stringResource(R.string.sidebar_qr_title))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val matrix = qr
                if (matrix != null) ProfileQrImage(matrix)
                else if (link == null) {
                    Text(stringResource(R.string.sidebar_qr_no_username), Modifier.padding(horizontal = 32.dp), textAlign = TextAlign.Center)
                    TextButton(onClick = { onClose(); onEdit() }) { Text(stringResource(R.string.sidebar_saved_profile)) }
                } else Spacer(Modifier.size(252.dp)) // One finite background computation; no idle spinner.
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(displayName, Modifier.padding(horizontal = 24.dp), fontSize = 20.sp, lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    if (n != null) Text(com.messenger.app.data.identity.IdentityPolicy.formattedNumber(n), Modifier.padding(horizontal = 24.dp))
                    if (!publicName.isNullOrEmpty()) Text("@$publicName", Modifier.padding(horizontal = 24.dp),
                        color = Color(0xFF98989F), fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                Text(instruction ?: stringResource(R.string.sidebar_qr_instruction), Modifier.padding(horizontal = 32.dp),
                    fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF98989F), textAlign = TextAlign.Center)
                content.origin?.let { publicOrigin ->
                    Text(publicOrigin, Modifier.padding(horizontal = 32.dp).testTag("profile-qr-origin"),
                        fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF98989F), textAlign = TextAlign.Center)
                }
                // A real button (it was grey text on a grey pill): white label on a lighter glass pill.
                if (link != null) Row(Modifier.heightIn(min = 50.dp).background(Color.White.copy(alpha = .14f), CircleShape)
                    .border(.5.dp, Color.White.copy(alpha = .14f), CircleShape)
                    .clickable(role = Role.Button) {
                        shareFailed = runCatching {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link)
                            }, shareTitle))
                        }.isFailure
                    }.padding(horizontal = 22.dp, vertical = 12.dp).testTag("profile-qr-share"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProfileShareIcon(Color(0xFFF1F1F1))
                    Text(shareTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF1F1F1))
                }
                if (link != null) TextButton(onClick = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("UNYRI", link))
                }) { Text(stringResource(R.string.copy)) }
                if (shareFailed) Text(stringResource(R.string.error_generic), Modifier.padding(horizontal = 32.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 13.sp)
            }
        }
    }
}

@Composable private fun ProfileQrHeader(onClose: () -> Unit, heading: String) {
    val actionWidth = 88.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        @Composable fun title(modifier: Modifier = Modifier) {
            Text(heading, modifier, fontSize = 17.sp,
                lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        @Composable fun done(modifier: Modifier = Modifier) {
            Text(stringResource(R.string.sidebar_done), modifier.heightIn(min = 44.dp)
                .background(Color.White.copy(alpha = .10f), CircleShape)
                .border(.5.dp, Color.White.copy(alpha = .10f), CircleShape)
                .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("profile-qr-done"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (maxWidth < actionWidth * 2 + 128.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title(Modifier.fillMaxWidth()); done(Modifier.align(Alignment.End))
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(actionWidth)); title(Modifier.weight(1f)); done(Modifier.width(actionWidth))
        }
    }
}

@Composable
private fun ProfileQrImage(matrix: BitMatrix) {
    val description = stringResource(R.string.sidebar_qr_code)
    Box(Modifier.widthIn(max = 252.dp).fillMaxWidth().aspectRatio(1f)
        .background(Color.White, RoundedCornerShape(24.dp)).padding(16.dp)) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = description }.testTag("profile-qr-image").drawWithCache {
            val unit = floor(minOf(size.width, size.height) / matrix.width).coerceAtLeast(1f)
            val inset = (size.width - matrix.width * unit) / 2f
            val path = Path().apply {
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
                    addRect(androidx.compose.ui.geometry.Rect(inset + x * unit, inset + y * unit,
                        inset + (x + 1) * unit, inset + (y + 1) * unit))
                }
            }
            onDrawBehind { drawPath(path, Color.Black) }
        })
    }
}

@Composable internal fun ProfileQrIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 24f
        for ((x, y) in listOf(2f to 2f, 14f to 2f, 2f to 14f)) {
            drawRoundRect(color, Offset(x * unit, y * unit), Size(7 * unit, 7 * unit),
                androidx.compose.ui.geometry.CornerRadius(unit), style = Stroke(1.7f * unit))
            drawRect(color, Offset((x + 2.5f) * unit, (y + 2.5f) * unit), Size(2 * unit, 2 * unit))
        }
        for ((x, y) in listOf(14f to 14f, 19f to 14f, 14f to 19f, 19f to 19f, 17f to 17f)) {
            drawRect(color, Offset(x * unit, y * unit), Size(2.5f * unit, 2.5f * unit))
        }
    }
}

@Composable private fun ProfileShareIcon(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(6*u, 9*u); lineTo(4*u, 9*u); lineTo(4*u, 22*u); lineTo(20*u, 22*u); lineTo(20*u, 9*u); lineTo(18*u, 9*u)
            moveTo(12*u, 16*u); lineTo(12*u, 1*u); moveTo(7*u, 6*u); lineTo(12*u, 1*u); lineTo(17*u, 6*u)
        }
        drawPath(path, color, style = Stroke(1.7f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
