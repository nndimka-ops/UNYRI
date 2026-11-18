package com.messenger.app.data.call

/** Only a configured capture session can advertise a torch. */
data class CallTorchState(val available: Boolean = false, val on: Boolean = false)

internal val noCallTorch = kotlinx.coroutines.flow.MutableStateFlow(CallTorchState())
