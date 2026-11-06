package com.messenger.app.ui.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.messenger.app.ui.settings.SettingsPrimaryButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.messenger.app.ui.settings.SettingsDialogButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.messenger.app.R
import com.messenger.app.domain.model.AvatarUpdate
import com.messenger.app.domain.model.BioUpdate
import com.messenger.app.ui.components.UserAvatar
import com.messenger.app.ui.theme.LocalCocoColors
import com.messenger.app.ui.theme.CocoHaptics
import com.messenger.app.ui.theme.CocoIcons
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreen(viewModel: ProfileViewModel, onSaved: () -> Unit, onBack: () -> Unit = onSaved) {
    val uiState by viewModel.uiState.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val profile = (uiState as? ProfileUiState.Loaded)?.profile
    val colors = LocalCocoColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // A photo revision is not a different editor/account. Keep typed fields.
    var username by remember(profile?.id) { mutableStateOf(profile?.username.orEmpty()) }
    var displayName by remember(profile?.id) { mutableStateOf(profile?.displayName.orEmpty()) }
    var bio by remember(profile?.id) { mutableStateOf(profile?.bio.orEmpty()) }
    var priorProfile by remember(profile?.id) { mutableStateOf(profile) }
    LaunchedEffect(profile) {
        // Fresh cached/server metadata can update untouched fields. A photo
        // revision must not overwrite values already edited by the user.
        if (username == priorProfile?.username.orEmpty()) username = profile?.username.orEmpty()
        if (displayName == priorProfile?.displayName.orEmpty()) displayName = profile?.displayName.orEmpty()
        if (bio == priorProfile?.bio.orEmpty()) bio = profile?.bio.orEmpty()
        priorProfile = profile
    }
    var showDiscard by remember(profile?.id) { mutableStateOf(false) }
    val saving = saveState == SaveState.Saving
    val baseline = priorProfile ?: profile
    val dirty = baseline != null && (username != baseline.username || displayName != baseline.displayName || bio != baseline.bio.orEmpty())
    // Match the server's Unicode scalar limits, not Kotlin UTF-16 length.
    val nameCount = displayName.trim().codePointCount(0, displayName.trim().length)
    val bioCount = bio.trim().codePointCount(0, bio.trim().length)
    val usernameValid = username.matches(ProfileUsernamePattern)
    val nameValid = nameCount in 1..64
    val bioValid = bioCount <= 160
    val canSave = profile != null && !saving && usernameValid && nameValid && bioValid
    val requestBack: () -> Unit = {
        if (!saving) {
            focus.clearFocus()
            keyboard?.hide()
            if (dirty) showDiscard = true else onBack()
        }
    }

    // E.1: avatar action sheet (Camera / Gallery / Remove / Cancel) + crop.
    var showAvatarSheet by remember { mutableStateOf(false) }
    var cropSourceUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    BackHandler(enabled = !showAvatarSheet && cropSourceUri == null) { requestBack() }
    val cameraCapture = com.messenger.app.capture.rememberOwnedCameraCapture("avatar_camera", "avatar",
        onUnavailable = { android.widget.Toast.makeText(context, R.string.capture_in_use, android.widget.Toast.LENGTH_SHORT).show() },
        onPermissionDenied = { android.widget.Toast.makeText(context, R.string.error_camera_denied, android.widget.Toast.LENGTH_SHORT).show() },
        onResult = { result ->
            if (result.success) { pendingCameraFile = result.file; cropSourceUri = result.uri } else result.file.delete()
        })

    // Gallery: the photo picker feeds the SAME crop pipeline as the camera.
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) cropSourceUri = uri
    }

    LaunchedEffect(saveState) {
        if (saveState is SaveState.Success) {
            viewModel.resetSaveState()
            onSaved()
        }
    }

    val save: () -> Unit = {
        if (canSave) {
            focus.clearFocus()
            keyboard?.hide()
            val bioUpdate = when {
                bio.isBlank() && profile?.bio != null -> BioUpdate.Clear
                bio.isNotBlank() && bio.trim() != profile?.bio -> BioUpdate.Set(bio.trim())
                else -> BioUpdate.Keep
            }
            viewModel.save(username.takeIf { it != profile?.username }, displayName.takeIf { it != profile?.displayName }, bioUpdate, AvatarUpdate.Keep)
        }
    }

    com.messenger.app.ui.theme.CocoScreenSurface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("edit-profile-screen")) {
            val largeText = LocalDensity.current.fontScale >= 1.3f
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                com.messenger.app.ui.components.CocoPageHeader(
                    title = stringResource(R.string.profile_edit_title),
                    titleFontWeight = FontWeight.SemiBold,
                    start = {
                        com.messenger.app.ui.components.CocoBackButton(onClick = { CocoHaptics.tap(view); requestBack() }, enabled = !saving,
                            modifier = Modifier.testTag("edit-profile-back"))
                    },
                    end = { if (!largeText) ProfileEditorSaveButton(saving, canSave, save) },
                )
                if (largeText) ProfileEditorSaveButton(saving, canSave, save,
                    Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (profile == null) {
                    ProfileEditorCard {
                        if (uiState is ProfileUiState.Error) {
                            Text((uiState as ProfileUiState.Error).message, color = colors.danger)
                            com.messenger.app.ui.settings.SettingsSecondaryButton(onClick = viewModel::refresh) { Text(stringResource(R.string.retry)) }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
                                Text(stringResource(R.string.profile_editor_loading), color = colors.secondaryText)
                            }
                        }
                    }
                } else {
                    ProfileEditorCard {
                        val photo: @Composable () -> Unit = {
                            UserAvatar(userId = profile.id, avatarVersion = profile.avatarVersion, hasAvatar = profile.hasAvatar,
                                name = displayName.ifBlank { profile.displayName }, color = colors.accent, contentColor = colors.onAccent, size = 80.dp)
                        }
                        val identity: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(displayName.trim().ifBlank { stringResource(R.string.profile_editor_preview_name) },
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.primaryText, maxLines = 2)
                                Text("@" + username, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText, maxLines = 2)
                                com.messenger.app.ui.settings.SettingsSecondaryButton(enabled = !saving, onClick = { focus.clearFocus(); keyboard?.hide(); showAvatarSheet = true },
                                    modifier = Modifier.testTag("edit-profile-photo")) {
                                    Icon(CocoIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.profile_change_photo))
                                }
                            }
                        }
                        if (largeText) { photo(); identity() }
                        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            photo(); Box(Modifier.weight(1f)) { identity() }
                        }
                        Text(stringResource(R.string.profile_editor_photo_note), style = MaterialTheme.typography.bodySmall, color = colors.secondaryText)
                    }

                    ProfileEditorCard {
                        Text(stringResource(R.string.profile_editor_identity_section), style = MaterialTheme.typography.titleSmall, color = colors.secondaryText)
                        OutlinedTextField(value = displayName, onValueChange = { displayName = it },
                            label = { Text(stringResource(R.string.auth_display_name)) }, singleLine = true,
                            enabled = !saving, isError = !nameValid,
                            supportingText = { ProfileEditorFieldHelp(stringResource(if (nameValid) R.string.profile_editor_name_hint else R.string.profile_editor_name_error), nameCount, 64, !nameValid) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                            shape = RoundedCornerShape(16.dp), colors = profileEditorFieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("edit-profile-display-name"))
                        OutlinedTextField(value = bio, onValueChange = { bio = it },
                            label = { Text(stringResource(R.string.profile_editor_bio_label)) }, minLines = 2, maxLines = 4,
                            enabled = !saving, isError = !bioValid,
                            supportingText = { ProfileEditorFieldHelp(stringResource(if (bioValid) R.string.profile_editor_bio_hint else R.string.profile_editor_bio_error), bioCount, 160, !bioValid) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            shape = RoundedCornerShape(16.dp), colors = profileEditorFieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("edit-profile-bio"))
                    }
                    ProfileEditorCard {
                        Text(stringResource(R.string.profile_editor_username_section), style = MaterialTheme.typography.titleSmall, color = colors.secondaryText)
                        OutlinedTextField(value = username, onValueChange = { username = it },
                            label = { Text(stringResource(R.string.auth_username)) }, prefix = { Text("@") }, singleLine = true,
                            enabled = !saving, isError = !usernameValid,
                            supportingText = { ProfileEditorFieldHelp(stringResource(if (usernameValid) R.string.profile_editor_username_hint else R.string.profile_editor_username_error), username.codePointCount(0, username.length), 32, !usernameValid) },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                            shape = RoundedCornerShape(16.dp), colors = profileEditorFieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("edit-profile-username"))
                    }
                }
                if (saveState is SaveState.Error) ProfileEditorCard {
                    Text((saveState as SaveState.Error).message, color = colors.danger, modifier = Modifier.testTag("edit-profile-error"))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false },
        title = { Text(stringResource(R.string.profile_editor_discard_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(stringResource(R.string.profile_editor_discard_message)) } },
        confirmButton = { TextButton(destructive = true, onClick = { showDiscard = false; onBack() },
            modifier = Modifier.testTag("edit-profile-discard")) { Text(stringResource(R.string.profile_editor_discard)) } },
        dismissButton = { TextButton(onClick = { showDiscard = false }, modifier = Modifier.testTag("edit-profile-keep-editing")) { Text(stringResource(R.string.cancel)) } },
        containerColor = colors.glassFloating)

    if (showAvatarSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showAvatarSheet = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.glassFloating,
        ) {
            Text(stringResource(R.string.profile_change_photo), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp))
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { showAvatarSheet = false; cameraCapture.request(false) }) {
                Text(stringResource(R.string.attach_camera))
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                showAvatarSheet = false
                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.attach_gallery)) }
            if (profile?.hasAvatar == true) TextButton(destructive = true, modifier = Modifier.fillMaxWidth(), onClick = { showAvatarSheet = false; viewModel.removeAvatar() }) {
                Text(stringResource(R.string.profile_remove_photo))
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    cropSourceUri?.let { uri ->
        AvatarCropDialog(
            sourceUri = uri,
            onDone = { bytes ->
                cropSourceUri = null
                pendingCameraFile?.delete(); pendingCameraFile = null
                viewModel.uploadAvatar(bytes)
            },
            onDismiss = {
                cropSourceUri = null
                pendingCameraFile?.delete()
                pendingCameraFile = null
            },
        )
    }
}

