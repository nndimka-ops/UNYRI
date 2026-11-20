package org.unyri.admin

import java.util.Properties
import java.io.InputStreamReader
import org.junit.Test
import kotlin.test.*

class LocalizationTest {
    private val languages = listOf("ru", "en", "uk")

    private fun resources(language: String): Properties {
        val values = object : Properties() {
            override fun put(key: Any, value: Any): Any? {
                assertFalse(containsKey(key), "$language: duplicate resource key '$key'")
                return super.put(key, value)
            }
        }
        val stream = assertNotNull(javaClass.getResourceAsStream("/strings_$language.properties"), "Missing locale: $language")
        InputStreamReader(stream, Charsets.UTF_8).use(values::load)
        return values
    }

    @Test fun allLocalesHaveSameKeysAndNonemptyValues() {
        val locales = languages.associateWith(::resources)
        val expected = locales.getValue("en").stringPropertyNames()
        assertTrue(expected.isNotEmpty())
        for ((language, locale) in locales) {
            val actual = locale.stringPropertyNames()
            assertEquals(expected, actual,
                "$language: missing keys ${(expected - actual).sorted()}, extra keys ${(actual - expected).sorted()}")
            for (key in actual) assertTrue(locale.getProperty(key).isNotBlank(), "$language: blank resource '$key'")
        }
        assertEquals("Администрирование",Strings("ru")["admin"])
    }

    @Test fun translatedTemplatesKeepEveryPlaceholder() {
        val locales = languages.associateWith(::resources)
        val english = locales.getValue("en")
        for ((language, locale) in locales) {
            for (key in english.stringPropertyNames()) {
                val translated = assertNotNull(locale.getProperty(key), "$language: missing resource '$key'")
                assertEquals(Regex("%@").findAll(english.getProperty(key)).count(),
                    Regex("%@").findAll(translated).count(), "$language: placeholder count changed in '$key'")
            }
        }
    }

    @Test fun mainWizardAndMyServerTextsUsePlainWords() {
        val jargon = listOf("WSL", "Compose", "SH-5", "docker-compose", "backend", "preflight", "exit code", ".yml", "scripts/")
        for (language in languages) {
            val locale = resources(language)
            val mainKeys = locale.stringPropertyNames().filter { it.startsWith("sh.") && !it.startsWith("sh.technical.") }
            assertTrue(mainKeys.isNotEmpty(), "$language: no wizard resources")
            for (key in mainKeys) {
                val value = locale.getProperty(key)
                for (word in jargon) assertFalse(value.contains(word, ignoreCase = true),
                    "$language: '$key' contains specialist wording '$word'; put technical details in a folded sh.technical.* area or the log")
            }
        }
    }

