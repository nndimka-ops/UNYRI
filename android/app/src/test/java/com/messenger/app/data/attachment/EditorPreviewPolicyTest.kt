package com.messenger.app.data.attachment
import kotlin.test.*
class EditorPreviewPolicyTest {
    @Test fun editorExportKeeps2560PixelsIndependentlyOfTheChatCap() {
        val editor = imagePreviewPlan(6000, 4000, ImagePreviewPurpose.EDITOR, 768)
        val chat = imagePreviewPlan(6000, 4000, ImagePreviewPurpose.CHAT, 2560)
        assertEquals(2560, editor.outputWidth); assertEquals(1706, editor.outputHeight)
        assertEquals(768, chat.outputWidth); assertEquals(512, chat.outputHeight)
        assertEquals(EDITOR_PREVIEW_EDGE, editor.edge)
    }
    @Test fun smallImagesAreNotUpscaledAndDecodedIntermediatesRemainBounded() {
        val small = imagePreviewPlan(400,300,ImagePreviewPurpose.EDITOR)
        assertEquals(400, small.outputWidth); assertEquals(300, small.outputHeight); assertEquals(1, small.sampleSize)
        for ((w,h) in listOf(12000 to 8000, 12000 to 300, 300 to 12000)) {
            val plan = imagePreviewPlan(w,h,ImagePreviewPurpose.EDITOR)
            assertTrue(maxOf(w,h) / plan.sampleSize < 2 * EDITOR_PREVIEW_EDGE)
            assertTrue(maxOf(plan.outputWidth, plan.outputHeight) <= EDITOR_PREVIEW_EDGE)
        }
        assertFailsWith<IllegalArgumentException> { imagePreviewPlan(0,300,ImagePreviewPurpose.EDITOR) }
    }
}
