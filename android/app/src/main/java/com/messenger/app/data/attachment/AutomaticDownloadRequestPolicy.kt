package com.messenger.app.data.attachment

import okhttp3.Interceptor

/** Runs after OkHttp dispatcher queueing, before the connection/HTTP request. */
internal class AutomaticDownloadRequestPolicy(val check: () -> Unit)
internal val automaticDownloadPolicyInterceptor = Interceptor { chain ->
    val request = chain.request()
    request.tag(AutomaticDownloadRequestPolicy::class.java)?.check?.invoke()
    chain.proceed(request)
}
