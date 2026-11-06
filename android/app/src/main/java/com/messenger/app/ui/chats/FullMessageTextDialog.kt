package com.messenger.app.ui.chats

import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import com.messenger.app.R
import com.messenger.app.data.message.MessageTextPolicy
import com.messenger.app.ui.theme.LocalCocoColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Derived display only. Transport, copy and stored message content stay untouched. */
@Composable
internal fun cocoMessagePresentationText(raw: String, inline: Boolean = false): String =
    remember(raw, inline) { MessageTextPolicy.presentationResult(raw, inline) }.let { result ->
        when (result.kind) {
            MessageTextPolicy.PresentationKind.EMPTY -> stringResource(R.string.coco_text_empty)
            MessageTextPolicy.PresentationKind.INVALID -> stringResource(R.string.coco_text_unsupported)
            MessageTextPolicy.PresentationKind.TEXT -> result.text
        }
    }

@Composable
internal fun cocoMessageRichText(raw: String): AnnotatedString {
    val displayed = cocoMessagePresentationText(raw)
    return remember(displayed) { chatRichText(displayed) }
}

/** Explicitly requested, literal, read-only native text. No HTML, URL handlers or input keyboard. */
@Composable
internal fun FullMessageTextDialog(raw: String, onDismiss: () -> Unit) {
    val colors = LocalCocoColors.current
    var native by remember(raw) { mutableStateOf<TextView?>(null) }
    var loading by remember(raw) { mutableStateOf(true) }
    var unsupported by remember(raw) { mutableStateOf(false) }
    val height = (LocalConfiguration.current.screenHeightDp * .5f).coerceIn(100f, 460f).dp
    LaunchedEffect(raw, native) {
        val view = native ?: return@LaunchedEffect
        loading = true
        unsupported = false
        try {
            // Validate before native shaping; this also protects old/imported content.
            MessageTextPolicy.received(raw)
            val params = TextViewCompat.getTextMetricsParams(view)
            val prepared = withContext(Dispatchers.Default) { PrecomputedTextCompat.create(raw, params) }
            TextViewCompat.setPrecomputedText(view, prepared)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unsupported = true
        } finally { if (native === view) loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.primaryText,
        textContentColor = colors.primaryText,
        title = { Text(stringResource(R.string.coco_text_full_title)) },
        text = {
            Box(Modifier.fillMaxWidth().height(height)) {
                if (unsupported) Text(stringResource(R.string.coco_text_unsupported))
                else key(raw) { AndroidView(
                    modifier = Modifier.fillMaxWidth().height(height),
                    factory = { context ->
                        ScrollView(context).apply {
                            isFillViewport = true
                            outlineProvider = ViewOutlineProvider.BOUNDS
                            clipToOutline = true
                            addView(TextView(context).apply {
                                tag = "coco-full-message-text-native"
                                setTextSize(17f)
                                setTypeface(Typeface.DEFAULT)
                                setTextColor(colors.primaryText.toArgb())
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                inputType = InputType.TYPE_NULL
                                keyListener = null
                                showSoftInputOnFocus = false
                                setSingleLine(false)
                                setTextIsSelectable(true)
                                autoLinkMask = 0
                                linksClickable = false
                                setHorizontallyScrolling(false)
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                native = this
                            })
                        }
                    },
                    update = { scroll -> (scroll.getChildAt(0) as TextView).setTextColor(colors.primaryText.toArgb()) },
                ) }
                if (loading && !unsupported) CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.primaryText)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss), color = colors.primaryText) } },
    )
}
