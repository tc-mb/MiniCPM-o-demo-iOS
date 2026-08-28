package com.example.minicpm_v_demo.rag.embed

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingSessionReleasePolicyTest {
    @Test
    fun `session is released only after five background minutes and a memory trim`() {
        val backgroundSince = 1_000L

        assertFalse(
            EmbeddingSessionReleasePolicy.shouldRelease(
                backgroundSinceMs = null,
                nowMs = backgroundSince + 10 * 60_000L,
                trimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ),
        )
        assertFalse(
            EmbeddingSessionReleasePolicy.shouldRelease(
                backgroundSinceMs = backgroundSince,
                nowMs = backgroundSince + 5 * 60_000L - 1,
                trimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ),
        )
        assertTrue(
            EmbeddingSessionReleasePolicy.shouldRelease(
                backgroundSinceMs = backgroundSince,
                nowMs = backgroundSince + 5 * 60_000L,
                trimLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ),
        )
        assertFalse(
            EmbeddingSessionReleasePolicy.shouldRelease(
                backgroundSinceMs = backgroundSince,
                nowMs = backgroundSince + 10 * 60_000L,
                trimLevel = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN - 1,
            ),
        )
    }
}
