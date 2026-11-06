package com.messenger.app.data.history

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import com.messenger.app.MessengerApp
import com.messenger.app.data.api.ServerEndpoint
import com.messenger.app.domain.call.CallUiState
import com.messenger.app.domain.model.AuthSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

internal object HistoryAutoPolicy {
    const val DAY=24*60*60*1000L
    fun due(now:Long,last:Long)=last<=0 || now<last || now-last>=DAY
}
object HistoryAutoBackup {
    private const val TAG="coco-history-auto"
    private fun prefs(context:Context)=context.getSharedPreferences("history-auto",Context.MODE_PRIVATE)
    private fun name(scope:String)="history-auto-"+java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString(""){"%02x".format(it)}
    fun enabled(context:Context,scope:String)=prefs(context).getBoolean("$scope:enabled",false)
    fun wifi(context:Context,scope:String)=prefs(context).getBoolean("$scope:wifi",true)
    fun last(context:Context,scope:String)=prefs(context).getLong("$scope:last",0)
    private fun blocked(context:Context,scope:String)=prefs(context).getLong("$scope:blockedUntil",0)>System.currentTimeMillis()
    internal fun defer(context:Context,scope:String) { prefs(context).edit().putLong("$scope:blockedUntil",System.currentTimeMillis()+HistoryAutoPolicy.DAY).commit() }
    fun configure(context:Context,scope:String,enabled:Boolean,wifi:Boolean) {
        check(prefs(context).edit().putBoolean("$scope:enabled",enabled).putBoolean("$scope:wifi",wifi).putLong("$scope:blockedUntil",0).putString("$scope:endpoint",ServerEndpoint.current()).commit())
        if(!enabled) WorkManager.getInstance(context).cancelUniqueWork(name(scope)) else schedule(context,scope)
        if(!enabled) WorkManager.getInstance(context).cancelUniqueWork(name(scope)+"-due")
    }
    private fun data(scope:String)=workDataOf("scope" to scope)
    private fun schedule(context:Context,scope:String) {
        val work=PeriodicWorkRequestBuilder<HistoryAutoWorker>(24,TimeUnit.HOURS)
            .setInputData(data(scope)).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(name(scope),ExistingPeriodicWorkPolicy.KEEP,work)
    }
    fun logout(context:Context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG) }
    fun reconcile(app:MessengerApp,foreground:Boolean=false) {
        val owner=(app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id ?: return logout(app)
        val entries=prefs(app).all
        entries.filter { it.key.endsWith(":enabled") && it.value==true }.keys.forEach { setting ->
            val scope=setting.removeSuffix(":enabled")
            if(scope.substringAfter(':')!=owner || entries["$scope:endpoint"]!=ServerEndpoint.current()) {
                WorkManager.getInstance(app).cancelUniqueWork(name(scope)); WorkManager.getInstance(app).cancelUniqueWork(name(scope)+"-due")
            } else {
                schedule(app,scope)
                if(foreground && !blocked(app,scope) && HistoryAutoPolicy.due(System.currentTimeMillis(),last(app,scope))) {
                    val work=OneTimeWorkRequestBuilder<HistoryAutoWorker>().setInputData(data(scope)).addTag(TAG)
                        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build()
                    WorkManager.getInstance(app).enqueueUniqueWork(name(scope)+"-due",ExistingWorkPolicy.KEEP,work)
                }
            }
        }
    }
    internal fun current(app:MessengerApp,scope:String):Boolean=enabled(app,scope) && !blocked(app,scope) &&
        (app.authRepository.authState.value as? AuthSessionState.LoggedIn)?.user?.id==scope.substringAfter(':') &&
        prefs(app).getString("$scope:endpoint",null)==ServerEndpoint.current()
    internal fun network(app:Context,scope:String):Boolean {
        val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // LAN-only Wi-Fi need not have validated Internet access.
        return !wifi(app,scope) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    internal fun success(app:Context,scope:String) { check(prefs(app).edit().putLong("$scope:last",System.currentTimeMillis()).putLong("$scope:blockedUntil",0).commit()) }
}
class HistoryAutoWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    companion object { private val serial = Mutex() }
    override suspend fun doWork():Result = serial.withLock { runWork() }
    private suspend fun runWork():Result {
        val app=applicationContext as MessengerApp; val scope=inputData.getString("scope") ?: return Result.failure()
        val epoch = app.authRepository.currentSessionEpoch()
        suspend fun writeCurrent(block: () -> Unit) { app.authRepository.withSessionWrite(epoch) { if (HistoryAutoBackup.current(app, scope)) block() } }
        return try {
            withTimeout(30_000) { app.historyReady.await() }
            if(!HistoryAutoBackup.current(app,scope) || !HistoryAutoPolicy.due(System.currentTimeMillis(),HistoryAutoBackup.last(app,scope))) return Result.success()
            if(!HistoryAutoBackup.network(app,scope) || app.callController.state.value !is CallUiState.Idle) return if(runAttemptCount<2) Result.retry() else Result.success()
            withTimeout(8*60*1000L) {
                coroutineScope {
                    val operation=async {
                        val session=app.historySettingsSession(); val status=session.status()
                        if(session.scopeId()!=scope) return@async false
                        if(!status.keySaved || !status.remote.desired_enabled) { writeCurrent { HistoryAutoBackup.configure(app,scope,false,HistoryAutoBackup.wifi(app,scope)) };return@async false }
                        session.backup(beforeStart={ HistoryAutoBackup.current(app,scope) && HistoryAutoPolicy.due(System.currentTimeMillis(),HistoryAutoBackup.last(app,scope)) && HistoryAutoBackup.network(app,scope) && app.callController.state.value is CallUiState.Idle }, onConfirmed={ HistoryAutoBackup.success(app,scope) }); true
                    }
                    val watch=launch { while(isActive) { delay(1000); if(!HistoryAutoBackup.current(app,scope) || !HistoryAutoBackup.network(app,scope) || app.callController.state.value !is CallUiState.Idle) operation.cancel() } }
                    try { if(operation.await()) writeCurrent { HistoryAutoBackup.success(app,scope) } }
                    finally { watch.cancel() }
                }
            }
            Result.success()
        } catch(e:CancellationException) { if(isStopped) throw e; if(runAttemptCount<2) Result.retry() else Result.success() }
          catch(_:HistoryAutoNotDue) { Result.success() }
          catch(_:HistorySourceNotReady) { writeCurrent { HistoryAutoBackup.defer(app,scope) };Result.success() }
          catch(_:javax.crypto.AEADBadTagException) { writeCurrent { HistoryAutoBackup.defer(app,scope) };Result.success() }
          catch(_:Exception) { if(runAttemptCount<2) Result.retry() else { writeCurrent { HistoryAutoBackup.defer(app,scope) };Result.success() } }
    }
}
