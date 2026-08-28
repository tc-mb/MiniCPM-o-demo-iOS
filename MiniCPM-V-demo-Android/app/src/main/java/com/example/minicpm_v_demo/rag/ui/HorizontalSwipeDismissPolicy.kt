package com.example.minicpm_v_demo.rag.ui

import kotlin.math.abs

object HorizontalSwipeDismissPolicy {
    private const val MIN_HORIZONTAL_DP = 72f
    private const val MAX_VERTICAL_DP = 48f

    fun shouldDismiss(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        density: Float,
    ): Boolean {
        require(density > 0f)
        val horizontal = startX - endX
        val vertical = abs(endY - startY)
        return horizontal >= MIN_HORIZONTAL_DP * density && vertical <= MAX_VERTICAL_DP * density
    }
}
