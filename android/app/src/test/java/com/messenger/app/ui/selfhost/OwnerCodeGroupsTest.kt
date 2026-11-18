package com.messenger.app.ui.selfhost

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerCodeGroupsTest {
    @Test fun inputKeepsPlainUppercaseCharacters() {
        assertEquals("ABCDEFGHJKLMNPQR", ownerCodeInput("abcd-efgh jklm\nnpqr"))
        assertEquals("ABCDEFGHJKLMNPQR", ownerCodeInput("ABCDEFGHJKLMNPQRSTUV"))
    }

    @Test fun groupsAreDisplayOnlyAndTheCursorStaysAfterTheTypedCharacter() {
        val shown = OwnerCodeGroups.filter(AnnotatedString("ABCDEFGHJKLMNPQR"))
        assertEquals("ABCD-EFGH-JKLM-NPQR", shown.text.text)
        val map = shown.offsetMapping
        assertEquals(0, map.originalToTransformed(0))
        assertEquals(4, map.originalToTransformed(4))
        assertEquals(6, map.originalToTransformed(5))
        assertEquals(9, map.originalToTransformed(8))
        assertEquals(19, map.originalToTransformed(16))
        assertEquals(4, map.transformedToOriginal(4))
        assertEquals(4, map.transformedToOriginal(5))
        assertEquals(5, map.transformedToOriginal(6))
        assertEquals(16, map.transformedToOriginal(19))
        // While typing: after the fifth character the caret sits after it, not before a dash.
        val partial = OwnerCodeGroups.filter(AnnotatedString("ABCDE"))
        assertEquals("ABCD-E", partial.text.text)
        assertEquals(6, partial.offsetMapping.originalToTransformed(5))
    }
}
