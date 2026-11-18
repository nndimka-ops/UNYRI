package com.messenger.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CocoLanguagesTest {

    @Test
    fun `all lists 21 entries with system default first`() {
        assertEquals(21, CocoLanguages.all.size)
        assertNull(CocoLanguages.all.first().tag)
    }

    @Test
    fun `localeFor maps language and region tags`() {
        assertEquals("en", CocoLanguages.localeFor("en")?.language)
        assertEquals("zh", CocoLanguages.localeFor("zh-CN")?.language)
        assertEquals("CN", CocoLanguages.localeFor("zh-CN")?.country)
        assertEquals("ja", CocoLanguages.localeFor("ja")?.language)
    }

    @Test
    fun `localeFor null returns null`() {
        assertNull(CocoLanguages.localeFor(null))
    }
}
