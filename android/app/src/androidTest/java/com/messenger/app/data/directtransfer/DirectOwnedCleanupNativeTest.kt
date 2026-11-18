package com.messenger.app.data.directtransfer

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import com.messenger.app.data.api.AuthApi
import com.messenger.app.data.api.LoginRequestDto
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test

/** Crash recovery for exact owned QA identities; no credential leaves this app. */
class DirectOwnedCleanupNativeTest {
    @Test fun deleteExactOwnedCrashAccounts(): Unit = runBlocking(Dispatchers.IO) {
        val harness = DirectServiceNativeTest()
        val context = harness.context
        check(context.packageName == "com.messenger.app.directqa")
        val run = requireNotNull(InstrumentationRegistry.getArguments().getString("run_id"))
        require(canonicalUuid(run))
        for (role in listOf("a", "b")) {
            val prefs = EncryptedSharedPreferences.create(context, "direct-service-$run-$role-keys",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            val username = "dfqa" + run.replace("-", "").take(12) + role
            check(prefs.getString("qa_run", null) == run && prefs.getString("qa_role", null) == role)
            check(prefs.getString("qa_username", null) == username)
            val api = AuthApi(harness.client, harness.json, harness.base)
            val password = requireNotNull(prefs.getString("qa_password", null))
            val response = api.login(LoginRequestDto(username, password, requireNotNull(prefs.getString("qa_installation", null)),
                installationCredential = requireNotNull(prefs.getString("qa_installation_credential", null))))
            check(response.user.id == prefs.getString("qa_user_id", null) && response.user.username == username)
            api.deleteAccount(response.accessToken, password)
            File(context.filesDir, "direct-service-cleanup-${response.user.id}.json").writeText(JSONObject()
                .put("user_id", response.user.id).put("username", username).put("deleted_through_normal_api", true)
                .put("crash_recovery", true).toString())
            check(prefs.edit().clear().commit())
        }
    }
}
