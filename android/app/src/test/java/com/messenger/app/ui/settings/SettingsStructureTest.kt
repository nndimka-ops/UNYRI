package com.messenger.app.ui.settings

import com.messenger.app.R
import com.messenger.app.ui.profile.settingsHomeGroups
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The approved home order is shared by the two clients, independent of translated labels. */
class SettingsStructureTest {
    private val main by lazy { source("profile/ProfileScreen.kt") }

    @Test fun `main and appearance groups follow the approved settings order`() {
        assertEquals(
            listOf(
                R.string.settings_group_main to listOf(
                    R.string.settings_account,
                    R.string.notification_settings_title,
                    R.string.profile_privacy_security,
                    R.string.settings_chats,
                    R.string.call_settings_title,
                    R.string.settings_data_storage,
                ),
                R.string.settings_group_appearance to listOf(
                    R.string.profile_appearance,
                    R.string.profile_language,
                ),
            ),
            settingsHomeGroups,
        )
    }

    @Test fun `home keeps accounts first and destructive actions after help before version`() {
        val loaded = main.substringAfter("is ProfileUiState.Loaded -> {").substringBefore("alphaPage?.let")
        assertOrdered(loaded,
            "AccountCard(", "SettingsHomeAccounts(", "SettingsHomeCategories",
            "R.string.settings_group_server", "SettingsHomeServer(", "SettingsHomeHelp",
            "R.string.settings_home_sign_out", "R.string.account_delete", "AboutRow(",
        )
        val home = loaded.substringBefore("} else when (settingsPage)")
        assertFalse(home.contains("SettingsHomeHistory("), "History and transfer belongs in Chats")
        assertFalse(home.contains("showOwnerClaim = true"), "Owner setup belongs on the Server page")
    }

    @Test fun `privacy follows devices visibility messages and account sections`() {
        val privacy = main.substringAfter("R.string.profile_privacy_security -> Column(")
            .substringBefore("R.string.settings_data_storage -> SettingsHomeGroup")
        assertOrdered(privacy,
            "R.string.privacy_devices_blocks", "R.string.profile_devices", "R.string.blocked_users_title", "AppLockSettings()",
            "R.string.settings_privacy_visibility", "PrivacySettingsContent(showReadReceipts = false)",
            "R.string.privacy_messages_section", "PrivacySettingsContent(showVisibility = false)",
            "R.string.live_draft_setting", "R.string.chat_activity_setting", "R.string.call_p2p_title",
            "R.string.settings_account", "R.string.settings_recovery_key", "R.string.auto_delete_title",
        )
        assertFalse(privacy.contains("R.string.settings_diagnostics"), "Diagnostics belongs in Help and application")
    }

    @Test fun `history language and legal documents remain in their approved destinations`() {
        val chats = main.substringAfter("R.string.settings_chats -> Column(")
            .substringBefore("R.string.profile_appearance -> SettingsHomeGroup")
        assertTrue(chats.contains("R.string.settings_home_history"))
        val appearance = main.substringAfter("R.string.profile_appearance -> SettingsHomeGroup")
            .substringBefore("R.string.profile_privacy_security -> Column(")
        assertFalse(appearance.contains("R.string.profile_language"))
        val data = main.substringAfter("R.string.settings_data_storage -> SettingsHomeGroup")
            .substringBefore("Spacer(modifier = Modifier.height(CocoSpacing.xxl))")
        assertFalse(data.contains("history"), "History moved from Data to Chats")
        assertFalse(data.contains("call_settings_title"), "Calls has its own home entry")
        val about = source("settings/AlphaSettings.kt").substringAfter("fun AlphaAboutContent(")
            .substringBefore("fun AlphaTermsContent(")
        assertOrdered(about, "R.string.settings_about_project", "listOf(\"terms\", \"privacy\", \"support\")")
        assertFalse(about.contains("R.string.alpha_test"))
        assertFalse(about.contains("R.string.settings_diagnostics"))
        assertFalse(about.contains("R.string.performance_online"))
    }

    @Test fun `approved group names and renamed rows are translated in English Russian and Ukrainian`() {
        val expected = mapOf(
            "settings_group_accounts" to listOf("Accounts", "Аккаунты", "Облікові записи"),
            "settings_account_current" to listOf("Current account", "Текущий аккаунт", "Поточний обліковий запис"),
            "settings_group_main" to listOf("Main", "Основное", "Основне"),
            "settings_group_appearance" to listOf("Appearance", "Оформление", "Оформлення"),
            "settings_group_server" to listOf("Server", "Сервер", "Сервер"),
            "settings_group_help" to listOf("Help", "Помощь", "Допомога"),
            "settings_privacy_visibility" to listOf("Visibility", "Видимость", "Видимість"),
            "settings_privacy_protection" to listOf("Protection", "Защита", "Захист"),
            "settings_privacy_diagnostics" to listOf("Diagnostics", "Диагностика", "Діагностика"),
            "settings_recovery_key" to listOf("Recovery key", "Ключ восстановления", "Ключ відновлення"),
            "settings_about_project" to listOf("About the project", "О проекте", "Про проєкт"),
            "settings_performance_diagnostics" to listOf("Performance diagnostics", "Диагностика производительности", "Діагностика продуктивності"),
            "settings_data_storage" to listOf("Connection and storage", "Связь и память", "Зв’язок і пам’ять"),
            "settings_about" to listOf("Help and application", "Помощь и приложение", "Допомога та застосунок"),
            "profile_privacy_security" to listOf("Privacy and security", "Приватность и защита", "Приватність і захист"),
        )
        val resources = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        for ((index, locale) in listOf("values", "values-ru", "values-uk").withIndex()) {
            val entries = File(resources, locale).listFiles().orEmpty().filter { it.extension == "xml" }
                .flatMap { file -> strings(file) }.groupBy({ it.first }, { it.second })
            for ((key, translations) in expected) {
                assertEquals(listOf(translations[index]), entries[key], "$locale/$key must have exactly one translated definition")
            }
        }
    }

    private fun source(path: String): String = listOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }.resolve("com/messenger/app/ui/$path").readText()

    private fun assertOrdered(source: String, vararg markers: String) {
        var previous = -1
        for (marker in markers) {
            val index = source.indexOf(marker, previous + 1)
            assertTrue(index > previous, "Missing or out-of-order settings entry: $marker")
            previous = index
        }
    }

    private fun strings(file: File): List<Pair<String, String>> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }
}
