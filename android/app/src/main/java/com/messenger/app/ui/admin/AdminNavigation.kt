package com.messenger.app.ui.admin

// ---------------------------------------------------------------------------
// Admin UX Pack 1 - role-aware information architecture.
//
// Pure Kotlin: no Compose, no Android. Everything here is unit-testable and
// decides *which* destinations an administrator may reach. The server stays the
// authority; this only decides what the client offers.
// ---------------------------------------------------------------------------

/** Nested navigation destinations inside the unlocked administration panel. */
enum class AdminTarget(val route: String) {
    Home("admin_home"),
    Reports("admin_reports"),
    ReportDetail("admin_report_detail"),
    Users("admin_users"),
    UserDetail("admin_user_detail"),
    Feedback("admin_feedback"),
    FeedbackDetail("admin_feedback_detail"),
    Storage("admin_storage"),
    Audit("admin_audit"),
    ServerSettings("admin_server_settings"),
    Security("admin_security"),
}

/** Entry points offered on the dashboard. */
enum class AdminSection(val target: AdminTarget) {
    Reports(AdminTarget.Reports),
    Users(AdminTarget.Users),
    Feedback(AdminTarget.Feedback),
    Storage(AdminTarget.Storage),
    Audit(AdminTarget.Audit),
    ServerSettings(AdminTarget.ServerSettings),
}

object AdminNavigation {
    const val OWNER = "owner"
    const val MODERATOR = "moderator"

    /** Sections every administrator may use, whatever their role. */
    val moderatorSections: List<AdminSection> =
        listOf(AdminSection.Reports, AdminSection.Users, AdminSection.Feedback)

    /** Owner-only sections. Hidden entirely for moderators - never shown disabled. */
    val ownerSections: List<AdminSection> =
        listOf(AdminSection.Storage, AdminSection.Audit, AdminSection.ServerSettings)

    /** Sections reachable directly (Security is not a dashboard card). */
    val allSections: List<AdminSection> = moderatorSections + ownerSections

    fun isOwner(role: String?): Boolean = role == OWNER

    fun isModerator(role: String?): Boolean = role == MODERATOR

    /**
     * A role the server actually granted. Anything else - null, an empty string,
     * a role name from a newer server, a revoked role - is not an administrator,
     * and no administration destination may be opened for it.
     */
    fun isAdministrator(role: String?): Boolean = isOwner(role) || isModerator(role)

    fun sectionsFor(role: String?): List<AdminSection> = when {
        isOwner(role) -> allSections
        isModerator(role) -> moderatorSections
        else -> emptyList()
    }

    fun canAccess(role: String?, section: AdminSection): Boolean = section in sectionsFor(role)

    /**
     * Whether a destination may be opened for this role. A non-administrator is
     * rejected outright, and owner-only destinations are rejected for moderators
     * even when reached through a stale deep link.
     */
    fun canOpen(role: String?, target: AdminTarget): Boolean = when {
        !isAdministrator(role) -> false
        target == AdminTarget.Storage ||
            target == AdminTarget.Audit ||
            target == AdminTarget.ServerSettings -> isOwner(role)
        else -> true
    }
}

/** Navigation route helpers. Detail routes carry the row id as the only argument. */
object AdminRoutes {
    const val ARG_ID = "adminId"

    fun route(target: AdminTarget): String = target.route

    fun detailRoute(target: AdminTarget): String = "${target.route}/{$ARG_ID}"

    fun detailPath(target: AdminTarget, id: String): String =
        "${target.route}/${java.net.URLEncoder.encode(id, "UTF-8")}"
}
