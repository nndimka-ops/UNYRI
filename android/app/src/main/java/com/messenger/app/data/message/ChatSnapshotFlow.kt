package com.messenger.app.data.message

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Room invalidates a table even when another chat changed. Compare before expensive
 * decoding/grouping, not only after mapping. State belongs to each collector. */
internal fun <M, R, O> projectChatSnapshots(
    messages: Flow<List<M>>,
    reactions: Flow<List<R>>,
    project: suspend (List<M>, List<R>) -> O,
): Flow<O> = combine(messages.distinctUntilChanged(), reactions.distinctUntilChanged(), project)
    .distinctUntilChanged()
