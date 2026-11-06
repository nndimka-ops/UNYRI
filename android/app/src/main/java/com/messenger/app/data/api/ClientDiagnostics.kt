package com.messenger.app.data.api

import android.content.Context
import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Opt-in, best-effort error counters. Never accepts message text or exception strings. */
class ClientDiagnostics(context: Context) {
    private val preferences = context.getSharedPreferences("coco_diagnostics", Context.MODE_PRIVATE)
    private val transport = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private var lastAttempt: Long? = null
    @Synchronized fun report(request: Request, status: Int) {
        if (!preferences.getBoolean("enabled", false)) return
        val authorization = request.header("Authorization") ?: return
        val operation = operation(request.url.encodedPath) ?: return
        if (status != 0 && status !in 400..599) return
        val now = SystemClock.elapsedRealtime()
        if (lastAttempt?.let { now - it < 60_000 } == true) return
        lastAttempt = now
        val body = """{"platform":"android","operation":"$operation","status":$status}"""
        val url = request.url.newBuilder().encodedPath("/diagnostics").query(null).fragment(null).build()
        val upload = Request.Builder().url(url).header("Authorization", authorization)
            .post(body.toRequestBody("application/json".toMediaType())).build()
        transport.newCall(upload).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }
    companion object {
        fun operation(path: String): String? = when {
            path.startsWith("/chats/") && (path.endsWith("/read") || path.endsWith("/delivered")) -> "receipts"
            path.startsWith("/chats/") && path.endsWith("/messages") -> "messages"
            path == "/chats" || path.startsWith("/chats/") -> "chats"
            path == "/sync" -> "sync"
            path == "/calls" || path.startsWith("/calls/") -> "calls"
            path == "/attachments" || path.startsWith("/attachments/") -> "attachments"
            path == "/keys" || path.startsWith("/keys/") -> "keys"
            else -> null
        }
    }
}
