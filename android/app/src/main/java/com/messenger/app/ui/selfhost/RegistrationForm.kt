package com.messenger.app.ui.selfhost

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.messenger.app.R
import com.messenger.app.data.selfhost.*
import com.messenger.app.ui.auth.authFieldColors

fun inviteFieldVisible(policy: RegistrationPolicy?) = policy?.inviteRequired == true
fun registrationPolicyStale(code: String?) = code in setOf("invite_required", "invite_invalid", "conflict", "registration_closed")
fun registrationFailureMessage(code: String?): Int? = when (code) {
    "invite_required" -> R.string.sh_invite_required
    "invite_invalid" -> R.string.sh_invite_invalid
    "conflict", "registration_closed" -> R.string.sh_registration_closed
    else -> null
}

@Composable
fun InviteCodeField(required: Boolean, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    // No layout node, spacer, semantics or drawing at all on an open server.
    if (!required) return
    OutlinedTextField(value, { onChange(it.take(ServerInviteLink.maxCode)) }, singleLine = true,
        label = { Text(stringResource(R.string.sh_invite_code)) }, enabled = enabled,
        colors = authFieldColors(), modifier = Modifier.fillMaxWidth().testTag("signup-invite"))
}

@Stable
class SignupPolicyState(private val api: SelfHostingApi, private val origin: String) {
    var policy by mutableStateOf<RegistrationPolicy?>(null); private set
    var busy by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    suspend fun load(force: Boolean = false): Boolean {
        if (busy) return false
        busy = true; failed = false
        return try { policy = api.registration(origin, force); true }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { failed = true; false }
        finally { busy = false }
    }
}

@Composable
fun rememberSignupPolicy(origin: String): SignupPolicyState {
    val app = LocalContext.current.applicationContext as com.messenger.app.MessengerApp
    return remember(origin) { SignupPolicyState(app.selfHosting, origin) }
}
