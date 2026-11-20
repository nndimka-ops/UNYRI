package org.unyri.admin

import java.util.Properties
import java.io.InputStreamReader
import org.junit.Test
import kotlin.test.*

class LocalizationTest {
    @Test fun allLocalesHaveSameKeysAndNonemptyValues() {
        val locales=listOf("ru","en","uk").map { language ->
            Properties().apply { InputStreamReader(LocalizationTest::class.java.getResourceAsStream("/strings_$language.properties")!!,Charsets.UTF_8).use(::load) }
        }
        for (locale in locales) {
            assertEquals(locales.first().keys,locale.keys)
            assertTrue(locale.values.all { it.toString().isNotBlank() })
        }
        assertEquals("Администрирование",Strings("ru")["admin"])
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
