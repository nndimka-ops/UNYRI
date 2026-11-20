package com.messenger.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import com.messenger.app.R
import com.messenger.app.data.identity.IdentityPolicy

internal data class PublicNumberPresentation(val display: String, val copy: String)
internal fun drawerPublicNumber(raw: String?): PublicNumberPresentation? = raw
    ?.takeIf { it.length == 9 && IdentityPolicy.number(it) != null }
    ?.let { PublicNumberPresentation(IdentityPolicy.formattedNumber(it), it) }

@Composable
internal fun DrawerPublicNumber(raw: String?, compactCaption: Boolean = false, bottomGap: Dp = 8.dp) {
    val number = drawerPublicNumber(raw) ?: return
    val context = LocalContext.current
    val copied = stringResource(R.string.login2_number_copied)
    val target = if (compactCaption) Modifier.fillMaxWidth() else Modifier.heightIn(min = 44.dp)
    Text(number.display, fontSize = (if (compactCaption) DrawerCaptionPolicy.numberSizeSp else 13f).sp,
        lineHeight = if (compactCaption) DrawerCaptionPolicy.numberLineHeightSp.sp else TextUnit.Unspecified,
        color = Color(0xFFA3A3A3),
        modifier = target.testTag("drawer-public-number")
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.copy)) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("UNYRI", number.copy))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }.then(if (compactCaption) Modifier.padding(top = (DrawerCaptionPolicy.textGapDp / 2).dp,
                bottom = bottomGap) else Modifier))
}
