package com.messenger.app.data.call

/** Identity-aware holder for a single attached renderer (pure JVM, no Android
 *  imports so it is unit-testable). The whole point is the ownership race fix:
 *  when a stale `DisposableEffect` disposes an OLD renderer, it must never
 *  clear a NEWER renderer that was attached in the meantime.
 *
 *  [attach] replaces the current renderer (returning whether it changed); it
 *  is the caller's job to perform the EGL init / track-sink side effects only
 *  when `true`.
 *
 *  [detach] clears the slot ONLY when [renderer] is `===` the currently held
 *  one, returning whether anything was actually detached. [clear] is the
 *  unconditional teardown used at call end. */
class RendererSlot<T : Any> {
    private var current: T? = null

    val attached: T? get() = current

    /** Attaches [renderer], replacing any previously-attached renderer (the
     *  caller is responsible for side-effect cleanup of the old one). Returns
     *  `true` when the slot actually changed (a new renderer took the slot) -
     *  the caller performs init/sink side effects on `true` only. */
    fun attach(renderer: T): Boolean {
        if (current === renderer) return false // already attached - no-op
        current = renderer
        return true
    }

    /** Detaches [renderer] only if it is the currently-held one. Returns
     *  `true` when the slot was actually cleared - the caller performs
     *  remove-sink/release side effects on `true` only. An OLD renderer's
     *  disposal returns `false` and leaves a NEWER renderer untouched. */
    fun detach(renderer: T?): Boolean {
        if (renderer == null || current !== renderer) return false
        current = null
        return true
    }

    /** Unconditional clear used at call teardown, regardless of identity. */
    fun clear() {
        current = null
    }
}
