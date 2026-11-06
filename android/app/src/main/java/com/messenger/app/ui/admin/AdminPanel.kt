package com.messenger.app.ui.admin

import com.messenger.app.ui.theme.cocoConversationBackground

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.messenger.app.BuildConfig
import com.messenger.app.MessengerApp
import com.messenger.app.R
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Admin UX Pack 1 - Android administration root.
//
// The security model is intentionally unchanged:
//   * FLAG_SECURE for the whole panel;
//   * a separate `x-coco-admin` session on top of the normal bearer token;
//   * password + authenticator/recovery code + Ed25519 paper-key proof;
//   * admin token lives in memory only and is dropped on background/lock;
//   * HTTPS-only, redirects rejected, signing key pinned, session epoch checked;
//   * owner/moderator separation enforced on the server, reflected in the UI.
//
// What changed is the information architecture: the single long screen became a
// nested navigation graph with role-aware sections (see AdminNavigation).
// ---------------------------------------------------------------------------

/** Constructed only after the server's own-profile response grants an admin role. */
@Composable
fun AdminPanel(role: String, onClose: () -> Unit) {
    // Defence in depth: the caller already gates this on a granted role, but a
    // stale or revoked role must not render a single administration screen.
    if (!AdminNavigation.isAdministrator(role)) return
    val colors = LocalCocoColors.current
    val context = LocalContext.current
    val app = context.applicationContext as MessengerApp
    DisposableEffect(context) {
        var current: android.content.Context? = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
        val window = (current as? android.app.Activity)?.window
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val alreadySecure = ((window?.attributes?.flags ?: 0) and flag) != 0
        window?.addFlags(flag)
        onDispose { if (!alreadySecure) window?.clearFlags(flag) }
    }
    val scope = rememberCoroutineScope()
    val epoch = remember { app.authRepository.currentSessionEpoch() }
    val client = remember {
        okhttp3.OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    val base = remember { ServerEndpoint.resolve(BuildConfig.API_BASE_URL) }
    val userId = remember { (app.authRepository.authState.value as? com.messenger.app.domain.model.AuthSessionState.LoggedIn)?.user?.id ?: "" }
    val pins = remember { app.getSharedPreferences("admin_public_pins", android.content.Context.MODE_PRIVATE) }
    val pinName = remember { "$base|$userId" }
    val cache = remember { AdminCache() }
    var serverId by remember { mutableStateOf("") }
    var serverName by remember { mutableStateOf("") }
    var keyMismatch by remember { mutableStateOf(false) }
    var keyConfigured by remember { mutableStateOf(false) }
    var authPolicy by remember { mutableStateOf(AdminFactorPolicy.Both) }
    var authProtocol by remember { mutableIntStateOf(1) }
    var totpConfigured by remember { mutableStateOf(false) }
    var unsupportedPolicy by remember { mutableStateOf(false) }
    var paperKey by remember { mutableStateOf("") }
    var paperSaved by remember { mutableStateOf(false) }
    var showPaper by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }
    var replacementKey by remember { mutableStateOf("") }
    var replacementSaved by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var recoveryMode by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf<String?>(null) }
    var setupNeeded by remember { mutableStateOf(false) }
    var setupReady by remember { mutableStateOf(false) }
    var statusLoaded by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var recovery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AdminPresentation.Failure?>(null) }
    var diagnostic by remember { mutableStateOf<String?>(null) }
    var requestStage by remember { mutableStateOf("LOCAL") }
    var lastAction by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    var panelGeneration by remember { mutableIntStateOf(0) }
    var backgroundAt by remember { mutableStateOf<Long?>(null) }
    var backgroundExpiry by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var formExpired by remember { mutableStateOf(false) }
    var regenerate by remember { mutableStateOf(false) }
    var copiedSecret by remember { mutableStateOf(false) }
    var manualPaper by remember { mutableStateOf(false) }
    var setupExpiresAt by remember { mutableStateOf<Long?>(null) }
    val clipboard = remember { context.getSystemService(android.content.ClipboardManager::class.java) }
    val clipboardLabel = remember { "UNYRI-admin-" + java.util.UUID.randomUUID() }
    var clipboardExpiresAt by remember { mutableLongStateOf(0) }
    fun clearOwnClipboard() {
        runCatching {
            if (clipboard.primaryClipDescription?.label?.toString() == clipboardLabel) {
                if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                else clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
        }
    }
    fun copySetupSecret() {
        if (secret.isBlank()) return
        val clip = android.content.ClipData.newPlainText(clipboardLabel, secret)
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        clipboardExpiresAt = android.os.SystemClock.elapsedRealtime() + 60_000L
        copiedSecret = true
        scope.launch {
            kotlinx.coroutines.delay(60_000L)
            if (android.os.SystemClock.elapsedRealtime() >= clipboardExpiresAt) clearOwnClipboard()
        }
    }

    /** Single place that drops every piece of sensitive UI state. */
    fun clear() {
        backgroundExpiry?.cancel(); backgroundAt = null; regenerate = false; manualPaper = false
        copiedSecret = false; setupExpiresAt = null; clearOwnClipboard()
        paperKey = ""; paperSaved = false; showPaper = false; rotating = false
        replacementKey = ""; replacementSaved = false
        panelGeneration++; token = null; password = ""; code = ""; recoveryMode = false
        secret = ""; qr = null; recovery = ""; setupReady = false
        cache.clear()
    }

    // Local functions must be declared before use, so the raw fetch comes first
    // and everything else builds on it.
    /** Raw bytes (screenshot endpoint); never parsed as JSON and never logged. */
    suspend fun adminFetch(path: String, body: String?): ByteArray {
        val operation = panelGeneration
        requestStage = when {
            path.startsWith("status") -> "STATUS"
            path.startsWith("setup") -> "SETUP"
            path.startsWith("key-challenge") -> "CHALLENGE"
            path.startsWith("unlock") -> "UNLOCK"
            else -> "ACTION"
        }
        check(epoch == app.authRepository.currentSessionEpoch())
        val current = ServerEndpoint.resolve(BuildConfig.API_BASE_URL)
        require(current.startsWith("https://"))
        val access = app.authRepository.currentAccessToken() ?: error("Signed out")
        check(epoch == app.authRepository.currentSessionEpoch())
        val adminToken = token
        val result = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url("$current/admin/$path").header("Authorization", "Bearer $access")
            adminToken?.let { builder.header("X-COCO-Admin", it) }
            if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
            client.newCall(builder.build()).execute().use { response ->
                // The body is read before the status check so a failure response
                // never leaves the connection unread; only the code is surfaced.
                val payload = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) throw AdminHttpFailure(response.code)
                payload
            }
        }
        check(epoch == app.authRepository.currentSessionEpoch() && operation == panelGeneration)
        lastAction = android.os.SystemClock.elapsedRealtime()
        return result
    }

    /** Raw administration call used by the typed transport. */
    suspend fun adminCall(path: String, body: String? = null): String =
        String(adminFetch(path, body), Charsets.UTF_8)

    /** Raw bytes (screenshot endpoint); used directly by the lazy image loader. */
    suspend fun requestBytes(path: String): ByteArray = adminFetch(path, null)

    suspend fun request(path: String, body: JSONObject? = null): JSONObject =
        JSONObject(adminCall(path, body?.toString()))

    // The single typed entry point every administration screen uses. It is
    // rebuilt on each composition so it always closes over the current panel
    // state (token, generation, account epoch).
    val transport = AdminTransport(
        text = { path, body -> adminCall(path, body) },
        blob = { path -> requestBytes(path) },
    )

    var consoleFailure by remember { mutableStateOf<AdminPresentation.Failure?>(null) }

    // ADMIN CONSOLE PACK 3 - native -> browser handoff.
    //
    // The console is opened by the system browser, so the browser needs its own
    // credential. The unlocked native session mints a single-use handoff, the
    // browser redeems it for an HttpOnly console cookie, and the handoff itself
    // is discarded here. No administration token is ever written to the URL, the
    // clipboard, a log or storage.
    fun openConsole() {
        scope.launch {
            if (busy) return@launch
            busy = true
            consoleFailure = null
            try {
                val reply = transport.post<AdminEmptyBody, AdminHandoffDto>(
                    "console-handoff",
                    AdminEmptyBody,
                )
                val url = AdminConsolePolicy.consoleUrl(base, reply.handoff)
                    ?: throw AdminHttpFailure(0)
                // `scope` is main-dispatched and the network call above already
                // returned to it, so the browser is started on the UI thread.
                AdminConsole.open(context, url)
                // Only the native session lives on; the panel stays unlocked, and
                // the browser session is one more thing the same lock will end.
                lastAction = android.os.SystemClock.elapsedRealtime()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                consoleFailure = failure.failureKind()
            } finally {
                busy = false
            }
        }
    }

    /** Public, unauthenticated card. Used only for the dashboard server name. */
    suspend fun publicServerName(): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(base.trimEnd('/') + "/server-info").build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val source = response.body?.source() ?: return@runCatching null
                source.request(65537)
                if (source.buffer.size > 65536) return@runCatching null
                JSONObject(source.buffer.readUtf8()).optString("name").takeIf { it.isNotBlank() && it != "null" }
            }
        }.getOrNull()
    }

    suspend fun loadStatus() {
        val data = request("status")
        require(data.optInt("key_protocol") == 1)
        val id = data.getString("server_id")
        val public = data.optString("signing_public_key").takeIf { it.isNotBlank() && it != "null" }
        authProtocol = data.optInt("auth_protocol", 1)
        val observedPolicy = AdminFactorPolicy.fromStatus(authProtocol, data.optString("auth_policy"))
        unsupportedPolicy = observedPolicy == null
        requireNotNull(observedPolicy) { "Unsupported administrator authentication policy" }
        val previous = pins.getString(pinName, null)
        val pending = pins.getString("$pinName|pending", null)
        val observed = "$id|$public"
        keyMismatch = !if (observedPolicy.paper) AdminSigningKey.acceptsPin(previous, pending, id, public)
            else AdminFactorPolicy.acceptsServerPin(previous, pending, id)
        require(!keyMismatch) { "Administrator key changed" }
        if (pending == observed) check(pins.edit().putString(pinName, observed).remove("$pinName|pending").commit())
        serverId = id; keyConfigured = public != null
        totpConfigured = if (authProtocol >= 2) data.optBoolean("totp_configured") else data.getBoolean("configured")
        authPolicy = if (!data.getBoolean("configured") && !keyConfigured && !totpConfigured && authProtocol >= 2)
            AdminFactorPolicy.Authenticator else observedPolicy
        setupNeeded = !data.getBoolean("configured"); statusLoaded = true
    }

    suspend fun proof(purpose: String, proposed: String, oldKey: String): JSONObject {
        val public = AdminSigningKey.publicKey(proposed)
        val challenge = request("key-challenge", JSONObject().put("purpose", purpose).put("public_key", public))
        require(
            challenge.getString("server_id") == serverId && challenge.getString("user_id") == userId &&
                challenge.getString("purpose") == purpose && challenge.getString("public_key") == public
        )
        val payload = AdminSigningKey.payload(
            serverId, userId, challenge.getString("auth_session"),
            challenge.getString("challenge_id"), purpose, public, challenge.getString("nonce"),
        )
        return JSONObject().put("challenge_id", challenge.getString("challenge_id"))
            .put("signature", AdminSigningKey.sign(oldKey, payload)).apply {
                if (purpose == "rotate") put("new_signature", AdminSigningKey.sign(proposed, payload))
            }
    }

    /** Runs a privileged action behind the shared busy / error / 401 handling. */
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; diagnostic = null
        val operation = panelGeneration
        scope.launch {
            try {
                action()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (failure: Exception) {
                if (operation == panelGeneration) {
                    val network = failure is java.io.IOException
                    diagnostic = "ADMIN-$requestStage-${(failure as? AdminHttpFailure)?.status ?: if (network) "NETWORK" else "LOCAL"}"
                    // Only localized messages are ever rendered; raw status codes
                    // stay in the diagnostic token above.
                    error = AdminPresentation.failure((failure as? AdminHttpFailure)?.status, network)
                    // A rejected or expired admin session must never keep panel access.
                    token = null
                    cache.clear()
                }
            } finally {
                if (operation == panelGeneration) busy = false
            }
        }
    }

    LaunchedEffect(Unit) { run { loadStatus() } }
    LaunchedEffect(Unit) { serverName = runCatching { publicServerName() }.getOrNull().orEmpty() }
    LaunchedEffect(token, lastAction) { if (token != null) { kotlinx.coroutines.delay(15 * 60 * 1000L); clear() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // Never retain an unlocked admin session in the background.
                    if (token != null) { clear(); busy = false }
                    else {
                        panelGeneration++; client.dispatcher.cancelAll(); busy = false
                        code = ""; showPaper = false; error = null
                        backgroundAt = android.os.SystemClock.elapsedRealtime()
                        backgroundExpiry?.cancel()
                        backgroundExpiry = scope.launch {
                            kotlinx.coroutines.delay(AdminSetupPolicy.resumeWindowMs)
                            clear(); formExpired = true
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (AdminSetupPolicy.expired(backgroundAt, now)) { clear(); formExpired = true }
                    backgroundExpiry?.cancel(); backgroundExpiry = null; backgroundAt = null
                    if (now >= clipboardExpiresAt) clearOwnClipboard()
                    if (epoch != app.authRepository.currentSessionEpoch()) { clear(); busy = false; statusLoaded = false }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); client.dispatcher.cancelAll(); clear() }
    }
    LaunchedEffect(setupExpiresAt) {
        val deadline = setupExpiresAt ?: return@LaunchedEffect
        kotlinx.coroutines.delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        secret = ""; qr = null; setupReady = false; setupExpiresAt = null; copiedSecret = false; clearOwnClipboard()
    }

    if (regenerate) AlertDialog(
        onDismissRequest = { regenerate = false },
        title = { Text(stringResource(R.string.admin_regenerate_title)) },
        text = { Text(stringResource(R.string.admin_regenerate_body),Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = {
                paperKey = AdminSigningKey.generate(); paperSaved = false
                showPaper = true; manualPaper = false; regenerate = false
            }) { Text(stringResource(R.string.admin_regenerate),color=colors.danger) }
        },
        dismissButton = { TextButton(onClick = { regenerate = false }) { Text(stringResource(R.string.admin_cancel_setup)) } },
    )

    Box(Modifier.fillMaxSize().cocoConversationBackground(colors)) {
        if (token == null) {
            AdminLockedBody(
                role = role,
                base = base,
                keyMismatch = keyMismatch,
                authPolicy = authPolicy,
                choosePolicy = authProtocol >= 2 && setupNeeded && !setupReady && !keyConfigured && !totpConfigured,
                onPolicy = { authPolicy = it },
                unsupportedPolicy = unsupportedPolicy,
                error = error,
                diagnostic = diagnostic,
                busy = busy,
                statusLoaded = statusLoaded,
                keyConfigured = keyConfigured,
                setupNeeded = setupNeeded,
                setupReady = setupReady,
                formExpired = formExpired,
                password = password,
                onPassword = { password = it; formExpired = false },
                code = code,
                onCode = { code = it.trim().take(128) },
                recoveryMode = recoveryMode,
                onToggleRecovery = { recoveryMode = !recoveryMode; code = "" },
                paperKey = paperKey,
                onPaperKey = { paperKey = it; paperSaved = false },
                paperSaved = paperSaved,
                onPaperSaved = { paperSaved = it },
                showPaper = showPaper,
                onShowPaper = { showPaper = it },
                manualPaper = manualPaper,
                onManualPaper = { manualPaper = true },
                onGeneratePaper = { paperKey = AdminSigningKey.generate(); paperSaved = false; showPaper = true },
                onRegenerate = { regenerate = true },
                secret = secret,
                qr = qr,
                copiedSecret = copiedSecret,
                onCopySecret = ::copySetupSecret,
                onStartSetup = {
                    run {
                        val body = JSONObject().put("password", password).apply { if (authProtocol >= 2) put("auth_policy", authPolicy.wire) }
                        val data = request("setup", body)
                        secret = data.optString("secret").takeUnless { it == "null" }.orEmpty()
                        val encoded = data.optString("qr_png").takeUnless { it == "null" }.orEmpty()
                        qr = if (encoded.isBlank()) null else android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).let {
                            android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)
                        }
                        setupReady = true
                        setupExpiresAt = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L
                        copiedSecret = false
                    }
                },
                onRefresh = { run { statusLoaded = false; loadStatus() } },
                onUnlock = {
                    run {
                        val body = JSONObject().put("password", password).apply {
                            if (authProtocol >= 2) put("auth_policy", authPolicy.wire)
                            if (authPolicy.authenticator) put("code", code)
                        }
                        var public: String? = null
                        if (authPolicy.paper) {
                            public = AdminSigningKey.publicKey(paperKey)
                            body.put("key_proof", proof("unlock", paperKey, paperKey))
                            check(pins.edit().putString("$pinName|pending", "$serverId|$public").commit())
                        }
                        val data = request("unlock", body)
                        if (authPolicy.paper) check(pins.edit().putString(pinName, "$serverId|$public").remove("$pinName|pending").commit())
                        else if (pins.getString(pinName, null) == null) check(pins.edit().putString(pinName, "$serverId|null").commit())
                        paperKey = ""; paperSaved = false; showPaper = false; keyConfigured = authPolicy.paper
                        token = data.getString("token"); password = ""; code = ""
                        secret = ""; qr = null; setupNeeded = false; setupExpiresAt = null; clearOwnClipboard()
                        val codes = data.getJSONArray("recovery_codes")
                        recovery = (0 until codes.length()).joinToString("\n") { codes.getString(it) }
                    }
                },
                onClose = { clear(); onClose() },
            )
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (keyMismatch) Text(
                    stringResource(R.string.admin_key_changed),
                    Modifier.fillMaxWidth().padding(horizontal = CocoSpacing.lg, vertical = CocoSpacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.danger,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surfaceVariant)
                Box(Modifier.weight(1f)) {
                    val nav = rememberNavController()
                    fun go(target: AdminTarget) {
                        if (!AdminNavigation.canOpen(role, target)) return
                        error = null; diagnostic = null
                        nav.navigate(target.route)
                    }
                    fun push(target: AdminTarget, id: String) {
                        if (!AdminNavigation.canOpen(role, target)) return
                        error = null; diagnostic = null
                        nav.navigate(AdminRoutes.detailPath(target, id))
                    }
                    NavHost(nav, startDestination = AdminTarget.Home.route) {
                        composable(AdminTarget.Home.route) {
                            AdminHomeScreen(
                                role = role, base = base, serverName = serverName, serverId = serverId,
                                cache = cache, busy = busy, transport = transport,
                                run = ::run,
                                onOpen = ::go,
                                onOpenConsole = { openConsole() },
                                consoleFailure = consoleFailure,
                                onClose = { clear(); onClose() },
                            )
                        }
                        composable(AdminTarget.Reports.route) {
                            AdminReportsScreen(
                                cache = cache, busy = busy, transport = transport, run = ::run,
                                onUnauthorized = { clear() },
                                onBack = { nav.popBackStack() },
                                onOpenDetail = { push(AdminTarget.ReportDetail, it) },
                            )
                        }
                        composable(AdminRoutes.detailRoute(AdminTarget.ReportDetail)) { entry ->
                            AdminReportDetailScreen(
                                reportId = entry.arguments?.getString(AdminRoutes.ARG_ID).orEmpty(),
                                cache = cache, busy = busy, error = error, diagnostic = diagnostic,
                                transport = transport, run = ::run,
                                onBack = { nav.popBackStack() },
                                onOpenUser = { push(AdminTarget.UserDetail, it) },
                            )
                        }
                        composable(AdminTarget.Users.route) {
                            AdminUsersScreen(
                                cache = cache, busy = busy, transport = transport, run = ::run,
                                onUnauthorized = { clear() },
                                onBack = { nav.popBackStack() },
                                onOpenDetail = { push(AdminTarget.UserDetail, it) },
                            )
                        }
                        composable(AdminRoutes.detailRoute(AdminTarget.UserDetail)) { entry ->
                            AdminModerationScreen(
                                userId = entry.arguments?.getString(AdminRoutes.ARG_ID).orEmpty(),
                                cache = cache, busy = busy, transport = transport, run = ::run,
                                onUnauthorized = { clear() },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(AdminTarget.Feedback.route) {
                            AdminFeedbackScreen(
                                cache = cache, busy = busy, transport = transport, run = ::run,
                                onUnauthorized = { clear() },
                                onBack = { nav.popBackStack() },
                                onOpenDetail = { push(AdminTarget.FeedbackDetail, it) },
                            )
                        }
                        composable(AdminRoutes.detailRoute(AdminTarget.FeedbackDetail)) { entry ->
                            AdminFeedbackDetailScreen(
                                feedbackId = entry.arguments?.getString(AdminRoutes.ARG_ID).orEmpty(),
                                cache = cache, busy = busy, error = error, diagnostic = diagnostic,
                                transport = transport, run = ::run,
                                onBack = { nav.popBackStack() },
                                onOpenUser = { push(AdminTarget.UserDetail, it) },
                            )
                        }
                        composable(AdminTarget.Storage.route) {
                            AdminStorageScreen(
                                cache = cache, busy = busy, error = error, diagnostic = diagnostic,
                                transport = transport, run = ::run, onBack = { nav.popBackStack() },
                            )
                        }
                        composable(AdminTarget.Audit.route) {
                            AdminAuditScreen(
                                cache = cache, busy = busy, transport = transport, run = ::run,
                                onUnauthorized = { clear() },
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable(AdminTarget.ServerSettings.route) {
                            AdminServerSettingsScreen(
                                cache = cache, busy = busy, error = error, diagnostic = diagnostic,
                                transport = transport, run = ::run, onBack = { nav.popBackStack() },
                            )
                        }
                        composable(AdminTarget.Security.route) {
                            AdminSecurityScreen(
                                role = role, serverId = serverId, busy = busy,
                                paperEnabled = authPolicy.paper,
                                recoveryCodes = recovery, onDismissRecovery = { recovery = "" },
                                rotating = rotating, onToggleRotate = {
                                    rotating = !rotating; paperKey = ""; replacementKey = ""; replacementSaved = false
                                },
                                paperKey = paperKey, onPaperKey = { paperKey = it },
                                replacementKey = replacementKey, onReplacementKey = { replacementKey = it },
                                replacementSaved = replacementSaved, onReplacementSaved = { replacementSaved = it },
                                onGenerateReplacement = { replacementKey = AdminSigningKey.generate(); replacementSaved = false },
                                onRotate = {
                                    run {
                                        val public = AdminSigningKey.publicKey(replacementKey)
                                        val signed = proof("rotate", replacementKey, paperKey)
                                        check(pins.edit().putString("$pinName|pending", "$serverId|$public").commit())
                                        request("key-rotate", signed)
                                        check(pins.edit().putString(pinName, "$serverId|$public").remove("$pinName|pending").commit())
                                        clear(); loadStatus()
                                    }
                                },
                                onLock = { run { request("lock", JSONObject()); clear() } },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Locked / setup state. Presentation was refreshed; every validation rule,
// storage decision and failure path is unchanged from the audited version.
// ---------------------------------------------------------------------------
@Composable
private fun AdminLockedBody(
    role: String,
    base: String,
    keyMismatch: Boolean,
    authPolicy: AdminFactorPolicy,
    choosePolicy: Boolean,
    onPolicy: (AdminFactorPolicy) -> Unit,
    unsupportedPolicy: Boolean,
    error: AdminPresentation.Failure?,
    diagnostic: String?,
    busy: Boolean,
    statusLoaded: Boolean,
    keyConfigured: Boolean,
    setupNeeded: Boolean,
    setupReady: Boolean,
    formExpired: Boolean,
    password: String,
    onPassword: (String) -> Unit,
    code: String,
    onCode: (String) -> Unit,
    recoveryMode: Boolean,
    onToggleRecovery: () -> Unit,
    paperKey: String,
    onPaperKey: (String) -> Unit,
    paperSaved: Boolean,
    onPaperSaved: (Boolean) -> Unit,
    showPaper: Boolean,
    onShowPaper: (Boolean) -> Unit,
    manualPaper: Boolean,
    onManualPaper: () -> Unit,
    onGeneratePaper: () -> Unit,
    onRegenerate: () -> Unit,
    secret: String,
    qr: android.graphics.Bitmap?,
    copiedSecret: Boolean,
    onCopySecret: () -> Unit,
    onStartSetup: () -> Unit,
    onRefresh: () -> Unit,
    onUnlock: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalCocoColors.current
    Column(
        Modifier.fillMaxSize().cocoConversationBackground(colors).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(CocoSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(CocoSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.admin_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.primaryText,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CocoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    AdminStatusChip(stringResource(R.string.admin_session_locked), colors.warning)
                    AdminStatusChip(
                        stringResource(if (role == AdminNavigation.OWNER) R.string.admin_role_owner else R.string.admin_role_moderator),
                        colors.accentSoft,
                    )
                }
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.admin_close)) }
        }

        if (!base.startsWith("https://")) Text(stringResource(R.string.admin_https), color = colors.danger)
        if (unsupportedPolicy) Text(stringResource(R.string.admin_factor_unsupported), color = colors.danger)
        if (keyMismatch) Text(stringResource(R.string.admin_key_changed), color = colors.danger)
        else error?.let { Text(stringResource(it.message), color = colors.danger) }
        if (error != null) diagnostic?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.veryMutedText) }
        if (!busy && !statusLoaded) {
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.retry), color = colors.accentSoft) }
        }
        if (!statusLoaded && error == null && busy) AdminLoadingState()

        if (statusLoaded) {
            Text(
                stringResource(R.string.admin_factor_password_required),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText,
            )
            if (formExpired) Text(stringResource(R.string.admin_form_expired), color = colors.warning)

            OutlinedTextField(
                value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.admin_password)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy,
            )

            Text(stringResource(when (authPolicy) {
                AdminFactorPolicy.Authenticator -> R.string.admin_factor_authenticator
                AdminFactorPolicy.PaperKey -> R.string.admin_factor_paper
                AdminFactorPolicy.Both -> R.string.admin_factor_both
            }), style = MaterialTheme.typography.titleMedium)
            if (choosePolicy) {
                AdminFactorPolicy.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option == authPolicy, onClick = { onPolicy(option) }, enabled = !busy)
                        TextButton(onClick = { onPolicy(option) }, enabled = !busy) { Text(stringResource(when (option) {
                            AdminFactorPolicy.Authenticator -> R.string.admin_factor_authenticator
                            AdminFactorPolicy.PaperKey -> R.string.admin_factor_paper
                            AdminFactorPolicy.Both -> R.string.admin_factor_both
                        })) }
                    }
                }
            }
            Text(stringResource(R.string.admin_factor_windows), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)

            if (setupNeeded) AdminSetupCard(if (authPolicy.authenticator) R.string.admin_step_auth else R.string.admin_factor_prepare) {
                if (authPolicy.authenticator) Text(stringResource(R.string.admin_auth_help), style = MaterialTheme.typography.bodyMedium)
                if (!setupReady) {
                    Button(enabled = !busy && password.isNotBlank(), onClick = onStartSetup) {
                        Text(stringResource(R.string.admin_factor_continue_setup))
                    }
                }
                if (setupReady && secret.isNotBlank()) {
                    Text(stringResource(R.string.admin_secret_label), style = MaterialTheme.typography.labelLarge)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(secret, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Button(onClick = onCopySecret, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.admin_copy_secret))
                    }
                    if (copiedSecret) Text(stringResource(R.string.admin_copied_secret), color = colors.accentSoft)
                    qr?.let {
                        Image(it.asImageBitmap(), stringResource(R.string.admin_setup), Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                    }
                    Text(stringResource(R.string.admin_setup_timeout), style = MaterialTheme.typography.bodySmall)
                }
                if (setupReady && authPolicy.authenticator && secret.isBlank()) Text(stringResource(R.string.admin_factor_existing_authenticator))
            }

            if (authPolicy.paper) AdminSetupCard(if (keyConfigured) R.string.admin_paper_key else R.string.admin_step_key) {
                Text(
                    stringResource(if (keyConfigured) R.string.admin_paper_hint else R.string.admin_key_once),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (setupNeeded && !setupReady) Text(stringResource(R.string.admin_factor_finish_prepare))
                else {
                    if (!keyConfigured && paperKey.isBlank() && !manualPaper) {
                        Button(enabled = !busy, onClick = onGeneratePaper) { Text(stringResource(R.string.admin_key_generate)) }
                        TextButton(onClick = onManualPaper) { Text(stringResource(R.string.admin_use_written_key)) }
                    } else {
                        AdminPaperKeyFields(paperKey, onPaperKey, showPaper, !busy)
                        Row {
                            Checkbox(showPaper, onShowPaper)
                            Text(stringResource(R.string.admin_key_show), Modifier.align(Alignment.CenterVertically))
                        }
                        if (!keyConfigured) {
                            Row {
                                Checkbox(paperSaved, onPaperSaved, enabled = AdminSigningKey.valid(paperKey))
                                Text(stringResource(R.string.admin_key_saved), Modifier.weight(1f).align(Alignment.CenterVertically))
                            }
                            TextButton(enabled = !busy, onClick = onRegenerate) { Text(stringResource(R.string.admin_regenerate)) }
                        }
                        if (paperKey.isNotBlank() && !AdminSigningKey.valid(paperKey)) {
                            Text(stringResource(R.string.admin_key_invalid), color = colors.warning)
                        }
                    }
                }
            }

            if (authPolicy.authenticator) {
            Text(
                stringResource(if (setupNeeded || !keyConfigured) R.string.admin_step_enter else R.string.admin_code),
                style = MaterialTheme.typography.titleMedium,
                color = colors.primaryText,
            )
            Text(stringResource(R.string.admin_code_help), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
            Text(stringResource(R.string.admin_factor_fresh_code), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
            Text(stringResource(R.string.admin_switch_hint), style = MaterialTheme.typography.bodySmall, color = colors.mutedText)
            if (!setupNeeded) {
                TextButton(enabled = !busy, onClick = onToggleRecovery) {
                    Text(stringResource(if (recoveryMode) R.string.admin_use_otp else R.string.admin_use_recovery))
                }
            }
            OutlinedTextField(
                value = code, onValueChange = onCode, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (recoveryMode) R.string.admin_recovery_input else R.string.admin_otp_input)) },
                singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = if (recoveryMode) androidx.compose.ui.text.input.KeyboardType.Ascii
                    else androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                ),
            )
            if (code.isNotEmpty() && !AdminSetupPolicy.validCode(code, recoveryMode)) {
                Text(
                    stringResource(if (recoveryMode) R.string.admin_recovery_format else R.string.admin_otp_format),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                )
            }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !unsupportedPolicy && !keyMismatch && password.isNotBlank() &&
                    (!authPolicy.authenticator || AdminSetupPolicy.validCode(code, recoveryMode)) &&
                    (!authPolicy.paper || (AdminSigningKey.valid(paperKey) && (keyConfigured || paperSaved))) && (!setupNeeded || setupReady),
                onClick = onUnlock,
            ) { Text(stringResource(R.string.admin_unlock)) }
        }
    }
}

@Composable
internal fun AdminPaperKeyFields(value: String, onChange: (String) -> Unit, visible: Boolean, enabled: Boolean) {
    val parts = value.split("-").let { if (it.size == 4) it else AdminSigningKey.normalize(value).chunked(8) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(4) { index ->
            if (index > 0) Text("–")
            Surface(
                Modifier.weight(1f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = parts.getOrElse(index) { "" },
                    onValueChange = { input ->
                        val raw = AdminSigningKey.normalize(input)
                        if (raw.length > 8) onChange(raw.take(32).chunked(8).joinToString("-"))
                        else onChange((0..3).map { if (it == index) raw else parts.getOrElse(it) { "" } }.joinToString("-"))
                    },
                    enabled = enabled, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun AdminSetupCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
