package com.messenger.app.ui

/** Root destinations in canonical COCO order (Phase 8 §Cross-Platform). */
enum class RootDestination { CHATS, CALLS, ROOMS, TRANSFERS, SEARCH, SETTINGS }

/** Pure root-navigation policy (Phase 8 §50/§51): which destinations are
 *  visible and what to do when the selected destination disappears. No UI
 *  types, directly unit-testable, mirrored by iOS's `RootNavigation`.
 *
 *  IMPORTANT: [showCalls] only ever affects ROOT TAB VISIBILITY + selection
 *  fallback. It must never gate call capability (CallController / CallKit /
 *  WebRTC / signaling / incoming-call presentation). */
object RootNavigation {

    const val DEFAULT_SHOW_CALLS = true

    fun visibleDestinations(showCalls: Boolean, showSearch: Boolean = true): List<RootDestination> =
        RootDestination.entries.filter { (it != RootDestination.CALLS || showCalls) && (it != RootDestination.SEARCH || showSearch) }

    fun fallbackSelected(selected: RootDestination, showCalls: Boolean, showSearch: Boolean = true): RootDestination =
        if (selected !in visibleDestinations(showCalls, showSearch)) RootDestination.CHATS else selected
}
