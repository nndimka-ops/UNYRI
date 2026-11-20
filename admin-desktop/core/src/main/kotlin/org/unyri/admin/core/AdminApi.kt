package org.unyri.admin.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.Instant

fun jsonBody(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in fields) when (value) {
        null -> put(key, JsonNull)
        is Boolean -> put(key, value)
        is Number -> put(key, JsonPrimitive(value))
        is JsonElement -> put(key, value)
        else -> put(key, value.toString())
    }
}

class AdminApi(val origin: Origin, private val transport: Transport = JvmTransport(origin)) {
    @Volatile var session: Session? = null; private set
    private inline fun <reified T> call(method: String, path: String, body: JsonObject? = null, bearer: Boolean = true, admin: Boolean = true): T {
        val current = session
        if (bearer && current == null || admin && current?.admin.isNullOrBlank()) throw AdminFailure(FailureKind.AUTH)
        if (bearer && current != null && runCatching { Instant.now().plusSeconds(30) >= Instant.parse(current.accessExpires) }.getOrDefault(true))
            throw AdminFailure(FailureKind.AUTH)
        val text = transport.send(WireRequest(method, path, body?.toString(), if (bearer) current?.access else null, if (admin) current?.admin else null))
        return try { JsonBudget.check(text); wireJson.decodeFromString<T>(text) } catch (e: AdminFailure) { throw e }
        catch (_: Exception) { throw ProtocolFailure() }
    }
    fun login(username: String, password: String, installation: String): AdminStatus {
        val result = call<LoginResponse>("POST", "/auth/login", jsonBody(
            "username" to username, "password" to password, "installation_id" to installation,
            "device_platform" to "desktop", "device_name" to "UNYRI Admin Desktop",
            "os_version" to System.getProperty("os.name"), "app_version" to "0.1.0"), false, false)
        PaperCrypto.uuid(result.user.id); PaperCrypto.uuid(result.device_id)
        if (result.access_token.isBlank() || result.access_token.length > 8192 ||
            runCatching { Instant.parse(result.access_token_expires_at).isAfter(Instant.now().plusSeconds(30)) }.getOrDefault(false).not()) throw ProtocolFailure()
        session = Session(origin.value, result.access_token, userId = result.user.id, username = result.user.username,
            deviceId = result.device_id, role = "", accessExpires = result.access_token_expires_at)
        try {
            val me = call<Me>("GET", "/users/me", admin = false)
            if (me.id != result.user.id || me.admin_role !in setOf("owner", "moderator")) throw AdminFailure(FailureKind.FORBIDDEN)
            session = session!!.copy(role = me.admin_role!!)
            return status()
        } catch (error: Exception) { runCatching { logout() }; throw error }
    }
    fun status(): AdminStatus {
        val result = call<AdminStatus>("GET", "/admin/status", admin = false)
        result.policy(); PaperCrypto.uuid(result.server_id)
        session?.let { current ->
            if (current.serverId.isNotEmpty() && current.serverId != result.server_id) throw ProtocolFailure()
            session = current.copy(serverId = result.server_id)
        }
        return result
    }
    fun setup(password: String, policy: Policy, status: AdminStatus): Setup {
        if (status.auth_protocol == 1 && policy != Policy.BOTH) throw ProtocolFailure()
        val body = jsonBody("password" to password).toMutableMap()
        if (status.auth_protocol == 2) body["auth_policy"] = JsonPrimitive(policy.wire)
        return call<Setup>("POST", "/admin/setup", JsonObject(body), admin = false).also {
            if (Policy.parse(it.auth_policy) != policy) throw ProtocolFailure()
        }
    }
    fun unlock(password: String, code: String, paper: String, policy: Policy, status: AdminStatus): Unlock {
        val current = session ?: throw AdminFailure(FailureKind.AUTH)
        if (status.configured && status.policy() != policy) throw ProtocolFailure()
        val body = jsonBody("password" to password).toMutableMap()
        if (status.auth_protocol == 2) body["auth_policy"] = JsonPrimitive(policy.wire)
        if (policy.totp) {
            if (code.isBlank()) throw AdminFailure(FailureKind.INVALID)
            body["code"] = JsonPrimitive(code.trim())
        }
        if (policy.paper) {
            val public = PaperCrypto.publicKey(paper)
            if (!status.signing_public_key.isNullOrEmpty() && status.signing_public_key != public) throw AdminFailure(FailureKind.INVALID)
            val c = call<Challenge>("POST", "/admin/key-challenge", jsonBody("purpose" to "unlock", "public_key" to public), admin = false)
            val proof = PaperCrypto.proof(paper, c, current.serverId, current.userId, "unlock")
            body["key_proof"] = wireJson.encodeToJsonElement(proof)
        }
        val unlocked = call<Unlock>("POST", "/admin/unlock", JsonObject(body), admin = false)
        PaperCrypto.decode(unlocked.token, 32)
        if (status.auth_protocol == 2 && Policy.parse(unlocked.auth_policy) != policy) throw ProtocolFailure()
        session = current.copy(admin = unlocked.token, unlockedAt = Instant.now().toString())
        return unlocked
    }
    fun restore(saved: Session) {
        if (!saved.optedIn || saved.origin != origin.value || !saved.valid()) throw AdminFailure(FailureKind.AUTH)
        session = saved
        try { status(); summary() } catch (error: Exception) { session = null; throw error }
    }
    fun health() = call<Health>("GET", "/health", bearer = false, admin = false)
    fun serverInfo() = call<ServerPolicy>("GET", "/server-info", bearer = false, admin = false)
    fun summary() = call<Summary>("GET", "/admin/summary")
    fun users(query: String, status: String, cursor: String?) = call<Page<UserRow>>("GET", query("/admin/users", "query" to query, "status" to status, "cursor" to cursor, "limit" to "50")).checked()
    fun user(id: String) = call<UserDetail>("GET", "/admin/users/${PaperCrypto.uuid(id)}")
    fun queue(feedback: Boolean, status: String, cursor: String?) = call<Page<QueueRow>>("GET",
        query(if (feedback) "/admin/feedback" else "/admin/reports", "status" to status, "cursor" to cursor, "limit" to "50")).checked()
    fun feedback(id: String) = call<FeedbackDetail>("GET", "/admin/feedback/${PaperCrypto.uuid(id)}")
    private fun image(path: String, maximum: Int): BinaryBody {
        val s = session ?: throw AdminFailure(FailureKind.AUTH)
        if (!s.valid()) throw AdminFailure(FailureKind.AUTH)
        return transport.download(WireRequest("GET",path,null,s.access,s.admin),maximum)
    }
    fun avatar(id: String) = image("/admin/users/${PaperCrypto.uuid(id)}/avatar?size=640",4*1024*1024)
    fun screenshot(id: String, index: Int): BinaryBody {
        if (index !in 0..2) throw AdminFailure(FailureKind.INVALID)
        return image("/admin/feedback/${PaperCrypto.uuid(id)}/screenshots/$index",1024*1024)
    }
    fun audit(cursor: String?) = call<Page<JsonObject>>("GET", query("/admin/audit", "cursor" to cursor, "limit" to "50")).checked()
    fun performance() = call<Performance>("GET", "/admin/performance")
    fun operations() = call<JsonObject>("GET", "/admin/operations")
    fun selfCheck() = call<JsonObject>("GET", "/admin/self-check")
    fun policy() = call<ServerPolicy>("GET", "/admin/server-policy")
    fun notes(id: String) = call<Page<JsonObject>>("GET", "/admin/users/${PaperCrypto.uuid(id)}/notes?limit=50").checked()
    fun timeline(id: String) = call<Page<JsonObject>>("GET", "/admin/users/${PaperCrypto.uuid(id)}/timeline?limit=50").checked()
    fun addNote(id: String, body: String) {
        requireReason(body); call<JsonObject>("POST", "/admin/users/${PaperCrypto.uuid(id)}/notes", jsonBody("body" to body))
    }
    fun review(feedback: Boolean, id: String, status: String, note: String, reason: String) {
        if (status !in (if (feedback) setOf("open", "resolved", "dismissed") else setOf("open", "in_progress", "resolved", "dismissed"))) throw AdminFailure(FailureKind.INVALID)
        if (status in setOf("resolved", "dismissed")) requireReason(reason)
        if (note.length > 2000 || reason.length > 2000) throw AdminFailure(FailureKind.INVALID)
        val path = if (feedback) "/admin/feedback/${PaperCrypto.uuid(id)}/review" else "/admin/reports/${PaperCrypto.uuid(id)}"
        call<JsonObject>("POST", path, jsonBody("status" to status, "note" to note, "decision_reason" to reason))
    }
    fun suspend(id: String, blocked: Boolean, reason: String) {
        requireReason(reason); call<JsonObject>("POST", "/admin/users/${PaperCrypto.uuid(id)}/suspension",
            jsonBody("blocked" to blocked, "reason" to reason, "until" to null, "include_installations" to false))
    }
    fun readOnly(id: String, enabled: Boolean, reason: String) {
        requireReason(reason); call<JsonObject>("POST", "/admin/users/${PaperCrypto.uuid(id)}/read-only", jsonBody("enabled" to enabled, "reason" to reason, "until" to null))
    }
    fun logoutDevice(id: String, reason: String) {
        requireReason(reason); call<JsonObject>("POST", "/admin/devices/${PaperCrypto.uuid(id)}/logout", jsonBody("reason" to reason))
    }
    fun deleteUser(id: String, username: String, reason: String) {
        requireReason(reason); call<JsonObject>("POST", "/admin/users/${PaperCrypto.uuid(id)}/delete", jsonBody("confirm_username" to username, "reason" to reason))
    }
    fun validateAdmin() { summary() }
    fun logout() {
        var failure: Exception? = null
        try { if (!session?.admin.isNullOrEmpty()) call<JsonObject>("POST", "/admin/lock") } catch (e: Exception) { failure = e }
        try { if (session != null) call<JsonObject>("POST", "/auth/logout", admin = false) } catch (e: Exception) { if (failure == null) failure = e }
        finally { session = null }
        failure?.let { throw it }
    }
    companion object {
        fun requireReason(reason: String) { if (reason.isBlank() || reason.codePointCount(0, reason.length) > 2000) throw AdminFailure(FailureKind.INVALID) }
        fun query(path: String, vararg values: Pair<String, String?>): String {
            val parts = values.filter { !it.second.isNullOrEmpty() }.map { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
            return if (parts.isEmpty()) path else path + "?" + parts.joinToString("&")
        }
    }
}
