package com.example.minicpm_v_demo

import android.graphics.Bitmap

sealed class ChatMessage {
    abstract val id: Long

    data class UserMessage(
        override val id: Long,
        val text: String,
        val imageBitmap: Bitmap? = null,
        val imageInfo: String? = null,
        val isPrefilling: Boolean = false,
        // True when [imageBitmap] is a video's first frame and the
        // cell should overlay a play icon to communicate "this was a
        // video, the model saw N sampled frames".  Mirrors iOS
        // MBImageTableViewCell's video-playback overlay.
        val isVideo: Boolean = false
    ) : ChatMessage()

    data class AiMessage(
        override val id: Long,
        val text: String,
        val isGenerating: Boolean = false
    ) : ChatMessage()

    data class WelcomeCard(
        override val id: Long = 0L,
        val isTextOnly: Boolean = false
    ) : ChatMessage()
}
