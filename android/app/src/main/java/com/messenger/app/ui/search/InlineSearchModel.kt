package com.messenger.app.ui.search

import com.messenger.app.domain.model.MessageSearchResult
import com.messenger.app.domain.model.PublicUser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

internal data class SearchPerson(val id: String, val title: String, val handle: String = "", val user: PublicUser? = null, val chatId: String? = null)
internal enum class InlineCategory { CHATS, MEDIA, LINKS, FILES, VOICE }
internal data class InlineSnapshot(val query: String = "", val people: List<SearchPerson> = emptyList(), val messages: List<MessageSearchResult> = emptyList(), val localFailed: Boolean = false)

internal object InlineSearchPolicy {
    const val DEBOUNCE_MS = 300L
    const val MESSAGE_LIMIT = 100
    const val DIRECTORY_PAGE = 30
    fun query(value: String): String {
        val normalized = value.trim().removePrefix("@").lowercase(Locale.ROOT)
        return normalized.substring(0, normalized.offsetByCodePoints(0, normalized.codePointCount(0, normalized.length).coerceAtMost(256)))
    }
    fun canSearchDirectory(value: String) = value.codePointCount(0, value.length) >= 3
    private fun fold(value: String) = java.text.Normalizer.normalize(query(value), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    fun rank(people: List<SearchPerson>, term: String, legacyLogins: Boolean = true): List<SearchPerson> {
        val normalized = fold(term)
        if (normalized.isEmpty()) return emptyList()
        return people.distinctBy { it.id }.map { person ->
            val publicMatch = !legacyLogins && person.user?.let { user ->
                user.publicNumber == term.filterNot { it.isWhitespace() } ||
                    user.publicUsername?.equals(term.removePrefix("@"), ignoreCase = true) == true
            } == true
            val score = if (publicMatch) 0 else (if (legacyLogins) listOf(person.title, person.handle) else listOf(person.title)).minOf { value ->
                val key = fold(value)
                when { key == normalized -> 0; key.startsWith(normalized) -> 1; key.contains(normalized) -> 2; else -> 3 }
            }
            person to score
        }.filter { it.second < 3 }.sortedWith(compareBy({ it.second }, { it.first.id })).map { it.first }
    }
    fun categories(hit: MessageSearchResult): Set<InlineCategory> = buildSet {
        add(InlineCategory.CHATS)
        when {
            hit.mediaType == "voice" -> add(InlineCategory.VOICE)
            hit.mediaType == "image" || hit.mimeType?.startsWith("image/", true) == true || hit.mimeType?.startsWith("video/", true) == true -> add(InlineCategory.MEDIA)
            hit.mediaType != null -> add(InlineCategory.FILES)
        }
        if (Regex("(?i)(https?://|www\\.)").containsMatchIn(hit.text)) add(InlineCategory.LINKS)
    }
    fun sections(snapshot: InlineSnapshot, directoryAllowed: Boolean = canSearchDirectory(snapshot.query)): List<String> = buildList {
        if (snapshot.query.isEmpty()) return@buildList
        if (snapshot.people.isNotEmpty()) add("chats")
        if (directoryAllowed) add("global")
        if (snapshot.messages.isNotEmpty()) add("messages")
    }
}

/** One cancellable debounce and one atomic local publication. Runs no work for empty input. */
internal class InlineSearchModel(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val load: suspend (String) -> InlineSnapshot,
) {
    private val mutable = MutableStateFlow(InlineSnapshot())
    val snapshot = mutable.asStateFlow()
    val loading = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    private var job: Job? = null
    private var ticket = 0L
    fun search(text: String) {
        job?.cancel()
        val request = ++ticket
        val term = InlineSearchPolicy.query(text)
        failed.value = false
        loading.value = term.isNotEmpty()
        if (term.isEmpty()) { mutable.value = InlineSnapshot(); return }
        job = scope.launch {
            delay(InlineSearchPolicy.DEBOUNCE_MS)
            try {
                val result = withContext(worker) { load(term) }
                if (ticket == request) { mutable.value = result.copy(query = term, messages = result.messages.take(InlineSearchPolicy.MESSAGE_LIMIT)); failed.value = result.localFailed }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == request) failed.value = true }
            finally { if (ticket == request) loading.value = false }
        }
    }
    fun cancel() { ++ticket; job?.cancel(); loading.value = false }
}

/** Capture stable row identity as well as the pixel offset before hiding the mounted list. */
internal class SearchScrollMemory {
    private var anchor: Triple<String?, Int, Int>? = null
    fun capture(key: String?, index: Int, offset: Int) { if (anchor == null) anchor = Triple(key, index, offset) }
    fun restore(keys: List<String>): Pair<Int, Int>? = anchor?.let { (key, index, offset) ->
        (keys.indexOf(key).takeIf { it >= 0 } ?: index.coerceIn(0, (keys.size - 1).coerceAtLeast(0))) to offset
    }.also { anchor = null }
}
