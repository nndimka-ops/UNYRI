package com.messenger.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.MessengerApp
import com.messenger.app.data.api.AvatarDownloadDto
import com.messenger.app.data.api.CreateAvatarDto
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.ProfileRepository
import com.messenger.app.ui.profile.EditProfileScreen
import com.messenger.app.ui.profile.ProfileViewModel
import com.messenger.app.ui.theme.CocoTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test

/** Uses the real editor and VM; no remote mutation or local auth/session changes. */
class ProfileLiveDraftInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun externalSelfUpdatePreservesTypedDraftAndUpdatesUntouchedFields() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MessengerApp
        val own = runBlocking { withTimeout(15_000) { app.authRepository.authState.first { it is AuthSessionState.LoggedIn } } } as AuthSessionState.LoggedIn
        val initial = UserProfile(own.user.id, "draft_fixture", "Original name", null,
            bio = "Original bio", createdAt = "", updatedAt = "")
        val snapshots = MutableStateFlow(initial)
        val repository = object : ProfileRepository {
            override val profileUpdates = snapshots
            override val usesAppRefreshCoordinator = true
            override suspend fun getMe() = Result.success(snapshots.value)
            override suspend fun updateProfile(username: String?, displayName: String?, bioUpdate: BioUpdate, avatarUpdate: AvatarUpdate) = error("No mutation in draft test")
            override suspend fun searchUsers(query: String, limit: Int, offset: Long) = Result.success(SearchResult(emptyList(), null))
            override suspend fun createAvatarUpload(sizeBytes: Long): Result<CreateAvatarDto> = error("unused")
            override suspend fun uploadAvatarBytes(uploadUrl: String, bytes: ByteArray): Result<Unit> = error("unused")
            override suspend fun completeAvatarUpload(objectKey: String): Result<UserProfile> = error("unused")
            override suspend fun removeAvatar(): Result<UserProfile> = error("unused")
            override suspend fun getAvatarDownloadUrl(userId: String): Result<AvatarDownloadDto> = error("unused")
            override suspend fun downloadAvatarBytes(downloadUrl: String): Result<ByteArray> = error("unused")
        }
        lateinit var vm: ProfileViewModel
        compose.runOnUiThread { vm = ProfileViewModel(repository, app.avatarRepository, app.authRepository) }
        try {
            compose.setContent { CocoTheme { EditProfileScreen(vm, onSaved = {}, onBack = {}) } }
            compose.onNodeWithTag("edit-profile-display-name").performTextReplacement("Unsaved local draft")
            compose.runOnIdle { snapshots.value = initial.copy(username = "remote_fixture", displayName = "Remote name", bio = "Remote bio", profileVersion = 2) }
            compose.onNodeWithTag("edit-profile-display-name").assertTextContains("Unsaved local draft")
            compose.onNodeWithTag("edit-profile-bio").assertTextContains("Remote bio")
            compose.onNodeWithTag("edit-profile-username").assertTextContains("remote_fixture")
        } finally { compose.runOnUiThread { vm.viewModelScope.cancel() } }
    }
}
