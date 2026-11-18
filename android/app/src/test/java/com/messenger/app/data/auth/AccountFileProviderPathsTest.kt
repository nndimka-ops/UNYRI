package com.messenger.app.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AccountFileProviderPathsTest {
    private val first = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    private val second = "11111111-1111-4111-8111-111111111111"

    @Test fun accountIdentityIsPartOfTheGrantedPathEvenForIdenticalFilenames() {
        for (path in listOf("/camera/capture.jpg", "/attachments/shared-attachment.jpg", "/restored_history_media/photo%20one.jpg")) {
            val firstPath = AccountFileProviderPaths.wrap(first, path)
            val secondPath = AccountFileProviderPaths.wrap(second, path)
            assertNotEquals(firstPath, secondPath)
            assertEquals(path, AccountFileProviderPaths.unwrap(first, firstPath))
            assertEquals(path, AccountFileProviderPaths.unwrap(second, secondPath))
            assertNull(AccountFileProviderPaths.unwrap(second, firstPath))
            assertNull(AccountFileProviderPaths.unwrap(first, secondPath))
        }
    }

    @Test fun oldUrisOnlyResolveInTheOriginalWorkspace() {
        val oldPath = "/camera/capture.jpg"
        assertEquals(oldPath, AccountFileProviderPaths.wrap(AccountWorkspaceLayout.LEGACY, oldPath))
        assertEquals(oldPath, AccountFileProviderPaths.unwrap(AccountWorkspaceLayout.LEGACY, oldPath))
        assertNull(AccountFileProviderPaths.unwrap(first, oldPath))
        assertNull(AccountFileProviderPaths.unwrap(AccountWorkspaceLayout.LEGACY, AccountFileProviderPaths.wrap(first, oldPath)))
    }

    @Test fun missingMalformedAndEncodedWorkspaceNamesAreRejected() {
        for (path in listOf("", "/", "camera/file.jpg", "/workspace/$first", "/workspace/$first/",
            "/workspace/%36${first.drop(1)}/camera/file.jpg", "/workspace/$second/camera/file.jpg")) {
            assertNull(AccountFileProviderPaths.unwrap(first, path), path)
        }
    }
}
