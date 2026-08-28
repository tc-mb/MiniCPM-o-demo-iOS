package com.example.minicpm_v_demo

object ModelDownloadPromptPolicy {
    fun shouldPrompt(
        ggufMissing: Boolean,
        mmprojMissing: Boolean,
        downloadRunning: Boolean
    ): Boolean =
        (ggufMissing || mmprojMissing) && !downloadRunning
}
