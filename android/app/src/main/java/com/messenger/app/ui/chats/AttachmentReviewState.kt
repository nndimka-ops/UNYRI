package com.messenger.app.ui.chats

import com.messenger.app.data.attachment.ComposerTraySnapshot

internal enum class AttachmentPickAction { CAPACITY, EDITING, REVIEW, SEND_NOW }

/** Every picker result is admitted before transport is considered. Direct picks
 * always enter review, even if a picker exposes a Send now action. */
internal fun attachmentPickAction(existing: Int, incoming: Int, editing: Boolean, busy: Boolean,
    captionValid: Boolean, direct: Boolean, sendNow: Boolean): AttachmentPickAction = when {
    incoming + existing > 10 -> AttachmentPickAction.CAPACITY
    editing -> AttachmentPickAction.EDITING
    direct || !sendNow || busy || !captionValid -> AttachmentPickAction.REVIEW
    else -> AttachmentPickAction.SEND_NOW
}

internal data class ComposerAttachmentPick(val sources: List<String>, val asFile: Boolean, val direct: Boolean)
internal enum class AttachmentPickRejection { DUPLICATE, CAPACITY, EDITING, NOT_READY, DESTINATION }
internal data class AttachmentPickResult(val accepted: Boolean, val rejection: AttachmentPickRejection? = null)

/** Production picker entry point: both admission and the chosen side effect live
 * here, so testing a policy enum alone cannot bypass staging or direct originals. */
internal fun admitComposerAttachmentPick(sources: List<String>, asFile: Boolean, direct: Boolean, sendNow: Boolean,
    known: Set<String>, existing: Int, editing: Boolean, busy: Boolean, ready: Boolean, captionValid: Boolean,
    directEligible: Boolean, cleanup: () -> Unit,
    review: (ComposerAttachmentPick) -> Boolean, send: (ComposerAttachmentPick) -> Boolean): AttachmentPickResult {
    fun reject(reason: AttachmentPickRejection): AttachmentPickResult { cleanup(); return AttachmentPickResult(false, reason) }
    val candidates = sources.distinct().filterNot { it in known }
    if (candidates.isEmpty()) return reject(AttachmentPickRejection.DUPLICATE)
    val action = attachmentPickAction(existing, candidates.size, editing, busy, captionValid, direct, sendNow)
    if (action == AttachmentPickAction.CAPACITY) return reject(AttachmentPickRejection.CAPACITY)
    if (action == AttachmentPickAction.EDITING) return reject(AttachmentPickRejection.EDITING)
    if (!ready) return reject(AttachmentPickRejection.NOT_READY)
    if (direct && !directEligible) return reject(AttachmentPickRejection.DESTINATION)
    val pick = ComposerAttachmentPick(candidates, asFile || direct, direct)
    return AttachmentPickResult(if (action == AttachmentPickAction.SEND_NOW) send(pick) else review(pick))
}

/** ChatScreen's production media Send wiring. A marked photo is always direct,
 * including legacy/restored photo drafts in Standard/High gallery quality. */
internal fun dispatchComposerAttachmentSend(tray: ComposerTraySnapshot, quality: String, direct: Set<String>,
    needsDirect: (String) -> Boolean, offer: (List<String>) -> Unit, ordinary: () -> Unit): Int {
    val photos = tray.sendablePhotos.toSet()
    val files = tray.sendableFiles.toSet()
    val sources = (tray.ordered + tray.sendablePhotos + tray.sendableFiles).distinct().filter { it in photos || it in files }
    val originals = sources.filter { it in direct || ((quality == "file" || it in files) && needsDirect(it)) }
    if (originals.isEmpty()) ordinary() else offer(originals.take(8))
    return originals.size
}

/** Shortcut state belongs to this picker only; only choose() may persist. */
internal data class GalleryPickerMode(val preferred: String, val mediaAsFiles: Boolean = false) {
    val quality: String get() = if (mediaAsFiles) "file" else preferred
    fun asFiles() = copy(mediaAsFiles = true)
    fun choose(quality: String, persist: (String) -> Unit): GalleryPickerMode {
        persist(quality)
        return GalleryPickerMode(quality)
    }
}