private val ProfileUsernamePattern = Regex("[A-Za-z0-9_.]{3,32}")

@Composable
private fun ProfileEditorSaveButton(saving: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.testTag("edit-profile-save")) {
        if (saving) {
            CircularProgressIndicator(Modifier.size(18.dp), color = androidx.compose.material3.LocalContentColor.current, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.save))
    }
}

@Composable
private fun ProfileEditorCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalCocoColors.current
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineSecondary)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ProfileEditorFieldHelp(message: String, count: Int, maximum: Int, error: Boolean) {
    val colors = LocalCocoColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(message, modifier = Modifier.weight(1f), color = if (error) colors.danger else colors.secondaryText)
        Text(stringResource(R.string.profile_editor_counter, count, maximum), maxLines = 1,
            color = if (error) colors.danger else colors.secondaryText)
    }
}

@Composable
private fun profileEditorFieldColors() = LocalCocoColors.current.let { colors ->
    OutlinedTextFieldDefaults.colors(focusedTextColor = colors.primaryText, unfocusedTextColor = colors.primaryText,
        disabledTextColor = colors.secondaryText, focusedContainerColor = colors.inputSurface, unfocusedContainerColor = colors.inputSurface,
        disabledContainerColor = colors.inputSurface, focusedBorderColor = colors.accent, unfocusedBorderColor = colors.outlineSecondary,
        focusedLabelColor = colors.accent, unfocusedLabelColor = colors.secondaryText, cursorColor = colors.accent,
        errorTextColor = colors.primaryText, errorContainerColor = colors.inputSurface, errorBorderColor = colors.danger, errorLabelColor = colors.danger)
}
