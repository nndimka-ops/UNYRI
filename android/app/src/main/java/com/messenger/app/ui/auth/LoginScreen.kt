package com.messenger.app.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.settings.CocoLanguages
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.ThemeMode
import com.messenger.app.ui.chats.ChatControls
import java.util.Locale

/** COCO FLOW Login (Этап N.1 §8/§9): server card, focused fields, loading
 *  button of fixed size, password visibility, language footer. */
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onNavigateToRegister: () -> Unit,
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    currentLanguage: String?,
    onLanguageChange: (Locale?) -> Unit,
    serverHost: String,
    onOpenServer: () -> Unit,
) {
    var showRecovery by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val colors = LocalCocoColors.current
    val cleanupFailed by viewModel.cacheCleanupFailed.collectAsState()
    if (cleanupFailed) {
        com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize(), color = colors.background) {
            Column(modifier = Modifier.safeDrawingPadding().padding(CocoSpacing.xxl)) {
                Text(stringResource(R.string.cache_cleanup_failed))
                androidx.compose.material3.TextButton(onClick = viewModel::logout) {
                    Text(stringResource(R.string.retry_cache_cleanup))
                }
            }
        }
        return
    }
    if (showRecovery) AccountRecoveryPanel(signedIn = false, initialUsername = username, onClose = { showRecovery = false })
    val primaryInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val loading = uiState is AuthUiState.Loading

    com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(CocoSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(CocoSpacing.sm))
            AuthBrandHeader()
            Spacer(modifier = Modifier.height(CocoSpacing.xs))
            Text(
                text = stringResource(R.string.auth_flow_welcome),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryText,
            )
            Spacer(modifier = Modifier.height(CocoSpacing.xl))

            ServerCard(host = serverHost, onClick = onOpenServer, enabled = !loading)

            Spacer(modifier = Modifier.height(CocoSpacing.lg))
            Surface(shape = RoundedCornerShape(20.dp), color = colors.surface,
                border = BorderStroke(1.dp, colors.divider)) {
            Column(Modifier.padding(14.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    viewModel.clearError()
                },
                label = { Text(stringResource(R.string.auth_flow_handle)) },
                supportingText = { Text(stringResource(R.string.auth_login_handle_help)) },
                leadingIcon = { AuthIconTile(AuthChromeIcons.person, colors.categoryViolet) },
                shape = RoundedCornerShape(20.dp), enabled = !loading,
                colors = authFieldColors(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(CocoSpacing.sm))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    viewModel.clearError()
                },
                label = { Text(stringResource(R.string.auth_password)) },
                leadingIcon = { AuthIconTile(ChatControls.lock, colors.categoryAmber) },
                shape = RoundedCornerShape(20.dp), enabled = !loading,
                colors = authFieldColors(),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!loading && username.isNotBlank() && password.isNotBlank()) {
                        viewModel.login(username.trim().removePrefix("@"), password)
                    }
                }),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) CocoIcons.EyeOff else CocoIcons.Eye,
                            contentDescription = stringResource(if (passwordVisible) R.string.auth_hide_password else R.string.auth_show_password),
                            tint = colors.secondaryText,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            }
            }

            if (uiState is AuthUiState.Error) {
                Spacer(modifier = Modifier.height(CocoSpacing.sm))
                Text(
                    text = stringResource((uiState as AuthUiState.Error).messageRes),
                    color = colors.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(CocoSpacing.lg))
            val loginEnabled = !loading && username.isNotBlank() && password.isNotBlank()
            val loginActionStyle = authPrimaryActionStyle(loginEnabled, RoundedCornerShape(20.dp))
            Button(
                onClick = { viewModel.login(username.trim().removePrefix("@"), password) },
                enabled = loginEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .then(com.messenger.app.ui.theme.cocoPressFeedback(primaryInteraction)).then(loginActionStyle.background),
                interactionSource = primaryInteraction,
                shape = RoundedCornerShape(20.dp),
                colors = loginActionStyle.colors,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.primaryText, strokeWidth = 2.dp)
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.auth_flow_login), fontWeight = FontWeight.SemiBold)
                        Icon(AuthChromeIcons.arrow, null, Modifier.align(Alignment.CenterEnd).size(22.dp), tint = loginActionStyle.ink)
                    }
                }
            }

            androidx.compose.material3.OutlinedButton(onClick = onNavigateToRegister, enabled = !loading,
                modifier = Modifier.fillMaxWidth().padding(top = CocoSpacing.md).heightIn(min = 48.dp),
                shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.divider)) {
                Text(stringResource(R.string.auth_create_account), color = if (loading) colors.secondaryText else colors.accentSoft)
            }
            TextButton(onClick = { showRecovery = true }, enabled = !loading) { Text(stringResource(R.string.auth_flow_recovery)) }

            Spacer(modifier = Modifier.height(CocoSpacing.xl))
            AuthFooter(
                currentTheme = currentTheme,
                onThemeChange = onThemeChange,
                currentLanguage = currentLanguage,
                onLanguageChange = onLanguageChange,
            )
        }
    }
}

