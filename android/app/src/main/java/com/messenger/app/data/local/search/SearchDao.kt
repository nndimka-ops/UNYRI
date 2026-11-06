package com.messenger.app.data.local.search

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Queries the local FTS4 index only - NEVER the backend (M.1: search is
 * fully on-device; the backend never sees a query, token, or plaintext).
 * `messages_fts` is a hand-created FTS4 virtual table (see MIGRATION_7_8),
 * so it is queried via `@RawQuery` (Room can't schema-validate a virtual
 * table it doesn't model as an entity). The repository passes an already-
 * sanitized MATCH expression from SearchQueryBuilder; a malformed query is
 * additionally caught by the repository, so bad input can never crash the UI.
 */
@Dao
interface SearchDao {

    @RawQuery
    suspend fun globalSearch(query: SupportSQLiteQuery): List<SearchResultRow>

    @RawQuery
    suspend fun chatSearch(query: SupportSQLiteQuery): List<SearchResultRow>
}
