package com.messenger.app.ui.chats

import androidx.annotation.StringRes
import com.messenger.app.R

/** User-facing error categories for chat actions (send/edit/reply/delete/
 *  reaction/forward/save + attachments). Never a raw exception message or
 *  backend string - every value maps to a localized string resource. */
enum class ChatError(@StringRes val resId: Int) {
    ATTACHMENT_SEND(R.string.error_attachment_send),
    ATTACHMENT_DOWNLOAD(R.string.error_attachment_download),
    ATTACHMENT_TOO_LARGE(R.string.error_file_too_large),
    CAMERA_DENIED(R.string.error_camera_denied),
    EDIT(R.string.error_edit_save),
    REPLY(R.string.error_reply_send),
    DELETE(R.string.error_delete),
    REACTION(R.string.error_reaction),
    FORWARD(R.string.error_forward),
    SAVED_OPEN(R.string.error_saved_open),
    SAVE(R.string.error_save),
    IDENTITY_CHANGED(R.string.error_identity_changed),
    NETWORK(R.string.error_network),
    FORWARD_TEXT_POLICY(R.string.error_forward_text_policy),
    TEXT_POLICY(R.string.error_text_policy),
    CAPTION_POLICY(R.string.error_caption_policy),
    GENERIC(R.string.error_generic),
}
