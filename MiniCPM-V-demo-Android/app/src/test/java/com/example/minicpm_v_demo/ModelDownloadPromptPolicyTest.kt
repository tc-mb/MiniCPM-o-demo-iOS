package com.example.minicpm_v_demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadPromptPolicyTest {

    @Test
    fun suppressesPromptWhileDownloadIsRunning() {
        assertFalse(
            ModelDownloadPromptPolicy.shouldPrompt(
                ggufMissing = true,
                mmprojMissing = true,
                downloadRunning = true
            )
        )
    }

    @Test
    fun promptsWhenFilesAreMissingAndNoDownloadIsRunning() {
        assertTrue(
            ModelDownloadPromptPolicy.shouldPrompt(
                ggufMissing = true,
                mmprojMissing = false,
                downloadRunning = false
            )
        )
    }

    @Test
    fun doesNotPromptWhenAllRequiredFilesExist() {
        assertFalse(
            ModelDownloadPromptPolicy.shouldPrompt(
                ggufMissing = false,
                mmprojMissing = false,
                downloadRunning = false
            )
        )
    }
}
