package com.messenger.app.domain.model

/** Legacy attachment text is a storage projection, never a user-facing label. */
object FilePreviewText {
    data class Preview(val filename: String?, val caption: String? = null) {
        fun display(label: String): String = file(label, filename, caption)
        fun projected(): String = "[File]" + (filename?.takeIf(String::isNotBlank)?.let { " $it" } ?: "") +
            (caption?.takeIf(String::isNotBlank)?.let { "\n\n$it" } ?: "")
    }

    /** Decode this message's stored offer, independently of the live transfer service. */
    fun direct(text: String?, offerJson: String?): Preview? {
        val offer = com.messenger.app.data.directtransfer.DirectFileOffer.decodeStored(offerJson)
        if (offer != null) return Preview(offer.filename, com.messenger.app.data.directtransfer.DirectFileOffer.caption(text))
        return if (offerJson != null || isDirectFallback(text)) Preview(null) else null
    }

    fun isDirectFallback(text: String?): Boolean = listOf("Direct file transfer", "Передача файла напрямую").any {
        text == it || text?.startsWith("$it\n\n") == true
    }

    fun project(text: String, offerJson: String? = null): String = direct(text, offerJson)?.projected() ?: text

    fun preview(text: String): Preview? {
        direct(text, null)?.let { return it }
        if (text != "[File]" && !text.startsWith("[File] ")) return null
        val head = text.substringBefore("\n\n")
        return storedFilename(head)?.let { Preview(it, text.substringAfter("\n\n", "").takeIf(String::isNotBlank)) }
    }

    fun storedFilename(text: String): String? = when {
        text == "[File]" -> ""
        text.startsWith("[File] ") -> text.removePrefix("[File] ").trim()
        else -> null
    }

    fun file(label: String, filename: String?, caption: String? = null): String =
        (filename?.takeIf { it.isNotBlank() }?.let { "$label: $it" } ?: label) +
            (caption?.takeIf(String::isNotBlank)?.let { "\n\n$it" } ?: "")

    fun display(text: String, fileLabel: String): String =
        preview(text)?.display(fileLabel) ?: text

    fun message(message: Message, fileLabel: String): String {
        val preview = message.filePreview ?: message.directFile?.offer?.let {
            Preview(it.filename, com.messenger.app.data.directtransfer.DirectFileOffer.caption(message.text))
        }
        return preview?.display(fileLabel) ?: display(message.text, fileLabel)
    }
}
