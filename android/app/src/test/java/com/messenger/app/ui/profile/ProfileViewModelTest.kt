package com.messenger.app.ui.profile

import com.messenger.app.data.api.CreateAvatarDto
import com.messenger.app.data.avatar.AvatarRepository
import com.messenger.app.data.sync.FakeAuthRepository
import com.messenger.app.domain.model.*
import com.messenger.app.domain.repository.ProfileRepository
import com.messenger.app.domain.repository.SocketConnectionState
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableSetOf<ProfileViewModel>()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        try {
            runTest(dispatcher) { models.toList().forEach { closeViewModel(it) } }
        } finally { models.clear(); Dispatchers.resetMain() }
    }
    // viewModelScope is not a child of runTest. Avatar cache work uses real IO,
    // so cancelling without joining can return to Main after resetMain().
    private fun newViewModel(repository: ProfileRepository, avatars: AvatarRepository,
        auth: com.messenger.app.domain.repository.AuthRepository,
        connection: kotlinx.coroutines.flow.Flow<SocketConnectionState> = kotlinx.coroutines.flow.emptyFlow()): ProfileViewModel =
        ProfileViewModel(repository, avatars, auth, connection).also { models += it }
    private suspend fun closeViewModel(vm: ProfileViewModel) {
        vm.viewModelScope.coroutineContext.job.cancelAndJoin()
        models -= vm
    }
    private fun profile(name: String) = UserProfile("me", "me", name, null, createdAt="", updatedAt="")
    private fun avatar() = AvatarRepository(java.io.File(requireNotNull(System.getProperty("java.io.tmpdir"))), "test") { null }
    /** Cache work is really dispatched to IO: await completion, not merely the next UI frame. */
    private suspend fun awaitAvatarSave(vm: ProfileViewModel) = withContext(Dispatchers.IO) {
        withTimeout(5000) { vm.saveState.first { it != SaveState.Saving } }
    }
    private class FakeProfileRepository : ProfileRepository {
        val updates = kotlinx.coroutines.flow.MutableSharedFlow<UserProfile>(extraBufferCapacity = 8)
        override val profileUpdates = updates
        var appCoordinator = false
        override val usesAppRefreshCoordinator get() = appCoordinator
        var result: suspend (String, Long) -> Result<SearchResult> = { _, _ -> Result.success(SearchResult(emptyList(), null)) }
        val queries = mutableListOf<Pair<String, Long>>()
        var cached: UserProfile? = null
        var calls = 0
        var load: suspend () -> Result<UserProfile> = { Result.failure(IllegalStateException("offline")) }
        var saveCalls = 0; var uploadCalls = 0; var removeCalls = 0
        var requestedName: String? = null
        var update: suspend () -> Result<UserProfile> = { Result.failure(IllegalStateException()) }
        var remove: suspend () -> Result<UserProfile> = { Result.failure(IllegalStateException()) }
        var avatarIntent: Result<CreateAvatarDto> = Result.failure(IllegalStateException())
        var avatarUpload: Result<Unit> = Result.failure(IllegalStateException())
        var avatarComplete: suspend () -> Result<UserProfile> = { Result.failure(IllegalStateException()) }
        override fun cachedMe() = cached
        override suspend fun getMe(): Result<UserProfile> { calls++; return load() }
        override suspend fun updateProfile(username: String?, displayName: String?, bioUpdate: BioUpdate, avatarUpdate: AvatarUpdate): Result<UserProfile> { saveCalls++; requestedName = displayName; return update() }
        override suspend fun searchUsers(query: String, limit: Int, offset: Long): Result<SearchResult> {
            queries += query to offset
            return result(query, offset)
        }
        override suspend fun createAvatarUpload(sizeBytes: Long): Result<CreateAvatarDto> { uploadCalls++; return avatarIntent }
        override suspend fun uploadAvatarBytes(uploadUrl: String, bytes: ByteArray): Result<Unit> = avatarUpload
        override suspend fun completeAvatarUpload(objectKey: String): Result<UserProfile> = avatarComplete()
        override suspend fun removeAvatar(): Result<UserProfile> { removeCalls++; return remove() }
        override suspend fun getAvatarDownloadUrl(userId: String): Result<com.messenger.app.data.api.AvatarDownloadDto> =
            Result.failure(IllegalStateException())
        override suspend fun downloadAvatarBytes(downloadUrl: String): Result<ByteArray> =
            Result.failure(IllegalStateException())
    }


    @Test fun reservedServerRejectionIsInlineAndClearsWhenNameChanges() = runTest(dispatcher) {
        val repo = FakeProfileRepository().apply {
            cached = profile("Bob")
            update = { Result.failure(com.messenger.app.data.api.AuthApiException.Api("display_name_reserved", DisplayNamePolicy.reservedError)) }
        }
        val vm = newViewModel(repo, avatar(), FakeAuthRepository()); runCurrent()
        try {
            vm.save(null, "UNYRI Support", BioUpdate.Keep, AvatarUpdate.Keep); runCurrent()
            assertEquals(SaveState.DisplayNameReserved, vm.saveState.value)
            vm.clearDisplayNameError()
            assertEquals(SaveState.Idle, vm.saveState.value)
        } finally { closeViewModel(vm) }
    }

    @Test fun displayErrorCodeSurvivesChangedServerWordingAndClearsWithEdit() = runTest(dispatcher) {
        for (code in listOf("display_name_link", "display_name_leading_at")) {
            val repo = FakeProfileRepository().apply {
                cached = profile("Bob")
                update = { Result.failure(com.messenger.app.data.api.AuthApiException.Api(code, "Changed server wording")) }
            }
            val vm = newViewModel(repo, avatar(), FakeAuthRepository()); runCurrent()
            try {
                vm.save(null, "Name", BioUpdate.Keep, AvatarUpdate.Keep); runCurrent()
                assertEquals(code, (vm.saveState.value as SaveState.Error).code)
                vm.clearDisplayNameError()
                assertEquals(SaveState.Idle, vm.saveState.value)
            } finally { closeViewModel(vm) }
        }
    }

    @Test fun reservedNamesAreDecidedByServerEvenWithoutCachedRole() = runTest(dispatcher) {
        val repo = FakeProfileRepository().apply {
            cached = profile("Bob").copy(adminRole = null)
            update = { Result.success(profile("UNYRI Support").copy(adminRole = "moderator")) }
        }
        val vm = newViewModel(repo, avatar(), FakeAuthRepository()); runCurrent()
        try {
            vm.save(null, "UNYRI Support", BioUpdate.Keep, AvatarUpdate.Keep); runCurrent()
            assertEquals("UNYRI Support", repo.requestedName)
            assertEquals(SaveState.Success, vm.saveState.value)
            assertEquals("moderator", (vm.uiState.value as ProfileUiState.Loaded).profile.adminRole)
        } finally { closeViewModel(vm) }
    }

    @Test fun unchangedInvisibleLegacyNameDoesNotBlockBioEdit() = runTest(dispatcher) {
        val legacy = "\u3164"
        val repo = FakeProfileRepository().apply {
            cached = profile(legacy)
            update = { Result.success(profile(legacy).copy(bio = "new bio")) }
        }
        val vm = newViewModel(repo, avatar(), FakeAuthRepository()); runCurrent()
        try {
            vm.save(null, DisplayNamePolicy.nameToSend(legacy, legacy), BioUpdate.Set("new bio"), AvatarUpdate.Keep); runCurrent()
            assertEquals(legacy, repo.requestedName)
            assertEquals(SaveState.Success, vm.saveState.value)
        } finally { closeViewModel(vm) }
    }
    @Test fun cachedProfileRefreshesOnReconnectWithoutPolling() = runTest(dispatcher) {
        val repository = FakeProfileRepository().apply { cached = profile("cached") }
        val connection = MutableStateFlow(SocketConnectionState.DISCONNECTED)
        val vm = newViewModel(repository, avatar(), FakeAuthRepository(), connection)
        runCurrent()
        assertTrue((vm.uiState.value as ProfileUiState.Loaded).isCached)
        repository.load = { Result.success(profile("fresh")) }
        connection.value = SocketConnectionState.CONNECTED
        runCurrent()
        assertEquals(ProfileUiState.Loaded(profile("fresh")), vm.uiState.value)
        connection.value = SocketConnectionState.DISCONNECTED; runCurrent()
        connection.value = SocketConnectionState.CONNECTED; runCurrent()
        assertEquals(3, repository.calls)
        closeViewModel(vm)
    }
    @Test fun responseFromEndedSessionCannotReplaceCachedProfile() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<UserProfile>>()
        val repository = FakeProfileRepository().apply { cached = profile("cached"); load = { response.await() } }
        val auth = FakeAuthRepository()
        val vm = newViewModel(repository, avatar(), auth)
        runCurrent(); auth.logout(); response.complete(Result.success(profile("stale"))); runCurrent()
        assertEquals("cached", (vm.uiState.value as ProfileUiState.Loaded).profile.displayName)
        closeViewModel(vm)
    }
    @Test fun repeatedSaveBeforeNextFrameSendsOneMutation() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<UserProfile>>()
        val repo = FakeProfileRepository().apply { cached = profile("cached"); update = { response.await() } }
        val vm = newViewModel(repo, avatar(), FakeAuthRepository()); runCurrent()
        try {
            repeat(2) { vm.save(null, "new", BioUpdate.Keep, AvatarUpdate.Keep) }; runCurrent()
            assertEquals(1, repo.saveCalls)
        } finally { closeViewModel(vm) }
    }
    @Test fun otherProfileActionsCannotOverlapPendingSave() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<UserProfile>>()
        val repo = FakeProfileRepository().apply { cached = profile("cached"); update = { response.await() } }
        val vm = newViewModel(repo, avatar(), FakeAuthRepository()); runCurrent()
        try {
            vm.save(null,"new",BioUpdate.Keep,AvatarUpdate.Keep); runCurrent()
            vm.removeAvatar(); vm.uploadAvatar(byteArrayOf(1)); runCurrent()
            assertEquals(0, repo.removeCalls); assertEquals(0, repo.uploadCalls)
        } finally { closeViewModel(vm) }
    }
    @Test fun staleEditorCannotStartMutationsForAnotherSession() = runTest(dispatcher) {
        val repo = FakeProfileRepository().apply { cached = profile("cached") }; val auth = FakeAuthRepository()
        val vm = newViewModel(repo, avatar(), auth); runCurrent(); auth.logout()
        try {
            vm.save(null,"new",BioUpdate.Keep,AvatarUpdate.Keep); vm.removeAvatar(); vm.uploadAvatar(byteArrayOf(1)); runCurrent()
            assertEquals(0, repo.saveCalls); assertEquals(0, repo.removeCalls); assertEquals(0, repo.uploadCalls)
        } finally { closeViewModel(vm) }
    }
    @Test fun lateSaveResponseCannotPublishAfterLogout() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<UserProfile>>()
        val repo = FakeProfileRepository().apply { cached = profile("cached"); update = { response.await() } }
        val auth = FakeAuthRepository(); val vm = newViewModel(repo, avatar(), auth); runCurrent()
        try {
            vm.save(null,"new",BioUpdate.Keep,AvatarUpdate.Keep); runCurrent()
            auth.logout(); response.complete(Result.success(profile("stale"))); runCurrent()
            assertNotEquals(SaveState.Success,vm.saveState.value)
            assertEquals("cached",(vm.uiState.value as ProfileUiState.Loaded).profile.displayName)
        } finally { closeViewModel(vm) }
    }
    @Test fun avatarChangeMustNotSignalWholeFormSaved() = runTest(dispatcher) {
        val repo = FakeProfileRepository().apply { cached=profile("cached"); remove={Result.success(profile("cached"))} }
        val vm=newViewModel(repo,avatar(),FakeAuthRepository()); runCurrent()
        try {
            vm.removeAvatar(); runCurrent()
            assertEquals(1,repo.removeCalls)
            assertNotEquals(SaveState.Success,vm.saveState.value)
        } finally {closeViewModel(vm)}
    }
    @Test fun unchangedSaveClosesWithoutSendingEmptyPatch() = runTest(dispatcher) {
        val repo=FakeProfileRepository().apply {cached=profile("cached")}
        val vm=newViewModel(repo,avatar(),FakeAuthRepository());runCurrent()
        try {
            vm.save(null,null,BioUpdate.Keep,AvatarUpdate.Keep);runCurrent()
            assertEquals(0,repo.saveCalls);assertEquals(SaveState.Success,vm.saveState.value)
        } finally {closeViewModel(vm)}
    }
    @Test fun cancelledMutationIsNotShownAsSaveFailure() = runTest(dispatcher) {
        val repo=FakeProfileRepository().apply {cached=profile("cached");update={Result.failure(CancellationException())}}
        val vm=newViewModel(repo,avatar(),FakeAuthRepository());runCurrent()
        try {
            vm.save(null,"new",BioUpdate.Keep,AvatarUpdate.Keep);runCurrent()
            assertEquals(SaveState.Idle,vm.saveState.value)
            assertEquals("cached",(vm.uiState.value as ProfileUiState.Loaded).profile.displayName)
        } finally {closeViewModel(vm)}
    }
    @Test fun failedMutationCanBeRetriedOnce() = runTest(dispatcher) {
        val repo=FakeProfileRepository().apply {cached=profile("cached")}
        val vm=newViewModel(repo,avatar(),FakeAuthRepository());runCurrent()
        try {
            vm.save(null,"new",BioUpdate.Keep,AvatarUpdate.Keep);runCurrent()
            assertTrue(vm.saveState.value is SaveState.Error)
            repo.update={Result.success(profile("new"))}
            vm.save(null,"new",BioUpdate.Keep,AvatarUpdate.Keep);runCurrent()
            assertEquals(2,repo.saveCalls);assertEquals(SaveState.Success,vm.saveState.value)
        } finally {closeViewModel(vm)}
    }
    @Test fun resetCannotReopenTheBusySlot() = runTest(dispatcher) {
        val response=CompletableDeferred<Result<UserProfile>>()
        val repo=FakeProfileRepository().apply {cached=profile("cached");update={response.await()}}
        val vm=newViewModel(repo,avatar(),FakeAuthRepository());runCurrent()
        try {
            vm.save(null,"new",BioUpdate.Keep,AvatarUpdate.Keep);runCurrent()
            vm.resetSaveState();vm.save(null,"other",BioUpdate.Keep,AvatarUpdate.Keep);runCurrent()
            assertEquals(1,repo.saveCalls);assertEquals(SaveState.Saving,vm.saveState.value)
        } finally {closeViewModel(vm)}
    }
    @Test fun staleEditorCannotDeleteTheReplacementAccount() = runTest(dispatcher) {
        val auth=FakeAuthRepository();var deletes=0
        val wrapped=object:com.messenger.app.domain.repository.AuthRepository by auth {
            override suspend fun deleteAccount(password:String):Result<Unit> {deletes++;return Result.success(Unit)}
        }
        val vm=newViewModel(FakeProfileRepository(),avatar(),wrapped);runCurrent();auth.logout()
        try {vm.deleteAccount("synthetic-test-password");runCurrent();assertEquals(0,deletes)} finally {closeViewModel(vm)}
    }

    @Test fun remoteProfileUpdateReachesAlreadyLoadedViewModelWithoutAuthReemit() = runTest(dispatcher) {
        val auth = FakeAuthRepository()
        val originalAuth = auth.authState.value
        val originalEpoch = auth.currentSessionEpoch()
        val repo = FakeProfileRepository().apply { load = { Result.success(profile("before")) }; appCoordinator = true }
        val vm = newViewModel(repo, avatar(), auth); runCurrent()
        try {
            repo.updates.emit(profile("after").copy(profileVersion = 2, avatarVersion = 3, hasAvatar = true)); runCurrent()
            assertEquals("after", (vm.uiState.value as ProfileUiState.Loaded).profile.displayName)
            assertEquals(3L, (vm.uiState.value as ProfileUiState.Loaded).profile.avatarVersion)
            assertEquals(originalAuth, auth.authState.value)
            assertEquals(originalEpoch, auth.currentSessionEpoch())
        } finally { closeViewModel(vm) }
    }

    @Test fun olderGetCannotReplaceNewerCanonicalUpdateInViewModel() = runTest(dispatcher) {
        val response = CompletableDeferred<Result<UserProfile>>()
        val repo = FakeProfileRepository().apply { load = { response.await() } }
        val vm = newViewModel(repo, avatar(), FakeAuthRepository()); runCurrent()
        try {
            repo.updates.emit(profile("new").copy(profileVersion = 5)); runCurrent()
            response.complete(Result.success(profile("old").copy(profileVersion = 4))); runCurrent()
            assertEquals("new", (vm.uiState.value as ProfileUiState.Loaded).profile.displayName)
        } finally { closeViewModel(vm) }
    }

    @Test fun appCoordinatorOwnsReconnectInsteadOfEachLoadedViewModel() = runTest(dispatcher) {
        val repo = FakeProfileRepository().apply { appCoordinator = true; load = { Result.success(profile("before")) } }
        val connection = MutableStateFlow(SocketConnectionState.DISCONNECTED)
        val vm = newViewModel(repo, avatar(), FakeAuthRepository(), connection); runCurrent()
        try {
            connection.value = SocketConnectionState.CONNECTED; runCurrent()
            assertEquals(1, repo.calls)
        } finally { closeViewModel(vm) }
    }

    @Test fun foreignAndEndedSessionUpdatesAreNotDisplayed() = runTest(dispatcher) {
        val repo = FakeProfileRepository().apply { cached = profile("before") }
        val auth = FakeAuthRepository()
        val vm = newViewModel(repo, avatar(), auth); runCurrent()
        try {
            repo.updates.emit(profile("foreign").copy(id = "other", profileVersion = 9)); runCurrent()
            assertEquals("before", (vm.uiState.value as ProfileUiState.Loaded).profile.displayName)
            auth.logout(); repo.updates.emit(profile("stale").copy(profileVersion = 9)); runCurrent()
            assertEquals("before", (vm.uiState.value as ProfileUiState.Loaded).profile.displayName)
        } finally { closeViewModel(vm) }
    }

    @Test fun lateAvatarUploadCannotWarmOldBytesUnderNewerRemoteVersion() = runTest(dispatcher) {
        val completion = CompletableDeferred<Result<UserProfile>>()
        val old = profile("old").copy(profileVersion = 1, avatarVersion = 1, hasAvatar = true, avatarUrl = "old-object")
        val local = old.copy(profileVersion = 2, avatarVersion = 2, avatarUrl = "own-fixture")
        val remote = old.copy(displayName = "remote", profileVersion = 3, avatarVersion = 3, avatarUrl = "remote-object")
        val repo = FakeProfileRepository().apply {
            cached = old
            avatarIntent = Result.success(CreateAvatarDto("own-fixture", "https://fixture.invalid", ""))
            avatarUpload = Result.success(Unit)
            avatarComplete = { completion.await() }
        }
        val directory = java.nio.file.Files.createTempDirectory("profile-avatar-order").toFile()
        val avatars = AvatarRepository(directory, "test") { null }
        val vm = newViewModel(repo, avatars, FakeAuthRepository()); runCurrent()
        try {
            vm.uploadAvatar(byteArrayOf(2)); runCurrent()
            avatars.put("me", 3, byteArrayOf(3))
            repo.cached = remote; repo.updates.emit(remote); runCurrent()
            completion.complete(Result.success(local)); runCurrent()
            assertEquals(SaveState.Idle, awaitAvatarSave(vm))
            assertArrayEquals(byteArrayOf(3), avatars.cached("me", 3, true))
            assertNull(avatars.cached("me", 2, true))
            assertEquals(3L, (vm.uiState.value as ProfileUiState.Loaded).profile.avatarVersion)
        } finally { closeViewModel(vm); directory.deleteRecursively() }
    }

    @Test fun lateAvatarRemovalCannotInvalidateNewRemotePicture() = runTest(dispatcher) {
        val completion = CompletableDeferred<Result<UserProfile>>()
        val old = profile("old").copy(profileVersion = 1, avatarVersion = 1, hasAvatar = true)
        val removed = old.copy(profileVersion = 2, avatarVersion = 2, hasAvatar = false)
        val remote = old.copy(displayName = "remote", profileVersion = 3, avatarVersion = 3)
        val repo = FakeProfileRepository().apply { cached = old; remove = { completion.await() } }
        val directory = java.nio.file.Files.createTempDirectory("profile-avatar-remove-order").toFile()
        val avatars = AvatarRepository(directory, "test") { null }
        val vm = newViewModel(repo, avatars, FakeAuthRepository()); runCurrent()
        try {
            vm.removeAvatar(); runCurrent()
            avatars.put("me", 3, byteArrayOf(3))
            repo.cached = remote; repo.updates.emit(remote); runCurrent()
            completion.complete(Result.success(removed)); runCurrent()
            assertEquals(SaveState.Idle, awaitAvatarSave(vm))
            assertArrayEquals(byteArrayOf(3), avatars.cached("me", 3, true))
            assertEquals(3L, (vm.uiState.value as ProfileUiState.Loaded).profile.avatarVersion)
        } finally { closeViewModel(vm); directory.deleteRecursively() }
    }

    @Test fun currentAvatarUploadCompletesIoWarmingBeforeReportingIdle() = runTest(dispatcher) {
        val uploaded = profile("current").copy(profileVersion = 2, avatarVersion = 2, hasAvatar = true, avatarUrl = "own-fixture")
        val repo = FakeProfileRepository().apply {
            cached = profile("before")
            avatarIntent = Result.success(CreateAvatarDto("own-fixture", "https://fixture.invalid", ""))
            avatarUpload = Result.success(Unit)
            avatarComplete = { cached = uploaded; Result.success(uploaded) }
        }
        val directory = java.nio.file.Files.createTempDirectory("profile-avatar-current").toFile()
        val avatars = AvatarRepository(directory, "test") { null }
        val vm = newViewModel(repo, avatars, FakeAuthRepository()); runCurrent()
        try {
            vm.uploadAvatar(byteArrayOf(2)); runCurrent()
            assertEquals(SaveState.Idle, awaitAvatarSave(vm))
            assertArrayEquals(byteArrayOf(2), avatars.cached("me", 2, true))
            assertEquals(2L, (vm.uiState.value as ProfileUiState.Loaded).profile.avatarVersion)
        } finally { closeViewModel(vm); directory.deleteRecursively() }
    }

    @Test fun avatarResponseForDifferentObjectCannotWarmUploadedBytesUnderItsVersion() = runTest(dispatcher) {
        val remote = profile("remote").copy(profileVersion = 3, avatarVersion = 3, hasAvatar = true, avatarUrl = "object-B")
        val repo = FakeProfileRepository().apply {
            cached = remote
            avatarIntent = Result.success(CreateAvatarDto("object-A", "https://fixture.invalid", ""))
            avatarUpload = Result.success(Unit)
            // Legacy server committed A, then returned another device's B from a later SELECT.
            avatarComplete = { Result.success(remote) }
        }
        val directory = java.nio.file.Files.createTempDirectory("profile-avatar-response-binding").toFile()
        val avatars = AvatarRepository(directory, "test") { null }
        avatars.put("me", 3, byteArrayOf(3))
        val vm = newViewModel(repo, avatars, FakeAuthRepository()); runCurrent()
        try {
            vm.uploadAvatar(byteArrayOf(2)); runCurrent()
            assertEquals(SaveState.Idle, awaitAvatarSave(vm))
            assertArrayEquals(byteArrayOf(3), avatars.cached("me", 3, true))
            assertEquals("object-B", (vm.uiState.value as ProfileUiState.Loaded).profile.avatarUrl)
        } finally { closeViewModel(vm); directory.deleteRecursively() }
    }

    @Test fun removalResponseContainingNewRemotePictureCannotEraseThatPicture() = runTest(dispatcher) {
        val remote = profile("remote").copy(profileVersion = 3, avatarVersion = 3, hasAvatar = true, avatarUrl = "object-B")
        val repo = FakeProfileRepository().apply { cached = remote; remove = { Result.success(remote) } }
        val directory = java.nio.file.Files.createTempDirectory("profile-remove-response-binding").toFile()
        val avatars = AvatarRepository(directory, "test") { null }
        avatars.put("me", 3, byteArrayOf(3))
        val vm = newViewModel(repo, avatars, FakeAuthRepository()); runCurrent()
        try {
            vm.removeAvatar(); runCurrent()
            assertEquals(SaveState.Idle, awaitAvatarSave(vm))
            assertArrayEquals(byteArrayOf(3), avatars.cached("me", 3, true))
            assertTrue((vm.uiState.value as ProfileUiState.Loaded).profile.hasAvatar)
        } finally { closeViewModel(vm); directory.deleteRecursively() }
    }
}
