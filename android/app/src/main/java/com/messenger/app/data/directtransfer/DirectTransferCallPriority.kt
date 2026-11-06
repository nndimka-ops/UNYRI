package com.messenger.app.data.directtransfer

import com.messenger.app.domain.call.CallUiState

/** Null means the existing call controller has not finished bootstrap: no file admission yet. */
internal fun directTransferCanStream(call: CallUiState?, conferenceReservesCallSlot: Boolean): Boolean =
    (call is CallUiState.Idle || call is CallUiState.Ended || call is CallUiState.Failed) && !conferenceReservesCallSlot
