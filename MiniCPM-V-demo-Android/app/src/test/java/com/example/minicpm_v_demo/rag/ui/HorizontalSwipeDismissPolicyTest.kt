package com.example.minicpm_v_demo.rag.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizontalSwipeDismissPolicyTest {
    @Test
    fun `a deliberate left swipe dismisses a failure notice`() {
        assertTrue(
            HorizontalSwipeDismissPolicy.shouldDismiss(
                startX = 300f,
                startY = 100f,
                endX = 180f,
                endY = 112f,
                density = 1f,
            ),
        )
    }

    @Test
    fun `right swipes short drags and vertical scrolls do not dismiss`() {
        assertFalse(HorizontalSwipeDismissPolicy.shouldDismiss(180f, 100f, 300f, 100f, 1f))
        assertFalse(HorizontalSwipeDismissPolicy.shouldDismiss(300f, 100f, 260f, 100f, 1f))
        assertFalse(HorizontalSwipeDismissPolicy.shouldDismiss(300f, 100f, 180f, 190f, 1f))
    }
}
