package com.messenger.app.ui.chats

import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private val chatHttpLinkPattern = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)

/** Address-only preview: never contacts the sender's website automatically. */
internal fun chatLinkAddress(source: String): java.net.URI? =
    chatHttpLinkPattern.findAll(com.messenger.app.data.message.MessageTextPolicy.presentation(source)).mapNotNull { match ->
        runCatching { java.net.URI(match.value.trimEnd('.', ',', ';', '!', '?', ')', ']')) }.getOrNull()
            ?.takeIf { !it.host.isNullOrBlank() && it.userInfo == null }
    }.firstOrNull()

/** Small, deterministic inline syntax. No HTML or remote preview requests. */
internal fun chatRichText(raw: String): AnnotatedString {
    val source = com.messenger.app.data.message.MessageTextPolicy.presentation(raw)
    if (!com.messenger.app.data.message.MessageTextPolicy.allowsRichParsing(source)) return AnnotatedString(source)
    val builder = AnnotatedString.Builder()
    var index = 0
    while (index < source.length) {
        val marker = listOf("**", "`", "*").firstOrNull { source.startsWith(it, index) }
        val end = marker?.let { source.indexOf(it, index + it.length) } ?: -1
        if (marker != null && end > index + marker.length) {
            val style = when (marker) {
                "**" -> SpanStyle(fontWeight = FontWeight.Bold)
                "*" -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> SpanStyle(fontFamily = FontFamily.Monospace)
            }
            builder.pushStyle(style); builder.append(source.substring(index + marker.length, end)); builder.pop()
            index = end + marker.length
        } else { builder.append(source[index]); index++ }
    }
    val rendered = builder.toString()
    chatHttpLinkPattern.findAll(rendered).forEach { match ->
        val url = match.value.trimEnd('.', ',', ';', '!', '?', ')', ']')
        if (url.isNotEmpty()) builder.addLink(LinkAnnotation.Url(url,
            styles = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))), match.range.first, match.range.first + url.length)
    }
    return builder.toAnnotatedString()
}
