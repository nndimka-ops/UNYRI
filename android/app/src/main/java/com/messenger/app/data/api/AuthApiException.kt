package com.messenger.app.data.api

/** Uniform API error shape returned by the backend: `{"error": {"code", "message"}}`. */
sealed class AuthApiException(message: String) : Exception(message) {
    class Api(val code: String, message: String, val httpStatus: Int? = null) : AuthApiException(message)
    class Network(message: String, val httpStatus: Int? = null) : AuthApiException(message)
}