@Composable
internal fun ServerCard(host: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = LocalCocoColors.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.divider),
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f).clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CocoSpacing.lg, vertical = CocoSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuthIconTile(AuthChromeIcons.server)
            Spacer(modifier = Modifier.width(CocoSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.server_label), style = MaterialTheme.typography.labelSmall, color = colors.secondaryText)
                Text(text = host, style = MaterialTheme.typography.bodyMedium, color = colors.primaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colors.secondaryText, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun AuthIconTile(icon: ImageVector, tint: Color? = null) {
    val colors=LocalCocoColors.current
    val ink = tint ?: colors.categoryBlue
    Surface(color = ink.copy(alpha = .12f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(6.dp).size(36.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = ink, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
internal fun AuthBrandHeader() {
    val colors=LocalCocoColors.current
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(CocoIcons.Logo,null,tint=colors.categoryViolet,modifier=Modifier.size(64.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_name),style=MaterialTheme.typography.headlineLarge,
            color=colors.primaryText,fontWeight=FontWeight.SemiBold)
    }
}

@Composable
internal fun authFieldColors(): androidx.compose.material3.TextFieldColors {
    val colors=LocalCocoColors.current
    return androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedBorderColor=colors.accent,unfocusedBorderColor=colors.divider,
        focusedContainerColor=colors.inputSurface,unfocusedContainerColor=colors.inputSurface,
        focusedTextColor=colors.primaryText,unfocusedTextColor=colors.primaryText,
        focusedLabelColor=colors.primaryText,unfocusedLabelColor=colors.secondaryText,
        cursorColor=colors.accent,errorBorderColor=colors.danger,errorLabelColor=colors.danger)
}

private object AuthChromeIcons {
    val person: ImageVector by lazy {
        ImageVector.Builder("AuthPerson",24.dp,24.dp,24f,24f).apply {
            path(fill=null,stroke=SolidColor(Color.Black),strokeLineWidth=1.7f,
                strokeLineCap=androidx.compose.ui.graphics.StrokeCap.Round) {
                moveTo(16f,7f);arcTo(4f,4f,0f,true,true,8f,7f);arcTo(4f,4f,0f,true,true,16f,7f)
                moveTo(4f,21f);lineTo(4f,19f);curveTo(4f,15f,7f,13f,12f,13f)
                curveTo(17f,13f,20f,15f,20f,19f);lineTo(20f,21f)
            }
        }.build()
    }
    val server: ImageVector by lazy {
        ImageVector.Builder("AuthServer", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
                moveTo(4f, 3f); lineTo(20f, 3f); lineTo(20f, 10f); lineTo(4f, 10f); close()
                moveTo(4f, 14f); lineTo(20f, 14f); lineTo(20f, 21f); lineTo(4f, 21f); close()
                moveTo(7f, 6.5f); lineTo(9f, 6.5f); moveTo(7f, 17.5f); lineTo(9f, 17.5f)
            }
        }.build()
    }
    val arrow: ImageVector by lazy {
        ImageVector.Builder("AuthNext", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
                moveTo(4f, 12f); lineTo(20f, 12f); moveTo(13f, 5f); lineTo(20f, 12f); lineTo(13f, 19f)
            }
        }.build()
    }
}

@Composable
internal fun AuthFooter(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    currentLanguage: String?,
    onLanguageChange: (Locale?) -> Unit,
) {
    val colors = LocalCocoColors.current
    var showLanguage by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { showLanguage = true }) {
            Text(
                text = CocoLanguages.all.firstOrNull { it.tag == currentLanguage }?.displayName ?: "System default",
                color = colors.secondaryText,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }

    if (showLanguage) {
        AlertDialog(
            onDismissRequest = { showLanguage = false },
            title = { Text(stringResource(R.string.language_label)) },
            text = {
                Column(modifier = Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    CocoLanguages.all.forEach { lang ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onLanguageChange(CocoLanguages.localeFor(lang.tag)); showLanguage = false },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = lang.displayName, modifier = Modifier.weight(1f))
                            RadioButton(selected = lang.tag == currentLanguage, onClick = { onLanguageChange(CocoLanguages.localeFor(lang.tag)); showLanguage = false })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLanguage = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
