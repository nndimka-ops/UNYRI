package com.messenger.app.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryBuilderTest {

    @Test
    fun `plain terms become prefix queries`() {
        assertEquals("hello* world*", SearchQueryBuilder.build("hello world"))
    }

    @Test
    fun `case is preserved but match is case-insensitive at tokenizer level`() {
        assertEquals("Hello*", SearchQueryBuilder.build("Hello"))
    }

    @Test
    fun `cyrillic terms survive`() {
        assertEquals("Привет*", SearchQueryBuilder.build("Привет"))
    }

    @Test
    fun `prefix is enabled by asterisk`() {
        assertEquals("hel*", SearchQueryBuilder.build("hel"))
    }

    @Test
    fun `multiple terms are joined with space for implicit AND`() {
        assertEquals("alpha* beta*", SearchQueryBuilder.build("alpha beta"))
    }

    @Test
    fun `quotes are stripped safely`() {
        assertEquals("hello*", SearchQueryBuilder.build("\"hello\""))
    }

    @Test
    fun `non-alphanumeric chars are separators like FTS4 tokenizes`() {
        assertEquals("hello* world* x*", SearchQueryBuilder.build("hello-world (x):*"))
    }

    @Test
    fun `underscored markers split into separate prefix terms`() {
        // Regression (M.1 live): stripping underscores used to concatenate
        // "M_SEARCH_ALPHA_92831" into "MSEARCHALPHA92831*", which FTS4 could
        // never match because it indexed "m", "search", "alpha", "92831".
        assertEquals("M* SEARCH* ALPHA* 92831*", SearchQueryBuilder.build("M_SEARCH_ALPHA_92831"))
    }

    @Test
    fun `highlight terms mirror FTS4 tokenization`() {
        assertEquals(listOf("m", "search", "alpha", "92831"), SearchQueryBuilder.highlightTerms("M_SEARCH_ALPHA_92831"))
    }

    @Test
    fun `empty and whitespace only yield null`() {
        assertNull(SearchQueryBuilder.build(""))
        assertNull(SearchQueryBuilder.build("   "))
    }

    @Test
    fun `symbols only yield null`() {
        assertNull(SearchQueryBuilder.build("*** --- ::: ( )"))
    }

    @Test
    fun `emoji only yield null and never crash`() {
        assertNull(SearchQueryBuilder.build("👍 😀"))
    }

    @Test
    fun `very long query is capped`() {
        val long = "word".repeat(5000)
        val built = SearchQueryBuilder.build(long)!!
        assertTrue(built.length <= 500)
    }

    @Test
    fun `highlight terms mirror sanitized lowercased tokens`() {
        assertEquals(listOf("hello", "world"), SearchQueryBuilder.highlightTerms("Hello WORLD"))
    }
    @Test fun `preview includes a match deep inside a long message`() {
        val preview = SearchQueryBuilder.snippet("before ".repeat(100) + "needle" + " after".repeat(100), "needle")
        org.junit.Assert.assertTrue(preview.contains("needle"))
        org.junit.Assert.assertTrue(preview.startsWith("…") && preview.endsWith("…"))
        org.junit.Assert.assertTrue(preview.codePointCount(0, preview.length) <= 162)
    }
    @Test fun `preview respects unicode and empty messages`() {
        org.junit.Assert.assertEquals("", SearchQueryBuilder.snippet("", "x"))
        val preview = SearchQueryBuilder.snippet("😀".repeat(80) + "Привет" + "😀".repeat(100), "привет")
        org.junit.Assert.assertTrue(preview.contains("Привет"))
        org.junit.Assert.assertTrue(preview.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == preview)
    }

}
