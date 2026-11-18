package com.messenger.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
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
import com.messenger.app.R
import com.messenger.app.data.identity.IdentityPolicy

internal data class PublicNumberPresentation(val display: String, val copy: String)
internal fun drawerPublicNumber(raw: String?): PublicNumberPresentation? = raw
    ?.takeIf { it.length == 9 && IdentityPolicy.number(it) != null }
    ?.let { PublicNumberPresentation(IdentityPolicy.formattedNumber(it), it) }

@Composable
internal fun DrawerPublicNumber(raw: String?) {
    val number = drawerPublicNumber(raw) ?: return
    val context = LocalContext.current
    val copied = stringResource(R.string.login2_number_copied)
    Text(number.display, fontSize = 13.sp, color = Color(0xFFA3A3A3),
        modifier = Modifier.heightIn(min = 44.dp).testTag("drawer-public-number")
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.copy)) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("UNYRI", number.copy))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            })
}