    @Test fun serverCodesHaveLocalizedLabels() {
        val actions = listOf("enroll", "unlock", "rotate-signing-key", "operator:authentication-policy", "notice:send",
            "diagnostics:export", "profile:moderate", "read_only:enable", "read_only:disable", "device_logout", "note:add",
            "device_suspend", "device_unsuspend", "operations:controls", "announcement:publish", "announcement:disable",
            "operator:role", "operator:reset_2fa", "credential:recovery_code", "suspend", "unsuspend",
            "suspend_with_installations", "unsuspend_with_installations", "setup:claim-invalid", "setup:claim-owner",
            "registration:mode", "invite:create", "invite:revoke", "invite:register-refused", "invite:register", "account_delete",
            "console:handoff-issued", "console:session-open", "console:session-close", "local:clear-message-call-history",
            "security:factor-lockout", "server:policy", "storage:cleanup_scheduled")
        for (language in languages) {
            val t = Strings(language)
            assertTrue(t["messageQueue"].isNotBlank())
            for (action in actions) {
                val label = t.auditAction(action)
                assertTrue(label.isNotBlank(), "$language: no label for '$action'")
                assertNotEquals(action, label, "$language: raw audit code '$action'")
                assertNotEquals(t["audit.other"], label, "$language: known audit code '$action' lost its label")
            }
            assertEquals("${t["audit.report"]} · ${t["status.in_progress"]}", t.auditAction("report:in_progress"))
            assertEquals("${t["audit.feedback"]} · ${t["status.resolved"]}", t.auditAction("feedback:resolved"))
            assertEquals(t["audit.other"], t.auditAction("future:action"))
            for (reason in listOf("spam", "harassment", "pornography", "scam", "self_harm", "copyright", "other"))
                assertEquals(t["reportReason.$reason"], t.reportReason(reason))
            assertEquals(t["unknown"], t.reportReason("future_reason"))
            assertEquals("Android", t.platform("android"))
            assertEquals(t["platform.desktop"], t.platform("desktop"))
            assertEquals(t["platform.web"], t.platform("web"))
            assertEquals(t["unknown"], t.platform("future_platform"))
        }
    }
    @Test fun offlineDemoSearchFilterAndPagingAreIsolated() {
        @Suppress("UNCHECKED_CAST")
        val first=Demo.load(Section.USERS,null,"","") as org.unyri.admin.core.Page<org.unyri.admin.core.UserRow>
        assertEquals(25,first.items.size); assertTrue(first.has_more)
        @Suppress("UNCHECKED_CAST")
        val second=Demo.load(Section.USERS,first.next_cursor,"","") as org.unyri.admin.core.Page<org.unyri.admin.core.UserRow>
        assertTrue(first.items.map { it.user_id }.intersect(second.items.map { it.user_id }.toSet()).isEmpty())
        @Suppress("UNCHECKED_CAST")
        val filtered=Demo.load(Section.USERS,null,"demo009","suspended") as org.unyri.admin.core.Page<org.unyri.admin.core.UserRow>
        assertEquals(1,filtered.items.size)
        assertEquals("https://home.example.test",Demo.session.origin)
    }
    @Test fun offlineDemoUsesTheSelectedLanguage() {
        for (language in languages) {
            val t = Strings(language)
            val dashboard = Demo.load(Section.DASHBOARD, null, "", "", language) as Dashboard
            assertEquals(t["demo.serverName"], dashboard.policy.name)
            assertEquals(t["demo.operator"], dashboard.policy.operator)
            @Suppress("UNCHECKED_CAST")
            val users = Demo.load(Section.USERS, null, "demo009", "suspended", language) as org.unyri.admin.core.Page<org.unyri.admin.core.UserRow>
            val user = users.items.single()
            assertEquals(t["demo.userName"].replace("%@", "9"), user.description)
            assertEquals(t["demo.profile"], Demo.user(user.user_id, language).profile.bio)
            @Suppress("UNCHECKED_CAST")
            val feedback = Demo.load(Section.FEEDBACK, null, "", "", language) as org.unyri.admin.core.Page<org.unyri.admin.core.QueueRow>
            assertEquals(t["demo.report"], feedback.items.single().description)
        }
    }
    @Test fun selfHostingDynamicKeysExistInEveryLocale() {
        for (lang in listOf("ru", "en", "uk")) {
            val t = Strings(lang)
            for (step in org.unyri.admin.core.HostStep.entries) {
                val key = step.name.lowercase()
                listOf("step", "explain", "why").forEach { assertTrue(t["sh.$it.$key"].isNotBlank()) }
            }
            for (state in org.unyri.admin.core.FirewallState.entries) assertTrue(t["sh.firewall.${state.name.lowercase()}"].isNotBlank())
            org.unyri.admin.core.HostPhases.allowed.forEach { assertTrue(t["sh.phase.$it"].isNotBlank()) }
            listOf("start", "stop", "update", "backup", "restore", "delete").forEach {
                assertTrue(t["sh.action.$it"].isNotBlank()); assertTrue(t["sh.confirm.$it"].isNotBlank())
            }
        }
    }
    @Test fun doctorRowsUseSharedCatalogKeysAndCompleteSentences() {
        val now = java.time.Instant.parse("2026-11-18T12:00:00Z")
        val fixture = org.unyri.admin.core.DoctorDemo.addresses(now)
        val variants = listOf(null, fixture, fixture.copy(schema = 2), fixture.copy(hosting = "vps"),
            fixture.copy(ingress = "tunnel", provider = "cloudflare"), fixture.copy(publicAddress = null, publicInvalid = true),
            fixture.copy(lan = fixture.lan.copy(state = "unknown")), fixture.copy(lan = fixture.lan.copy(state = "invalid")))
        for (language in listOf("ru", "en", "uk")) {
            val t = Strings(language)
            variants.forEach { a ->
                val p = org.unyri.admin.core.ConnectionDoctorPresentation.make(a, "https://home.example.test", now = now) { it.toString() }
                (p.internet + p.home + p.connection).forEach { row ->
                    assertTrue(t.doctor(row).isNotBlank()); assertFalse(t.doctor(row).contains("%@"))
                }
            }
            assertTrue(t["Server addresses"].isNotBlank()); assertTrue(t["How you are connected"].isNotBlank())
        }
        assertEquals("Указан при установке. Сейчас не проверен.", Strings("ru")["Set during installation. Not checked now."])
        assertEquals("Порт: 443", Strings("ru").doctor(org.unyri.admin.core.ConnectionDoctorPresentation.Row("Port: %@", listOf("443"))))
    }
}
