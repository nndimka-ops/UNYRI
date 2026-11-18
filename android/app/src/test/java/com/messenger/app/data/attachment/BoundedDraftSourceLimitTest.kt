package com.messenger.app.data.attachment

import java.io.ByteArrayOutputStream
import kotlin.test.*

class BoundedDraftSourceLimitTest {
    @Test fun omittedOverridePreservesExistingVideoAndFileLimits() {
        assertEquals(500L,boundedDraftSourceLimit(500,null))
        assertEquals(100L,boundedDraftSourceLimit(100,null))
    }
    @Test fun callerCanOnlyTightenExistingLimitAndCannotDisableIt() {
        assertEquals(100L,boundedDraftSourceLimit(500,100))
        assertEquals(500L,boundedDraftSourceLimit(500,1000))
        assertEquals(100L,boundedDraftSourceLimit(100,500))
        assertFailsWith<IllegalArgumentException> { boundedDraftSourceLimit(500,0) }
        assertFailsWith<IllegalArgumentException> { boundedDraftSourceLimit(500,-1) }
    }
    @Test fun unknownSmallStreamStillCopiesAndProviderGrowthCannotExceedCap() {
        val output=ByteArrayOutputStream()
        copyBoundedFile(ByteArray(42){it.toByte()}.inputStream(),output,boundedDraftSourceLimit(500,100))
        assertEquals(42,output.size())
        val grew=ByteArrayOutputStream()
        assertFailsWith<AttachmentTooLargeException> {
            // Provider metadata may have said a small size; the same stream copy
            // enforces the actual bytes instead of trusting that declaration.
            copyBoundedFile(ByteArray(101).inputStream(),grew,boundedDraftSourceLimit(500,100))
        }
        assertTrue(grew.size()<=100)
    }
}
