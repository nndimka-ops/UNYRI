package com.messenger.app.ui.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ---------------------------------------------------------------------------
// Admin Product Pack 2.1 - typed administration transport.
//
// The screens never see JSON text. `AdminPanel` supplies two raw callbacks that
// already enforce the security model (HTTPS only, redirect rejection, the
// `x-coco-admin` header, stale-result rejection via panel generation); this
// class only turns their payloads into typed DTOs.
// ---------------------------------------------------------------------------

internal object AdminJson {
    /**
     * Unknown keys are ignored so a new server field cannot break an older
     * client, and defaults are not encoded so an omitted optional field means
     * exactly what the server's own default means.
     */
    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
}

internal class AdminTransport(
    /** Returns the response body of an authenticated administration call. */
    val text: suspend (path: String, body: String?) -> String,
    /** Returns raw bytes (screenshot endpoint), never parsed as JSON. */
    val blob: suspend (path: String) -> ByteArray,
) {
    suspend inline fun <reified T> get(path: String): T =
        AdminJson.format.decodeFromString<T>(text(path, null))

    suspend inline fun <reified B : Any, reified T> post(path: String, body: B): T =
        AdminJson.format.decodeFromString<T>(text(path, AdminJson.format.encodeToString(body)))

    /** For endpoints that answer `{"ok":true}`. */
    suspend inline fun <reified B : Any> postOk(path: String, body: B): Boolean =
        post<B, AdminOkDto>(path, body).ok

    /**
     * For POST endpoints that take no body. An empty JSON object is sent so the
     * request still has a body to parse, and the server ignores it.
     */
    suspend inline fun <reified T> postEmpty(path: String): T =
        AdminJson.format.decodeFromString<T>(text(path, "{}"))

    suspend fun screenshot(path: String): ByteArray = blob(path)
}
