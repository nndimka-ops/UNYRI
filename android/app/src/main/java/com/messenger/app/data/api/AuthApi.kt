package com.messenger.app.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** Thin REST client for the auth endpoints. Plain OkHttp + kotlinx.serialization
 *  to avoid pulling in a larger HTTP framework for four endpoints. */
class AuthApi(
    client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
) {
    // Auth request bodies contain passwords, refresh tokens and installation
    // proofs. OkHttp's internal 307/308 follow-up bypasses app interceptors and
    // can retain that body on another origin. Keep this policy local to auth;
    // attachment/media clients retain their own signed-URL redirect behavior.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** No Authorization header; the proof is returned only in the TLS response body. */
    suspend fun createInstallationCredential(origin: String): String {
        require(java.net.URI(origin).scheme == "https") { "Installation protection requires HTTPS" }
        return json.decodeFromString<InstallationCredentialDto>(
            execute(Request.Builder().url("${origin.trimEnd('/')}/auth/installations").post("".toRequestBody()).build()),
        ).credential
    }

    suspend fun register(request: RegisterRequestDto): AuthResponseDto {
        requireProofTransport(request.installationCredential)
        return post("/auth/register", json.encodeToString(RegisterRequestDto.serializer(), request))
    }

    suspend fun login(request: LoginRequestDto): AuthResponseDto {
        requireProofTransport(request.installationCredential)
        return post("/auth/login", json.encodeToString(LoginRequestDto.serializer(), request))
    }

    private fun requireProofTransport(credential: String?) {
        if (credential != null) require(java.net.URI(ServerEndpoint.resolve(baseUrl)).scheme == "https") { "Installation protection requires HTTPS" }
    }

    suspend fun refresh(request: RefreshRequestDto): RefreshResponseDto =
        post("/auth/refresh", json.encodeToString(RefreshRequestDto.serializer(), request))

    suspend fun logout(accessToken: String) {
        val httpRequest = Request.Builder()
            .url("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/auth/logout")
            .header("Authorization", "Bearer $accessToken")
            .post("".toRequestBody())
            .build()
        execute(httpRequest)
    }

    suspend fun deleteAccount(accessToken: String, password: String) {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("password", kotlinx.serialization.json.JsonPrimitive(password))
        }
        execute(Request.Builder()
            .url("${ServerEndpoint.resolve(baseUrl)}/users/me/delete")
            .header("Authorization", "Bearer $accessToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
    }

    suspend fun historyContext(accessToken: String): HistoryContextDto = json.decodeFromString(
        execute(Request.Builder().url("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}/auth/history-context")
            .header("Authorization", "Bearer $accessToken").get().build()),
    )

    private suspend inline fun <reified T> post(path: String, jsonBody: String): T {
        val httpRequest = Request.Builder()
            .url("${com.messenger.app.data.api.ServerEndpoint.resolve(baseUrl)}$path")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseBody = execute(httpRequest)
        return json.decodeFromString(responseBody)
    }

    private suspend fun execute(request: Request): String = client.executeCancellableApi(request, json)
}
