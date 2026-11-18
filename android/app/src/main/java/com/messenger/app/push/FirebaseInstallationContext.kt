package com.messenger.app.push

import android.content.Context
import android.content.ContextWrapper

/** Firebase identifies the physical app installation, independently of the selected account.
 * FirebaseApp normalizes its input to applicationContext, so returning the workspace Application
 * here would silently put FIS files and FCM preferences back into account-scoped storage.
 */
internal class FirebaseInstallationContext(context: Context) : ContextWrapper(frameworkContext(context)) {
    override fun getApplicationContext(): Context = this
}

private fun frameworkContext(context: Context): Context {
    var base = context
    while (base is ContextWrapper) base = base.baseContext
    return base
}
