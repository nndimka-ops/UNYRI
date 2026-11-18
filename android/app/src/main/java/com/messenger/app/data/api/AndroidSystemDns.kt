package com.messenger.app.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.util.concurrent.Executor
import okhttp3.Dns

/** Default network is deliberately NOT replaced with a Wi-Fi/cellular network. */
internal object AndroidSystemDns {
    fun create(context: Context): Dns {
        if (Build.VERSION.SDK_INT < 29) return Dns.SYSTEM
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        return RecoveringSystemDns(
            primary = Dns.SYSTEM,
            networkIdentity = {
                connectivity?.let {
                    val active = it.activeNetwork
                    val bound = it.boundNetworkForProcess
                    if (active == null && bound == null) null else active to bound
                }
            },
            isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            queryFactory = { Api29.newQuery() },
            report = { android.util.Log.i("DnsRecovery", "result=$it") },
        )
    }
    @RequiresApi(29)
    private object Api29 {
        @DoNotInline fun newQuery(): SystemDnsRecoveryQuery = AndroidResolverQuery()
    }
}

/** Injection seam verifies default/VPN routing without any real DNS request. */
@RequiresApi(29)
internal fun interface AndroidDnsQueryRunner {
    fun query(network: Network?, hostname: String, flags: Int, executor: Executor,
              cancellation: CancellationSignal, callback: DnsResolver.Callback<List<InetAddress>>)
}

@RequiresApi(29)
internal class AndroidResolverQuery(
    private val runner: AndroidDnsQueryRunner = AndroidDnsQueryRunner { network, hostname, flags, executor, cancellation, callback ->
        DnsResolver.getInstance().query(network, hostname, flags, executor, cancellation, callback)
    },
) : SystemDnsRecoveryQuery {
    private val cancellation = CancellationSignal()
    override fun start(hostname: String, callbackExecutor: Executor, answer: (List<InetAddress>?) -> Unit) {
        // null delegates routing to Android's per-UID default network, including
        // VPN and Private DNS. FLAG_EMPTY keeps the OS cache/policy intact.
        runner.query(null, hostname, DnsResolver.FLAG_EMPTY, callbackExecutor, cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(value: List<InetAddress>, rcode: Int) = answer(value.takeIf { rcode == 0 })
                override fun onError(error: DnsResolver.DnsException) = answer(null)
            })
    }
    override fun cancel() { cancellation.cancel() }
}
