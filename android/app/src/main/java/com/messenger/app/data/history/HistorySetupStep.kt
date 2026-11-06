package com.messenger.app.data.history

/** A next-step hint, not authorization. Verified keys and server revisions are
 * still required by HistoryKeySettingsSession for every history operation. */
internal enum class HistorySetupStep {
    prepareKey, confirmKey, enterExistingKey, unfinishedUpload, allowUploads, createBackup, ready;

    companion object {
        fun next(keySaved: Boolean, hasDraft: Boolean, hasBackup: Boolean,
                 hasUnfinishedUpload: Boolean, uploadsAllowed: Boolean): HistorySetupStep = when {
            hasDraft -> confirmKey
            !keySaved && hasBackup -> enterExistingKey
            !keySaved && hasUnfinishedUpload -> unfinishedUpload
            !keySaved -> prepareKey
            !uploadsAllowed -> allowUploads
            hasBackup -> ready
            else -> createBackup
        }
    }
}
