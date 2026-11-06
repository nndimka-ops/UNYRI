package com.messenger.app.domain.repository

import com.messenger.app.domain.model.MessageSearchResult

/** On-device message search (M.1): never talks to the backend. */
interface MessageSearchRepository {
    suspend fun globalSearch(query: String, limit: Int = 100): List<MessageSearchResult>

    suspend fun chatSearch(chatId: String, query: String, limit: Int = 100): List<MessageSearchResult>
}
