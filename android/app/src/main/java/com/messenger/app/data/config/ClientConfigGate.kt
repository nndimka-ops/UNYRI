package com.messenger.app.data.config

import android.content.Context
import com.messenger.app.BuildConfig
import com.messenger.app.data.api.ClientConfigApi
import com.messenger.app.data.api.ClientPlatformPolicy
import com.messenger.app.data.api.ServerEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.net.URI

@Serializable
data class ClientConfigEntry(val fetchedAt: Long, val policy: ClientPlatformPolicy? = null)

interface ClientConfigStorage {
    fun entries(): Map<String, ClientConfigEntry>
    fun saveEntries(entries: Map<String, ClientConfigEntry>)
    fun dismissed(): Map<String, String>
    fun saveDismissed(versions: Map<String, String>)
}

private class PreferenceClientConfigStorage(context: Context) : ClientConfigStorage {
    private val preferences = context.getSharedPreferences("client-config-v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    override fun entries(): Map<String, ClientConfigEntry> = runCatching {
        json.parseToJsonElement(preferences.getString("entries", null) ?: "{}").jsonObject.mapNotNull { (origin, value) ->
            runCatching { origin to json.decodeFromJsonElement<ClientConfigEntry>(value) }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())
    override fun saveEntries(entries: Map<String, ClientConfigEntry>) {
        preferences.edit().putString("entries", json.encodeToString(entries)).apply()
    }
    override fun dismissed(): Map<String, String> = runCatching {
        json.decodeFromString<Map<String, String>>(preferences.getString("dismissed", null) ?: "{}")
    }.getOrDefault(emptyMap())
    override fun saveDismissed(versions: Map<String, String>) {
        preferences.edit().putString("dismissed", json.encodeToString(versions)).apply()
    }
}

/** App-scoped, origin-scoped compatibility state. Refresh is event-driven, never a timer. */
class ClientConfigGate(
    private val currentVersion: String,
    initialOrigin: String,
    private val storage: ClientConfigStorage,
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> ClientPlatformPolicy?,
    private val callInProgress: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Requirement(val current: String, val minimum: String, val host: String, val updateUrl: String?)
    data class Recommendation(val version: String, val origin: String, val updateUrl: String?)
    data class Decision(val required: Requirement? = null, val recommended: Recommendation? = null)
    enum class Outcome { APPLIED, THROTTLED, UNREACHABLE }

    private var origin = AppVersion.originKey(initialOrigin)
    private val _decision = MutableStateFlow(Decision())
    val decision: StateFlow<Decision> = _decision
    private val mutex = Mutex()
    private val lastFetch = linkedMapOf<String, Long>()
    private var coldOrigin: String? = origin
    private val inFlight = mutableMapOf<String, Deferred<Outcome>>()
    private var pending: Requirement? = null

    init {
        storage.entries()[origin]?.let { apply(it, origin, coldLaunch = true) }
    }

    suspend fun refresh(server: String, force: Boolean = false): Outcome {
        val key = AppVersion.originKey(server)
        val task = mutex.withLock {
            if (key != origin) {
                origin = key
                coldOrigin = null
                pending = null
                _decision.value = Decision()
                storage.entries()[key]?.let { apply(it, key, coldLaunch = false) }
            }
            // A newly discovered requirement waits for the next foreground/refresh,
            // even when the call ends while this activity remains visible.
            pending?.takeUnless { callInProgress() }?.let {
                pending = null
                _decision.value = Decision(required = it)
            }
            inFlight[key]?.let { return@withLock it }
            val last = lastFetch[key]
            if (!force && last != null && now() - last < THROTTLE_MS) return Outcome.THROTTLED
            lastFetch.remove(key)
            lastFetch[key] = now()
            while (lastFetch.size > MAX_ORIGINS) lastFetch.remove(lastFetch.keys.first())
            val coldLaunch = coldOrigin == key
            coldOrigin = null
            scope.async(start = CoroutineStart.LAZY) { fetchAndApply(key, coldLaunch) }.also { inFlight[key] = it }
        }
        return try { task.await() } finally {
            mutex.withLock { if (inFlight[key] === task && task.isCompleted) inFlight.remove(key) }
        }
    }

    suspend fun recheck(): Outcome = refresh(origin, force = true)

    fun dismissRecommendation() {
        val recommendation = _decision.value.recommended ?: return
        val dismissed = storage.dismissed().toMutableMap()
        dismissed.remove(recommendation.origin)
        dismissed[recommendation.origin] = recommendation.version
        storage.saveDismissed(dismissed.entries.toList().takeLast(MAX_ORIGINS).associate { it.toPair() })
        _decision.value = _decision.value.copy(recommended = null)
    }

    private suspend fun fetchAndApply(key: String, coldLaunch: Boolean): Outcome {
        val policy = try { fetch(key) } catch (error: CancellationException) { throw error }
        catch (_: Exception) { return Outcome.UNREACHABLE }
        mutex.withLock {
            val entry = ClientConfigEntry(now(), policy)
            val entries = storage.entries().toMutableMap().apply { put(key, entry) }
            storage.saveEntries(entries.entries.sortedWith(compareByDescending<Map.Entry<String, ClientConfigEntry>> { it.value.fetchedAt }.thenByDescending { it.key == key }).take(MAX_ORIGINS).associate { it.toPair() })
            if (origin == key) apply(entry, key, coldLaunch = coldLaunch)
        }
        return Outcome.APPLIED
    }

    private fun apply(entry: ClientConfigEntry, key: String, coldLaunch: Boolean) {
        val policy = entry.policy
        val updateUrl = AppVersion.trustedUpdateUrl(policy?.updateUrl)
        val minimum = policy?.minimum
        if (minimum != null && AppVersion.compare(currentVersion, minimum)?.let { it < 0 } == true) {
            val requirement = Requirement(currentVersion, minimum, URI(key).host ?: key, updateUrl)
            if (_decision.value.required == null && !coldLaunch && callInProgress()) {
                pending = requirement
                return
            }
            pending = null
            _decision.value = Decision(required = requirement)
            return
        }
        pending = null
        val recommended = policy?.recommended?.takeIf {
            AppVersion.compare(currentVersion, it)?.let { comparison -> comparison < 0 } == true && storage.dismissed()[key] != it
        }?.let { Recommendation(it, key, updateUrl) }
        _decision.value = Decision(recommended = recommended)
    }

    companion object {
        const val THROTTLE_MS = 30 * 60 * 1000L
        const val MAX_ORIGINS = 8

        fun create(context: Context, client: OkHttpClient, scope: CoroutineScope, callInProgress: () -> Boolean): ClientConfigGate {
            val api = ClientConfigApi(client)
            return ClientConfigGate(BuildConfig.VERSION_NAME, ServerEndpoint.current(), PreferenceClientConfigStorage(context), scope, api::fetch, callInProgress)
        }
    }
}
