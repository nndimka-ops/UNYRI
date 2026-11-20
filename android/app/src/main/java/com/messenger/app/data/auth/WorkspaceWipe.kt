package com.messenger.app.data.auth

import android.content.Context
import com.messenger.app.data.secure.WorkspaceSecrets
import java.io.File

/** Only private workspace paths are accepted; never follows a link out of the workspace. */
internal object WorkspaceWipe {
    fun erase(context: Context, key: String) {
        require(AccountWorkspaceLayout.validKey(key))
        val base = AccountWorkspaceContext.installationContext(context)
        for (name in base.databaseList()) {
            if (if (key == AccountWorkspaceLayout.LEGACY) !name.startsWith("account_") && !name.startsWith("androidx.") && !name.startsWith("com.google.") && !name.startsWith("firebase") else name.startsWith("account_${key}_")) {
                check(base.deleteDatabase(name) || !base.getDatabasePath(name).exists()) { "Could not erase account database" }
            }
        }
        val prefsRoot = File(base.applicationInfo.dataDir, "shared_prefs")
        for (file in prefsRoot.listFiles().orEmpty()) {
            val name = file.name.removeSuffix(".bak").removeSuffix(".xml")
            val shared = setOf("local-accounts", "local_security_decisions", "installation-identity", "native-app-lock", "coco_language", "coco_theme", "client-config-v1", "coco-drawer", "pending-logouts", "installation-push-bindings-v1")
            val owned = if (key == AccountWorkspaceLayout.LEGACY) !name.startsWith("account_") && name !in shared && !name.startsWith("com.google.") && !name.startsWith("androidx.") else name.startsWith("account_${key}_")
            if (owned) {
                check(base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                base.deleteSharedPreferences(name)
            }
        }
        // The legacy root contains installation state too: enumerate only account folders there.
        for (root in listOf(base.filesDir, base.noBackupFilesDir, base.cacheDir, base.codeCacheDir)) {
            if (key != AccountWorkspaceLayout.LEGACY) eraseWithin(root, AccountWorkspaceLayout.directory(key, root))
            else for (child in root.listFiles().orEmpty()) if (child.name !in setOf("accounts", "account-avatars", "installation", "installation-protection") &&
                !child.name.startsWith("androidx.") && !child.name.startsWith("firebase") && !child.name.startsWith("PersistedInstallation") && !child.name.startsWith("com.google.")) eraseWithin(root, child)
        }
        WorkspaceSecrets.eraseKeys(key)
        base.getSharedPreferences("installation-push-bindings-v1", Context.MODE_PRIVATE).edit().remove(key).commit()
    }

    internal fun eraseWithin(root: File, target: File) {
        val canonicalRoot = root.canonicalFile
        require(target.absoluteFile.toPath().normalize().startsWith(canonicalRoot.toPath()))
        if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
            check(target.delete() || !target.exists())
            return
        }
        require(target.canonicalFile.toPath().startsWith(canonicalRoot.toPath()))
        if (target.isDirectory) target.listFiles().orEmpty().forEach { eraseWithin(canonicalRoot, it) }
        check(target.delete() || !target.exists()) { "Could not erase account file" }
    }
}
