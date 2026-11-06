package com.messenger.app.data.history

import com.messenger.app.data.api.AuthApiException

/** Stable numeric metadata only; never include an exception message or a QR value. */
internal enum class HistoryTransferPhase(val code: Int) {
    REQUIREMENTS(1), PREPARE(2), CREATE_REQUEST(3), SCAN(4), VALIDATE_CODE(5),
    DEVICE_UNLOCK(6), APPROVE(7), WAIT(8), ACCEPT(9), RESTORE(10), RENDER_QR(11),
}

internal enum class HistoryTransferFailureCategory(val code: Int) {
    WRONG_SERVER(1), WRONG_ACCOUNT(2), EXPIRED(3), UNSUPPORTED_SERVER(4), WRONG_KEY(5),
    SOURCE_NOT_READY(6), SOURCE_CHANGED(7), MEDIA_UNAVAILABLE(8), SPACE(9), KEY_MISSING(10),
    UPLOADS_DISABLED(11), SESSION(12), REQUEST_MISSING(13), CONFLICT(14), RATE_LIMIT(15),
    SERVER(16), REQUEST_REJECTED(17), NETWORK(18), DEVICE_LOCK(19), CAMERA(20), INVALID_CODE(21), UNEXPECTED(22),
}

internal data class HistoryTransferFailure(val category: HistoryTransferFailureCategory, val httpStatus: Int = 0) {
    fun reference(phase: HistoryTransferPhase) = "HT-${phase.code}-${category.code}"

    /** Existing server allowlist: history_load + phase/operation/status/success. */
    fun metrics(phase: HistoryTransferPhase) = mapOf(
        "phase" to phase.code.toDouble(), "operation" to (7400 + category.code).toDouble(),
        "status" to httpStatus.takeIf { it in 400..599 }.orZero().toDouble(), "success" to 0.0,
    )
}

private fun Int?.orZero() = this ?: 0

internal fun classifyHistoryTransferFailure(failure: Throwable): HistoryTransferFailure {
    val status = when (failure) {
        is AuthApiException.Api -> failure.httpStatus
        is AuthApiException.Network -> failure.httpStatus
        else -> null
    }?.takeIf { it in 400..599 } ?: 0
    val category = when (failure) {
        is HistoryTransferValidationFailure -> when (failure.reason) {
            HistoryTransferValidationFailure.Reason.WRONG_SERVER -> HistoryTransferFailureCategory.WRONG_SERVER
            HistoryTransferValidationFailure.Reason.WRONG_ACCOUNT -> HistoryTransferFailureCategory.WRONG_ACCOUNT
            HistoryTransferValidationFailure.Reason.EXPIRED -> HistoryTransferFailureCategory.EXPIRED
        }
        is com.messenger.app.data.api.HistoryBackupUnsupportedServer -> HistoryTransferFailureCategory.UNSUPPORTED_SERVER
        is javax.crypto.AEADBadTagException -> HistoryTransferFailureCategory.WRONG_KEY
        is HistorySourceNotReady -> HistoryTransferFailureCategory.SOURCE_NOT_READY
        is HistorySourceChanged -> HistoryTransferFailureCategory.SOURCE_CHANGED
        is HistoryMediaUnavailable -> HistoryTransferFailureCategory.MEDIA_UNAVAILABLE
        is HistoryInsufficientSpace -> HistoryTransferFailureCategory.SPACE
        is HistoryRecoveryKeyMissing -> HistoryTransferFailureCategory.KEY_MISSING
        is HistoryUploadsDisabled -> HistoryTransferFailureCategory.UPLOADS_DISABLED
        else -> when (status) {
            401, 403 -> HistoryTransferFailureCategory.SESSION
            404, 410 -> HistoryTransferFailureCategory.REQUEST_MISSING
            409 -> HistoryTransferFailureCategory.CONFLICT
            429 -> HistoryTransferFailureCategory.RATE_LIMIT
            in 500..599 -> HistoryTransferFailureCategory.SERVER
            in 400..499 -> HistoryTransferFailureCategory.REQUEST_REJECTED
            else -> if (failure is AuthApiException.Network || failure is java.io.IOException) HistoryTransferFailureCategory.NETWORK
                else HistoryTransferFailureCategory.UNEXPECTED
        }
    }
    return HistoryTransferFailure(category, status)
}
