package com.messenger.app.data.sync

import com.messenger.app.data.signal.MalformedSignalCiphertextException
import com.messenger.app.data.signal.UnsupportedSignalMessageTypeException
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.InvalidVersionException
import org.signal.libsignal.protocol.LegacyMessageException

/** Only known terminal parser/protocol failures leave automatic selection. */
internal fun retainedDecryptErrorClass(error: Exception): String = when (error) {
    is DuplicateMessageException -> "permanent_duplicate"
    is MalformedSignalCiphertextException, is InvalidMessageException -> "permanent_malformed_ciphertext"
    is UnsupportedSignalMessageTypeException, is InvalidVersionException, is LegacyMessageException -> "permanent_unsupported_type"
    else -> error.javaClass.simpleName
}
