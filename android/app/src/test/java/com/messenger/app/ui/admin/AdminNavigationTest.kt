package com.messenger.app.ui.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ADMIN UX PACK 1 - role-aware navigation contract.
class AdminNavigationTest {

    @Test
    fun `moderator sees only moderation sections`() {
        val sections = AdminNavigation.sectionsFor(AdminNavigation.MODERATOR)
        assertEquals(
            listOf(AdminSection.Reports, AdminSection.Users, AdminSection.Feedback),
            sections,
        )
    }

    @Test
    fun `moderator never sees owner-only sections`() {
        val visible = AdminNavigation.sectionsFor(AdminNavigation.MODERATOR)
        assertFalse(AdminSection.Storage in visible)
        assertFalse(AdminSection.Audit in visible)
        assertFalse(AdminSection.ServerSettings in visible)
        AdminNavigation.ownerSections.forEach { section ->
            assertFalse(AdminNavigation.canAccess(AdminNavigation.MODERATOR, section))
        }
    }

    @Test
    fun `owner sees owner sections in addition to moderation sections`() {
        val visible = AdminNavigation.sectionsFor(AdminNavigation.OWNER)
        assertTrue(visible.containsAll(AdminNavigation.moderatorSections))
        assertTrue(visible.containsAll(AdminNavigation.ownerSections))
        assertEquals(AdminNavigation.allSections, visible)
    }

    @Test
    fun `a role the server did not grant is not an administrator`() {
        assertFalse(AdminNavigation.isAdministrator(""))
        assertFalse(AdminNavigation.isAdministrator(null))
        assertFalse(AdminNavigation.isAdministrator("admin"))
        assertFalse(AdminNavigation.isOwner("Owner"))
        assertTrue(AdminNavigation.isAdministrator(AdminNavigation.MODERATOR))
        assertTrue(AdminNavigation.isAdministrator(AdminNavigation.OWNER))
    }

    @Test
    fun `an unknown role sees no sections at all`() {
        assertEquals(emptyList(), AdminNavigation.sectionsFor(""))
        assertEquals(emptyList(), AdminNavigation.sectionsFor(null))
        assertEquals(emptyList(), AdminNavigation.sectionsFor("admin"))
        assertEquals(emptyList(), AdminNavigation.sectionsFor("superuser"))
    }

    @Test
    fun `a normal account cannot enter any administration destination`() {
        listOf(null, "", "user", "admin").forEach { role ->
            AdminTarget.entries.forEach { target ->
                assertFalse(
                    AdminNavigation.canOpen(role, target),
                    "role=$role target=$target must not be openable",
                )
            }
            AdminSection.entries.forEach { section ->
                assertFalse(AdminNavigation.canAccess(role, section))
            }
        }
    }

    @Test
    fun `owner-only destinations are not openable for moderators`() {
        val ownerOnly = listOf(AdminTarget.Storage, AdminTarget.Audit, AdminTarget.ServerSettings)
        ownerOnly.forEach { target ->
            assertFalse(AdminNavigation.canOpen(AdminNavigation.MODERATOR, target))
            assertTrue(AdminNavigation.canOpen(AdminNavigation.OWNER, target))
        }
    }

    @Test
    fun `moderation destinations are openable for both roles`() {
        listOf(
            AdminTarget.Home, AdminTarget.Reports, AdminTarget.ReportDetail,
            AdminTarget.Users, AdminTarget.UserDetail, AdminTarget.Feedback,
            AdminTarget.FeedbackDetail, AdminTarget.Security,
        ).forEach { target ->
            assertTrue(AdminNavigation.canOpen(AdminNavigation.MODERATOR, target))
            assertTrue(AdminNavigation.canOpen(AdminNavigation.OWNER, target))
        }
    }

    @Test
    fun `detail routes carry exactly one id argument and list routes do not`() {
        assertEquals("admin_report_detail/{adminId}", AdminRoutes.detailRoute(AdminTarget.ReportDetail))
        assertEquals("admin_user_detail/{adminId}", AdminRoutes.detailRoute(AdminTarget.UserDetail))
        assertEquals("admin_feedback_detail/{adminId}", AdminRoutes.detailRoute(AdminTarget.FeedbackDetail))
        assertEquals("admin_reports", AdminRoutes.route(AdminTarget.Reports))
    }

    @Test
    fun `detail paths keep the id out of the query string`() {
        assertEquals("admin_user_detail/abc", AdminRoutes.detailPath(AdminTarget.UserDetail, "abc"))
        assertEquals("admin_user_detail/a%2Fb", AdminRoutes.detailPath(AdminTarget.UserDetail, "a/b"))
    }

    @Test
    fun `every target has a distinct route so back navigation stays predictable`() {
        val routes = AdminTarget.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
    }
}
