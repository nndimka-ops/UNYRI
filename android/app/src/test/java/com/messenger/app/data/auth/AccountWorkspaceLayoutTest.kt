package com.messenger.app.data.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import java.io.File

class AccountWorkspaceLayoutTest {
    private val first = "6f9619ff-8b86-d011-b42d-00c04fc964ff"
    private val second = "6f9619ff-8b86-d011-b42d-00c04fc964fe"

    @Test fun upgradeKeepsOriginalStorageAndOtherAccountsAreIsolated() {
        assertEquals("messenger.db", AccountWorkspaceLayout.databaseName("legacy", "messenger.db"))
        assertEquals("messenger_secure_prefs", AccountWorkspaceLayout.preferenceName("legacy", "messenger_secure_prefs"))
        for (name in listOf("messenger_secure_prefs", "messenger_signal_store", "coco_history_recovery", "server-endpoint")) {
            assertNotEquals(AccountWorkspaceLayout.preferenceName(first, name), AccountWorkspaceLayout.preferenceName(second, name))
        }
        assertNotEquals(AccountWorkspaceLayout.databaseName(first, "messenger.db"), AccountWorkspaceLayout.databaseName(second, "messenger.db"))
        val noBackupPath = "/data/user/0/com.messenger.app/no_backup/accounts/$first/androidx.work.workdb"
        assertEquals(noBackupPath, AccountWorkspaceLayout.databaseName(first, noBackupPath))
    }

    @Test fun deviceProtectionAndInstallationRemainShared() {
        for (name in listOf("local-accounts", "native-app-lock", "installation-identity", "client-config-v1", "coco_language", "coco_theme")) {
            assertEquals(name, AccountWorkspaceLayout.preferenceName(first, name))
        }
        assertTrue(AccountWorkspaceLayout.validKey(first))
        assertTrue(AccountWorkspaceLayout.validKey("legacy"))
        for (invalid in listOf("", "../legacy", "a/b", first.uppercase())) assertFalse(AccountWorkspaceLayout.validKey(invalid))
    }

    @Test fun everyPrivateDirectoryKeepsLegacyPathsAndSeparatesAccounts() {
        for (directory in listOf("files", "no_backup", "cache", "code_cache")) {
            val root = File("app-storage", directory)
            assertEquals(root, AccountWorkspaceLayout.directory("legacy", root))
            assertEquals(File(root, "accounts/$first"), AccountWorkspaceLayout.directory(first, root))
            assertNotEquals(AccountWorkspaceLayout.directory(first, root), AccountWorkspaceLayout.directory(second, root))
        }
    }

    @Test fun databaseOperationsUseTheSameNamesAndLeaveAbsolutePathsAlone() {
        for (name in listOf("messenger.db", "androidx.work.workdb", "fixture.db")) {
            assertEquals("account_${first}_$name", AccountWorkspaceLayout.databaseName(first, name))
            assertEquals(name, AccountWorkspaceLayout.databaseName("legacy", name))
        }
        val absolute = File("workspace-fixture.db").absolutePath
        assertEquals(absolute, AccountWorkspaceLayout.databaseName(first, absolute))
    }
}
