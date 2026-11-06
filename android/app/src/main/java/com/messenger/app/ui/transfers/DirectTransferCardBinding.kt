package com.messenger.app.ui.transfers

import com.messenger.app.data.directtransfer.DirectFilePresentation

/** Actual compact-card click binding. Ordinary Retry never substitutes for a manual intent.
 * Coordinator methods revalidate current ownership/revisions before doing any work. */
internal fun directTransferManualClick(
    value: DirectFilePresentation,
    selectionMode: Boolean,
    onSelection: () -> Unit,
    pause: (String) -> Unit,
    resume: (String) -> Unit,
) {
    val action = DirectTransferUiPolicy.manualAction(value) ?: return
    if (selectionMode) onSelection()
    else when (action) {
        DirectTransferManualAction.PAUSE -> pause(value.offer.transferId)
        DirectTransferManualAction.CONTINUE -> resume(value.offer.transferId)
    }
}
