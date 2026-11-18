package com.messenger.app.data.auth

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.messenger.app.MessengerApp
import java.io.File

/** Provider contexts are created independently of Activity/Application wrappers.
 * Resolve configured roots through the same fixed workspace as file creators.
 * Workspace path prefixes also keep a previously granted URI from resolving a
 * different account's file after the app switches its workspace. */
class AccountFileProvider : FileProvider() {
    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context.applicationContext, info)
    }

    private fun owned(uri: Uri): Uri {
        val app = requireNotNull(context).applicationContext as MessengerApp
        val path = AccountFileProviderPaths.unwrap(app.accountWorkspaces.activeKey, uri.encodedPath.orEmpty())
            ?: throw SecurityException("File belongs to another account workspace")
        return uri.buildUpon().encodedPath(path).build()
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = super.openFile(owned(uri), mode)
    override fun getType(uri: Uri): String? = super.getType(owned(uri))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        super.query(owned(uri), projection, selection, selectionArgs, sortOrder)
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        super.delete(owned(uri), selection, selectionArgs)

    companion object {
        fun uriForFile(context: Context, authority: String, file: File, displayName: String? = null): Uri {
            val app = context.applicationContext as MessengerApp
            val uri = if (displayName == null) FileProvider.getUriForFile(app, authority, file)
                else FileProvider.getUriForFile(app, authority, file, displayName)
            return uri.buildUpon().encodedPath(AccountFileProviderPaths.wrap(app.accountWorkspaces.activeKey, uri.encodedPath.orEmpty())).build()
        }
    }
}

/** Pure path policy: URI grants compare paths, so the workspace is part of the
 * path rather than a query parameter. Pre-workspace URIs retain legacy scope. */
internal object AccountFileProviderPaths {
    private const val PREFIX = "/workspace/"

    fun wrap(workspace: String, path: String): String {
        require(AccountWorkspaceLayout.validKey(workspace) && path.startsWith('/') && path != "/")
        return if (workspace == AccountWorkspaceLayout.LEGACY) path else "$PREFIX$workspace$path"
    }

    fun unwrap(workspace: String, path: String): String? {
        if (!AccountWorkspaceLayout.validKey(workspace) || !path.startsWith('/') || path == "/") return null
        if (!path.startsWith(PREFIX)) return path.takeIf { workspace == AccountWorkspaceLayout.LEGACY }
        val rest = path.removePrefix(PREFIX)
        val split = rest.indexOf('/')
        if (split <= 0 || split == rest.lastIndex || rest.substring(0, split) != workspace) return null
        return rest.substring(split)
    }
}