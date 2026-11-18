package com.messenger.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class CocoChromePolicyTest {
    private val normal = CocoChromePolicy(active = true, lowPower = false, increasedContrast = false)

    @Test fun lifecycleAndAccessibilityChangesRetireSamplingAndRestoreIt() {
        val transitions = listOf(
            normal to true,
            normal.copy(active = false) to false,
            normal.copy(lowPower = true) to false,
            normal.copy(increasedContrast = true) to false,
            normal.copy(reduceTransparency = true) to false,
            normal to true,
        )
        for ((policy, expected) in transitions) {
            assertEquals(expected, policy.samplesBackdrop(supported = true))
            assertFalse(policy.samplesBackdrop(supported = false))
        }
    }

    @Test fun customTintSurvivesFallbackAndResumeWithoutDoubleApplyingAlpha() {
        val background = Color(0xFF183040)
        val custom = Color(0x70A638CB)
        val live = resolveComposerSurfaceStyle(custom, background, normal.samplesBackdrop(true))
        val lowPower = resolveComposerSurfaceStyle(custom, background, normal.copy(lowPower = true).samplesBackdrop(true))
        val resumed = resolveComposerSurfaceStyle(custom, background, normal.samplesBackdrop(true))
        assertEquals(custom.compositeOver(background), lowPower.fill)
        assertEquals(lowPower.fill, live.opaqueFill)
        assertEquals(1f, lowPower.fill.alpha, 0f)
        assertFalse(lowPower.backdropEnabled)
        assertEquals(live, resumed)
    }

    @Test fun defaultFallbackRemainsIndependentOfMaterialSampling() {
        for (background in listOf(Color.Black, Color(0xFF263E65), Color(0xFF98345A))) {
            val live = resolveComposerSurfaceStyle(Color(0xCC000000), background, true)
            val solid = resolveComposerSurfaceStyle(Color(0xCC000000), background, false)
            assertEquals(live.opaqueFill, solid.fill)
            if (background == Color.Black) assertNotEquals(live.fill.compositeOver(background), solid.fill)
            assertEquals(1f, solid.fill.alpha, 0f)
            assertNotEquals(Color.White, solid.fill)
            assertNull(solid.border)
        }
    }

    @Test fun defaultMaterialPreservesTextContrastAboveBrightAndSaturatedMedia() {
        val material = resolveComposerSurfaceStyle(Color(0xCC000000), Color.Black, true)
        val backdrops = listOf(Color.White, Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta, Color.Yellow) +
            (0..255 step 17).map { Color(it, it, it) }
        for (backdrop in backdrops) {
            // Worst-case top light plus a white grain texel, not just the base material.
            val panel = Color.White.copy(alpha = .02f).compositeOver(
                Color.White.copy(alpha = .035f).compositeOver(material.fill.compositeOver(backdrop)))
            for (ink in listOf(Color(0xFFF1F1F1), Color(0xFFB0B0B0), Color(0xFF77E6C6))) {
                val contrast = (ink.luminance() + .05f) / (panel.luminance() + .05f)
                assertTrue("Default ink $ink loses contrast above $backdrop: $contrast", contrast >= 4.5f)
            }
        }
        assertNotEquals("Material must retain the backdrop rather than replace it",
            material.fill.compositeOver(Color.Black), material.fill.compositeOver(Color.White))
    }

    @Test fun popupFallbackIsOpaqueAndPreservesSavedTintAcrossEverySamplingGate() {
        for (saved in listOf(Color(0xCC000000), Color(0x70A638CB), Color.Transparent)) {
            val material = resolveComposerSurfaceStyle(saved, Color(0xFF183040), true)
            val fallback = resolveComposerSurfaceStyle(saved, Color(0xFF183040), false)
            assertEquals(fallback.fill, material.opaqueFill)
            for (backdrop in listOf(Color.Black, Color.White, Color.Yellow)) {
                assertEquals(material.opaqueFill, material.opaqueFill.compositeOver(backdrop))
            }
        }
    }
}
