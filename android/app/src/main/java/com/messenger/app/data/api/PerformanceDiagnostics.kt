package com.messenger.app.data.api

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import com.messenger.app.BuildConfig
import com.messenger.app.domain.model.AuthSessionState
import com.messenger.app.domain.repository.AuthRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Bounded numeric diagnostics, never text, coordinates, URLs or content identifiers. */
class PerformanceDiagnostics(private val app: Application, private val auth: AuthRepository,
                             private val scope: CoroutineScope,
                             preferenceName: String = "coco_diagnostics") : Application.ActivityLifecycleCallbacks {
    private val prefs = app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS).build()
    private val events = ArrayDeque<JSONObject>()
    private var recording = UUID.randomUUID().toString()
    private var sequence = 0L
    private var acknowledged = 0L
    private var dropped = 0L
    @Volatile private var epoch = -1
    @Volatile private var origin = ""
    @Volatile private var enabled = prefs.getBoolean("enabled", false)
    @Volatile private var active = false
    @Volatile private var screen = "app"
    private var resumed: Activity? = null
    private var measuredWindow: Window? = null
    private var frameWindow: RenderedFrameWindow? = null
    private var lastTouch = 0L
    private val uploadWake = Channel<Unit>(Channel.CONFLATED)
    private val uploadAllowed = MutableStateFlow(false)
    private var uploader: Job? = null
    private fun updateUploadGate() {
        uploadAllowed.value = enabled && active && ownsSession() && prefs.getBoolean("performance_online", false)
        uploadWake.trySend(Unit)
    }
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "enabled" || key == "performance_online") handler.post {
            enabled = prefs.getBoolean("enabled", false)
            if (!enabled) clear()
            updateFrames()
            updateUploadGate()
        }
    }
    init {
        app.registerActivityLifecycleCallbacks(this)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        scope.launch { auth.authState.map { state ->
            (if (state is AuthSessionState.LoggedIn) auth.currentSessionEpoch() else -1) to ServerEndpoint.current()
        }.distinctUntilChanged().collectLatest { (currentEpoch, currentOrigin) ->
            synchronized(this@PerformanceDiagnostics) {
                clear(); epoch = currentEpoch
                origin = currentOrigin
            }
            // Main.immediate may emit during construction, before the listener exists.
            handler.post { updateFrames() }
            updateUploadGate()
        } }
        uploader = scope.launch { uploadAllowed.collectLatest { if (it) uploadLoop() } }
    }
    @Synchronized private fun clear() {
        events.clear(); recording = UUID.randomUUID().toString(); sequence = 0
        acknowledged = 0; dropped = 0
    }
    private fun ownsSession() = epoch >= 0 && auth.currentSessionEpoch() == epoch && ServerEndpoint.current() == origin
    @Synchronized private fun record(kind: String, metrics: Map<String, Double> = emptyMap()) {
        if (!enabled || !ownsSession()) return
        val now = System.currentTimeMillis()
        while (events.isNotEmpty() && (events.size >= 600 || now - events.first().getLong("at_ms") > 15 * 60_000)) {
            events.removeFirst(); dropped++
        }
        val numbers = JSONObject()
        metrics.filterValues { it.isFinite() && it >= 0 && it <= 1e12 }.forEach { (k, v) -> numbers.put(k, v) }
        events.addLast(JSONObject().put("sequence", ++sequence).put("at_ms", now)
            .put("kind", kind).put("screen", screen).put("metrics", numbers))
        uploadWake.trySend(Unit)
    }
    fun screen(route: String?) {
        val next = when {
            route == null -> "app"
            route.startsWith("chat/") -> "chat"
            route == "main" -> "chats"
            route.contains("search") -> "search"
            route.contains("media") -> "media"
            route == "login" || route == "register" -> "login"
            else -> "settings"
        }
        if (next != screen) { record("screen_close"); screen = next; record("screen_open") }
    }
    fun touch() {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTouch >= 250) { lastTouch = now; record("touch") }
    }
    fun network(duration: Double, status: Int) {
        if (!enabled) return
        record(if (status == 0 || status >= 400) "network_error" else "network_request",
            mapOf("duration_ms" to duration, "status" to status.toDouble()))
    }
    fun markProblem() = record("mark_problem")
    val isRecordingCalls: Boolean get() = enabled && ownsSession()
    /** Call wire events are validated together: an unknown key must never poison a snapshot. */
    fun callState(metrics: Map<String, Double>) {
        if (metrics.size > 16 || !com.messenger.app.data.call.CallDiagnosticEvent.allowedMetrics.containsAll(metrics.keys) ||
            !metrics.containsKey("operation") || !metrics.containsKey("phase")) return
        record("call_state", metrics)
    }
    /** Fixed history-transfer stage/category codes; no strings or payloads are accepted. */
    internal fun historyTransferFailure(
        phase: com.messenger.app.data.history.HistoryTransferPhase,
        failure: com.messenger.app.data.history.HistoryTransferFailure,
    ) = record("history_load", failure.metrics(phase))
    @Synchronized fun snapshot(online: Boolean = false): JSONObject? {
        if (!enabled || !ownsSession()) return null
        val now = System.currentTimeMillis()
        val eligible = events.filter { now - it.getLong("at_ms") <= 15 * 60_000 && (!online || it.getLong("sequence") > acknowledged) }
        val selected = if (online) eligible.take(64) else eligible.takeLast(600)
        if (selected.isEmpty()) return null
        fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9.,_+\\-]"), "_").take(64).ifEmpty { "unknown" }
        return JSONObject().put("schema_version", 1).put("recording_id", recording).put("platform", "android")
            .put("app_version", safe(BuildConfig.VERSION_NAME)).put("build", BuildConfig.VERSION_CODE.toString())
            .put("os_version", safe(android.os.Build.VERSION.RELEASE)).put("hardware", safe(android.os.Build.MODEL))
            .put("dropped", dropped).put("omitted", eligible.size - selected.size).put("events", JSONArray(selected))
    }
    private suspend fun uploadLoop() {
        var pause = 5_000L
        while (currentCoroutineContext().isActive) {
            uploadWake.receive()
            while (currentCoroutineContext().isActive) {
                val wait = DiagnosticsUploadPolicy.delayMillis(enabled, active, prefs.getBoolean("performance_online", false),
                    snapshot(true) != null, pause) ?: break
                delay(wait)
                if (!uploadAllowed.value) break
                val capturedEpoch = epoch; val capturedOrigin = origin
                val snapshot = snapshot(true) ?: break
                try {
                    val token = auth.currentAccessToken() ?: break
                    if (!ownsSession() || capturedEpoch != epoch || capturedOrigin != origin) break
                    val body = snapshot.toString()
                    check(body.toByteArray().size <= 32 * 1024)
                    val success = withContext(Dispatchers.IO) {
                        if (!enabled || !active || !prefs.getBoolean("performance_online", false) || !ownsSession() || capturedEpoch != epoch) return@withContext false
                        val request = Request.Builder().url("$capturedOrigin/performance").header("Authorization", "Bearer $token")
                            .post(body.toRequestBody("application/json".toMediaType())).build()
                        client.newCall(request).execute().use {
                            it.isSuccessful
                        }
                    }
                    synchronized(this) {
                        if (success && ownsSession() && capturedEpoch == epoch && snapshot.getString("recording_id") == recording) {
                            val batch = snapshot.getJSONArray("events")
                            acknowledged = maxOf(acknowledged, batch.getJSONObject(batch.length() - 1).getLong("sequence"))
                        }
                    }
                    pause = DiagnosticsUploadPolicy.afterAttempt(pause, success)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { pause = DiagnosticsUploadPolicy.afterAttempt(pause, false) }
            }
        }
    }
    /** Window metrics arrive only after a frame actually renders. Unlike a reposting
     * Choreographer callback, observing diagnostics cannot keep an idle window ticking.
     * gap_ms/max_gap_ms now describe rendering duration, never idle inter-frame time.
     */
    private val frameListener = Window.OnFrameMetricsAvailableListener { window, metrics, _ ->
        if (window !== measuredWindow) return@OnFrameMetricsAvailableListener
        if (!enabled || !active || !ownsSession()) { stopFrames(); return@OnFrameMetricsAvailableListener }
        val accounting = frameWindow ?: return@OnFrameMetricsAvailableListener
        val observation = accounting.frame(metrics.getMetric(FrameMetrics.TOTAL_DURATION), SystemClock.elapsedRealtimeNanos())
            ?: return@OnFrameMetricsAvailableListener
        if (observation.stalled) record("frame_gap", mapOf("gap_ms" to observation.durationMillis, "budget_ms" to accounting.budgetMillis))
        observation.summary?.let { summary ->
            record("frame_summary", mapOf("frames" to summary.frames.toDouble(), "late_frames" to summary.lateFrames.toDouble(),
                "max_gap_ms" to summary.maximumDurationMillis, "maximum_fps" to accounting.maximumFps))
            val runtime = Runtime.getRuntime()
            record("memory", mapOf("memory_mb" to (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0))
        }
    }
    private fun stopFrames() {
        measuredWindow?.let { window -> runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) } }
        measuredWindow = null
        frameWindow = null
    }
    private fun updateFrames() {
        stopFrames()
        val activity = resumed ?: return
        if (!enabled || !active || !ownsSession()) return
        // Read the display once on attach, rather than making a display service call per frame.
        @Suppress("DEPRECATION") val hz = activity.windowManager.defaultDisplay.refreshRate
        frameWindow = RenderedFrameWindow(hz)
        measuredWindow = activity.window
        try { activity.window.addOnFrameMetricsAvailableListener(frameListener, handler) }
        catch (_: RuntimeException) { measuredWindow = null; frameWindow = null }
    }
    fun close() {
        enabled = false; active = false; clear()
        uploader?.cancel(); uploadAllowed.value = false
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        app.unregisterActivityLifecycleCallbacks(this)
        handler.post { stopFrames() }
    }
    override fun onActivityResumed(activity: Activity) { resumed = activity; active = true; record("app_active"); updateFrames(); updateUploadGate() }
    override fun onActivityPaused(activity: Activity) { if (resumed === activity) { record("app_inactive"); resumed = null; active = false; updateFrames(); updateUploadGate() } }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
