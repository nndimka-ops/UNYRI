package com.messenger.app

import android.app.Activity
import android.content.pm.ActivityInfo
import java.util.WeakHashMap

/** Main-thread, activity-scoped ownership. Recomposition without an ownership change is inert. */
object OrientationLease {
    val Call = Any()
    private data class Lease(val owners: OrientationOwners)
    private val activities = WeakHashMap<Activity, Lease>()
    @Volatile var callFullScreen: Boolean = false
        private set

    fun holds(activity: Activity, owner: Any): Boolean = activities[activity]?.owners?.holds(owner) == true

    fun acquire(activity: Activity, owner: Any, orientation: Int = ActivityInfo.SCREEN_ORIENTATION_USER) {
        val lease = activities.getOrPut(activity) { Lease(OrientationOwners(activity.requestedOrientation)) }
        if (lease.owners.acquire(owner, orientation)) apply(activity, lease.owners.requested)
        updateCall()
    }

    /** True only if this release actually changed the activity's orientation request. */
    fun release(activity: Activity, owner: Any): Boolean {
        val lease = activities[activity] ?: return false
        if (!lease.owners.release(owner)) return false
        if (lease.owners.empty) activities.remove(activity)
        val changed = apply(activity, lease.owners.requested)
        updateCall()
        return changed
    }

    private fun apply(activity: Activity, orientation: Int): Boolean {
        if (activity.requestedOrientation == orientation) return false
        activity.requestedOrientation = orientation
        return true
    }
    private fun updateCall() { callFullScreen = activities.values.any { it.owners.holds(Call) } }
}

/** Latest owner wins; removing it restores the next owner, or the original request. */
internal class OrientationOwners(private val previous: Int) {
    private val owners = linkedMapOf<Any, Int>()
    val requested: Int get() = owners.values.lastOrNull() ?: previous
    val empty: Boolean get() = owners.isEmpty()
    fun holds(owner: Any) = owner in owners
    fun acquire(owner: Any, orientation: Int): Boolean {
        if (owners[owner] == orientation) return false
        owners.remove(owner)
        owners[owner] = orientation
        return true
    }
    fun release(owner: Any): Boolean = owners.remove(owner) != null
}
