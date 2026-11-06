package com.messenger.app.data.api

import kotlinx.serialization.json.Json

/** Maps a non-2xx HTTP response body to the uniform error shape shared by
 *  every backend endpoint (`{"error": {"code","message"}}`), reused by both
 *  [AuthApi] and [UsersApi] so error handling can't drift between them. */
internal fun parseApiException(json: Json, httpCode: Int, bodyString: String): AuthApiException {
    val parsed = runCatching { json.decodeFromString(ApiErrorDto.serializer(), bodyString) }.getOrNull()
    return if (parsed != null) {
        AuthApiException.Api(parsed.error.code, parsed.error.message, httpCode)
    } else if (httpCode in listOf(400, 404, 405, 410, 413, 415, 422)) {
        // A proxy may return HTML or an empty body for permanent request errors.
        AuthApiException.Api("http_error", "HTTP $httpCode", httpCode)
    } else {
        AuthApiException.Network("HTTP $httpCode", httpCode)
    }
}
