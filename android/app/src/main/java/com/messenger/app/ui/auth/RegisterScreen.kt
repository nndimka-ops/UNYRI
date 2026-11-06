package com.messenger.app.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.ui.theme.CocoIcons
import com.messenger.app.ui.theme.CocoSpacing
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.ThemeMode
import java.util.Locale

/** COCO FLOW Registration (Этап N.1 §8/§9): confirm-password validation,
 *  focused fields, loading button, language/theme footer. */
@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit,
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    currentLanguage: String?,
    onLanguageChange: (Locale?) -> Unit,
    serverHost: String,
    onOpenServer: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val colors = LocalCocoColors.current
    val primaryInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val loading = uiState is AuthUiState.Loading
    val normalizedUsername = username.trim().removePrefix("@")
    val usernameValid = normalizedUsername.matches(Regex("[A-Za-z0-9_.]{3,32}"))
    val passwordsMismatch = confirmPassword.isNotEmpty() && password != confirmPassword
    var accepted by remember { mutableStateOf(false) }
    var legalDocument by remember { mutableStateOf<String?>(null) }
    val formValid = accepted && usernameValid && displayName.trim().length in 1..64 && password.length in 8..256 && confirmPassword == password

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
            Spacer(modifier = Modifier.height(CocoSpacing.lg))
            AuthBrandHeader()
            Spacer(modifier = Modifier.height(CocoSpacing.xs))
            Text(text = stringResource(R.string.auth_create_account), style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText)
            Spacer(modifier = Modifier.height(CocoSpacing.lg))

            ServerCard(host = serverHost, onClick = onOpenServer)

            Spacer(modifier = Modifier.height(CocoSpacing.lg))
            OutlinedTextField(
                shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                value = displayName,
                onValueChange = {
                    displayName = it
                    viewModel.clearError()
                },
                label = { Text(stringResource(R.string.auth_your_name)) },
                supportingText = { Text(stringResource(R.string.auth_name_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(CocoSpacing.sm))
            OutlinedTextField(
                shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                value = username,
                onValueChange = {
                    username = it
                    viewModel.clearError()
                },
                label = { Text(stringResource(R.string.auth_choose_handle)) },
                singleLine = true,
                isError = username.isNotEmpty() && !usernameValid,
                supportingText = { Text(stringResource(R.string.auth_handle_help)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(CocoSpacing.sm))
            OutlinedTextField(
                shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                value = password,
                onValueChange = {
                    password = it
                    viewModel.clearError()
                },
                label = { Text(stringResource(R.string.auth_password)) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                isError = password.isNotEmpty() && password.length !in 8..256,
                supportingText = { Text(stringResource(R.string.auth_password_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
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
            Spacer(modifier = Modifier.height(CocoSpacing.sm))
            OutlinedTextField(
                shape=RoundedCornerShape(20.dp),colors=authFieldColors(),
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    viewModel.clearError()
                },
                label = { Text(stringResource(R.string.auth_confirm_password)) },
                singleLine = true,
                visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                isError = passwordsMismatch,
                supportingText = { if (passwordsMismatch) Text(stringResource(R.string.auth_passwords_mismatch)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!loading && formValid) {
                        viewModel.register(normalizedUsername, password, displayName.trim())
                    }
                }),
                trailingIcon = {
                    IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                        Icon(
                            imageVector = if (confirmPasswordVisible) CocoIcons.EyeOff else CocoIcons.Eye,
                            contentDescription = stringResource(if (confirmPasswordVisible) R.string.auth_hide_password else R.string.auth_show_password),
                            tint = colors.secondaryText,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (uiState is AuthUiState.Error) {
                Spacer(modifier = Modifier.height(CocoSpacing.sm))
                Text(text = stringResource((uiState as AuthUiState.Error).messageRes), color = colors.danger, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(CocoSpacing.lg))
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
                .clip(RoundedCornerShape(20.dp)).toggleable(value=accepted,enabled=!loading,role=Role.Checkbox,
                    onValueChange={accepted=it}).padding(horizontal=8.dp,vertical=4.dp),
                verticalAlignment=Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(accepted, onCheckedChange=null, enabled = !loading)
                Text(com.messenger.app.ui.settings.cocoLegalAgreement(),modifier=Modifier.weight(1f).padding(start=8.dp),color=colors.primaryText)
            }
            for (id in listOf("terms", "privacy")) {
                TextButton(onClick = { legalDocument = id }) { Text(com.messenger.app.ui.settings.cocoLegalTitle(id)) }
            }
            Text(if (com.messenger.app.ui.settings.cocoLegalLanguage() == "ru") "До создания аккаунта прочитайте политику конфиденциальности. Диагностика включается отдельно. Правила выбранного сервера — в его карточке." else "Read the Privacy Policy before creating an account. Diagnostics are controlled separately. Check the selected server’s card for its rules.", style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
            val registerEnabled = !loading && formValid
            val registerActionStyle = authPrimaryActionStyle(registerEnabled, RoundedCornerShape(20.dp))
            Button(
                onClick = { viewModel.register(normalizedUsername, password, displayName.trim()) },
                enabled = registerEnabled,
                modifier = Modifier.fillMaxWidth().height(52.dp).then(com.messenger.app.ui.theme.cocoPressFeedback(primaryInteraction)).then(registerActionStyle.background),
                interactionSource = primaryInteraction,
                shape = RoundedCornerShape(20.dp),
                colors = registerActionStyle.colors,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = colors.primaryText, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.auth_create_account))
                }
            }

            TextButton(onClick = onNavigateToLogin, modifier = Modifier.padding(top = CocoSpacing.sm)) {
                Text(stringResource(R.string.auth_have_account), color = colors.secondaryText)
            }

            Spacer(modifier = Modifier.height(CocoSpacing.xl))
            AuthFooter(
                currentTheme = currentTheme,
                onThemeChange = onThemeChange,
                currentLanguage = currentLanguage,
                onLanguageChange = onLanguageChange,
            )
        }
    }
    legalDocument?.let { documentID ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { legalDocument = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            com.messenger.app.ui.settings.SettingsScaffold(com.messenger.app.ui.settings.cocoLegalTitle(documentID), { legalDocument = null }) { com.messenger.app.ui.settings.CocoLegalContent(documentID) }
        }
    }

}
