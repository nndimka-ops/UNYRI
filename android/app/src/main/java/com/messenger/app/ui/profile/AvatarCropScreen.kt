package com.messenger.app.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import com.messenger.app.ui.settings.SettingsDialogButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.messenger.app.R
import com.messenger.app.data.avatar.AvatarCropMath
import com.messenger.app.data.avatar.AvatarImageProcessor
import com.messenger.app.data.avatar.CropRect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f

/**
 * E.1: lightweight internal avatar crop (square output, pan, pinch zoom,
 * circular preview mask, Cancel/Done). No ACTION_CROP, no third-party image
 * editor - pure Compose. The crop geometry is delegated to the JVM-tested
 * [AvatarCropMath]; only the Bitmap IO stays here.
 */
@Composable
fun AvatarCropDialog(
    sourceUri: Uri,
    onDone: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }

    // Hoisted crop state, readable by the Done button (in the header Row).
    val geometry = remember { CropGeometry() }
    var zoom by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(sourceUri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { AvatarImageProcessor.decodeForEdit(context, sourceUri) }.getOrNull()
        }
        decodeFailed = bitmap == null
        zoom = MIN_ZOOM
        offset = Offset.Zero
    }

    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }

    Dialog(onDismissRequest = { if (!processing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !processing, onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        val bmp = bitmap ?: return@TextButton
                        val rect = AvatarCropMath.cropRect(
                            bmp.width, bmp.height, geometry.viewport, geometry.cover * zoom, offset.x, offset.y,
                        )
                        processing = true
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) { cropToJpeg(bmp, rect) }
                            processing = false
                            onDone(bytes)
                        }
                    },
                    enabled = !processing && bitmap != null,
                ) {
                    Text(stringResource(R.string.done))
                }
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(), contentAlignment = Alignment.Center) {
                when {
                    processing -> CircularProgressIndicator(color = Color.White)
                    decodeFailed -> Text(stringResource(R.string.profile_avatar_decode_failed), color = Color.White)
                    bitmap != null -> {
                        val bmp = bitmap!!
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val density = LocalDensity.current
                            val viewportDp = maxWidth.coerceAtMost(maxHeight * 0.9f)
                            // Crop math operates in PIXELS (same unit as the
                            // source bitmap), so convert the viewport side.
                            val viewport = with(density) { viewportDp.toPx() }
                            val cover = remember(bmp, viewport) { AvatarCropMath.coverScale(bmp.width, bmp.height, viewport) }
                            geometry.viewport = viewport
                            geometry.cover = cover
                            val baseW = with(density) { (bmp.width * cover).toDp() }
                            val baseH = with(density) { (bmp.height * cover).toDp() }

                            Box(
                                modifier = Modifier
                                    .size(viewportDp)
                                    .align(Alignment.Center)
                                    .border(2.dp, Color.White, CircleShape)
                                    .pointerInput(bmp, cover) {
                                        detectTransformGestures { _, pan, gestureZoom, _ ->
                                            zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                            val newScale = cover * zoom
                                            offset = Offset(
                                                (offset.x + pan.x).coerceIn(
                                                    -AvatarCropMath.maxPanX(bmp.width, viewport, newScale),
                                                    AvatarCropMath.maxPanX(bmp.width, viewport, newScale),
                                                ),
                                                (offset.y + pan.y).coerceIn(
                                                    -AvatarCropMath.maxPanY(bmp.height, viewport, newScale),
                                                    AvatarCropMath.maxPanY(bmp.height, viewport, newScale),
                                                ),
                                            )
                                        }
                                    },
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(baseW, baseH)
                                        .align(Alignment.Center)
                                        .graphicsLayer {
                                            scaleX = zoom
                                            scaleY = zoom
                                            translationX = offset.x
                                            translationY = offset.y
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Per-dialog crop geometry written by the viewport, read by the Done button. */
private class CropGeometry {
    var viewport by mutableStateOf(0f)
    var cover by mutableStateOf(1f)
}

private fun cropToJpeg(bitmap: Bitmap, rect: CropRect): ByteArray {
    val square = AvatarImageProcessor.cropToSquare(bitmap, rect)
    val file = File.createTempFile("avatar", ".jpg")
    try {
        AvatarImageProcessor.encodeJpeg(square, file)
        return file.readBytes()
    } finally {
        if (square !== bitmap) square.recycle()
        file.delete()
    }
}
