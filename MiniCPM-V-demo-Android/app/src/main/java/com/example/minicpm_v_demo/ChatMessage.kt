package com.example.minicpm_v_demo

import android.graphics.Bitmap

data class CitationRef(
    val messageId: Long,
    val sourceId: String,
    val chunkId: Long,
    val documentId: String,
    val documentNameSnapshot: String,
    val locator: String,
    val quotedText: String,
    val retrievalScore: Double,
    val retrievalVersion: Int,
) {
    init {
        require(messageId >= 0 && chunkId > 0)
        require(sourceId.isNotBlank() && documentId.isNotBlank() && documentNameSnapshot.isNotBlank())
        require(retrievalScore.isFinite() && retrievalVersion >= 0)
    }
}

sealed class ChatMessage {
    abstract val id: Long

    data class UserMessage(
        override val id: Long,
        val text: String,
        val imageBitmap: Bitmap? = null,
        val imageInfo: String? = null,
        val originalImageToken: String? = null,
        val previewImageToken: String? = null,
        val isPrefilling: Boolean = false,
        val requiresPrivacyConfirmation: Boolean = false,
        val includeInModelContext: Boolean = true,
        // True when [imageBitmap] is a video's first frame and the
        // cell should overlay a play icon to communicate "this was a
        // video, the model saw N sampled frames".  Mirrors iOS
        // MBImageTableViewCell's video-playback overlay.
        val isVideo: Boolean = false
    ) : ChatMessage()

    data class AiMessage(
        override val id: Long,
        val text: String,
        val isGenerating: Boolean = false,
        val includeInModelContext: Boolean = true,
        val citations: List<CitationRef> = emptyList(),
        val ragRunId: String? = null,
        val answerEdited: Boolean = false,
        val ragGenerationStage: RagGenerationStage? = null,
    ) : ChatMessage()

    data class WelcomeCard(
        override val id: Long = 0L,
        val isTextOnly: Boolean = false,
        val hasVisualContext: Boolean = false
    ) : ChatMessage()
}

enum class RagGenerationStage {
    RETRIEVING,
    ORGANIZING,
    GENERATING,
}

fun ChatMessage.UserMessage.confirmedForSubmission(
    attachment: PendingImageAttachment?,
    persistedPreviewToken: String? = null
): ChatMessage.UserMessage = if (attachment == null) {
    copy(requiresPrivacyConfirmation = false)
} else {
    copy(
        imageBitmap = attachment.thumbnail,
        imageInfo = attachment.imageInfo,
        originalImageToken = attachment.originalImageToken,
        previewImageToken = persistedPreviewToken,
        requiresPrivacyConfirmation = false
    )
}
