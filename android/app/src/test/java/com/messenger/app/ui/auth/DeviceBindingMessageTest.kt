package com.messenger.app.ui.auth

import com.messenger.app.R
import com.messenger.app.data.api.AuthApiException
import com.messenger.app.data.auth.deviceBindingFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceBindingMessageTest {
    @Test fun `required server and unsupported hardware selects the localized explanation`() {
        val failure = deviceBindingFailure(AuthApiException.Api("session_revoked", "Revoked", 401), true) as AuthApiException.Api
        assertEquals(R.string.auth_hardware_binding_required, authTerminalMessage(failure.code))
        assertEquals(R.string.auth_session_ended, authTerminalMessage("session_revoked"))
    }
}
