package com.messenger.app.ui.selfhost

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.messenger.app.R
import com.messenger.app.data.selfhost.*
import com.messenger.app.ui.security.rejectObscuredTouches
import com.messenger.app.ui.settings.SettingsScaffold
import kotlinx.coroutines.launch

@Composable
fun OwnerClaimScreen(onClose: () -> Unit, onClaimed: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as com.messenger.app.MessengerApp
    DisposableEffect(context) {
        var current: android.content.Context? = context
        while (current is android.content.ContextWrapper && current !is android.app.Activity) current = current.baseContext
        val activity = current as? android.app.Activity
        activity?.let(com.messenger.app.security.ScreenshotPrivacy::apply)
        onDispose { activity?.let(com.messenger.app.security.ScreenshotPrivacy::apply) }
    }
    val controller = app.ownerSetup
    val state by controller.state.collectAsState()
    var code by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var scanningEpoch by remember { mutableStateOf(-1) }
    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
        if (scanningEpoch == app.authRepository.currentSessionEpoch()) result.contents?.let { code = ownerCodeInput(it) }
    }
    // Setup codes are ephemeral, including when the activity backgrounds.
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) code = ""
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { code = ""; lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state) { if (state == ClaimState.Success) { code = ""; onClaimed() } }
    androidx.activity.compose.BackHandler { onClose() }
    SettingsScaffold(stringResource(R.string.sh_claim_title), onClose) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(com.messenger.app.ui.theme.CocoSpacing.md)) {
            Text(com.messenger.app.data.api.ServerEndpoint.current())
            Text(stringResource(R.string.sh_claim_help))
            // The field keeps the plain 16 characters; groups of four are display-only. Rewriting
            // the text with dashes while typing moved the cursor before each new dash and
            // scrambled every fifth character (lead check on the Samsung, 18 Nov).
            OutlinedTextField(code, { raw -> code = ownerCodeInput(raw) },
                visualTransformation = OwnerCodeGroups,
                label = { Text(stringResource(R.string.sh_setup_code)) }, singleLine = true,
                enabled = OwnerClaimPolicy.canSubmit(state), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrect = false,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters))
            TextButton(enabled = OwnerClaimPolicy.canSubmit(state), onClick = {
                scanningEpoch = app.authRepository.currentSessionEpoch()
                scanner.launch(com.journeyapps.barcodescanner.ScanOptions()
                    .setCaptureActivity(com.messenger.app.capture.SharedQrCaptureActivity::class.java)
                    .setOrientationLocked(false).setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setBeepEnabled(false).setBarcodeImageEnabled(false))
            }) { Text(stringResource(R.string.sh_scan_owner_code)) }
            val message = when (state) {
                ClaimState.Invalid -> R.string.sh_setup_invalid
                ClaimState.RateLimited -> R.string.sh_setup_limited
                ClaimState.Unavailable -> R.string.sh_setup_unavailable
                ClaimState.Failed, ClaimState.RoleRefreshFailed -> R.string.error_network
                else -> null
            }
            message?.let { Text(stringResource(it)) }
            // Becoming the server owner is a privileged confirmation: refuse obscured touches (H1 batch).
            Button(enabled = OwnerClaimPolicy.canSubmit(state) && OwnerClaimPolicy.normalize(code) != null,
                modifier = Modifier.rejectObscuredTouches(),
                onClick = { val submitted = code; code = ""; scope.launch { controller.claim(submitted) } }) {
                Text(stringResource(R.string.sh_claim_title))
            }
            if (state == ClaimState.RateLimited || state == ClaimState.Failed) TextButton(onClick = {
                scope.launch { controller.checkAvailability(true) }
            }) { Text(stringResource(R.string.retry)) }
            if (state == ClaimState.RoleRefreshFailed) TextButton(onClick = {
                scope.launch { controller.retryRoleRefresh() }
            }) { Text(stringResource(R.string.retry)) }
        }
    }
}

/** Plain owner-code characters: no separators, uppercase, at most 16. */
internal fun ownerCodeInput(raw: String): String =
    raw.take(64).filterNot { it == '-' || it.isWhitespace() }.uppercase(java.util.Locale.ROOT).take(16)

/** Shows ABCD-EFGH-JKLM-NPQR while the field value stays ABCDEFGHJKLMNPQR. */
internal object OwnerCodeGroups : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val raw = text.text
        val shown = raw.chunked(4).joinToString("-")
        val mapping = object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                (offset + if (offset > 0) (offset - 1) / 4 else 0).coerceIn(0, shown.length)
            override fun transformedToOriginal(offset: Int): Int = (offset - offset / 5).coerceIn(0, raw.length)
        }
        return androidx.compose.ui.text.input.TransformedText(androidx.compose.ui.text.AnnotatedString(shown), mapping)
    }
}
