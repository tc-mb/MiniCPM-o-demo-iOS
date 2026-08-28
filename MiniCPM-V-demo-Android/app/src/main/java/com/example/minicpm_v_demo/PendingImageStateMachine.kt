package com.example.minicpm_v_demo

sealed interface PendingImageState {
    val progressPercent: Int?

    data object Empty : PendingImageState {
        override val progressPercent: Int? = null
    }

    data class Preprocessing(val requestId: Long) : PendingImageState {
        override val progressPercent: Int? = null
    }

    data class Ready(val requestId: Long) : PendingImageState {
        override val progressPercent: Int = 100
    }
}

data class ChatInputControls(
    val textEnabled: Boolean,
    val sendEnabled: Boolean,
    val mediaEnabled: Boolean,
    val modelSettingsEnabled: Boolean
)

enum class PendingImageCancellationMode {
    CONTEXT_RESET,
    USER_REMOVE
}

enum class PendingImageCancellationDisplay {
    HIDDEN,
    CLEARING
}

object PendingImageCancellationPolicy {
    fun displayWhileCancelling(
        hasProcessingJob: Boolean,
        mode: PendingImageCancellationMode
    ): PendingImageCancellationDisplay =
        if (hasProcessingJob && mode == PendingImageCancellationMode.CONTEXT_RESET) {
            PendingImageCancellationDisplay.CLEARING
        } else {
            PendingImageCancellationDisplay.HIDDEN
        }
}

class PendingImageStateMachine {
    var state: PendingImageState = PendingImageState.Empty
        private set

    private var nextRequestId = 1L

    fun start(): Long {
        check(state is PendingImageState.Empty) {
            "A pending image must be consumed or cleared before selecting another image"
        }
        return nextRequestId++.also { requestId ->
            state = PendingImageState.Preprocessing(requestId)
        }
    }

    fun complete(requestId: Long): Boolean {
        val current = state as? PendingImageState.Preprocessing ?: return false
        if (current.requestId != requestId) return false
        state = PendingImageState.Ready(requestId)
        return true
    }

    fun fail(requestId: Long): Boolean {
        val currentRequestId = when (val current = state) {
            is PendingImageState.Preprocessing -> current.requestId
            is PendingImageState.Ready -> current.requestId
            PendingImageState.Empty -> return false
        }
        if (currentRequestId != requestId) return false
        state = PendingImageState.Empty
        return true
    }

    fun consumeReady(): Long? {
        val ready = state as? PendingImageState.Ready ?: return null
        state = PendingImageState.Empty
        return ready.requestId
    }

    fun clear() {
        state = PendingImageState.Empty
    }

    fun controls(
        modelReady: Boolean,
        engineBusy: Boolean,
        videoProcessing: Boolean,
        hasText: Boolean
    ): ChatInputControls {
        if (!modelReady || engineBusy || videoProcessing) {
            return ChatInputControls(
                textEnabled = false,
                sendEnabled = false,
                mediaEnabled = false,
                modelSettingsEnabled = false
            )
        }

        val isPreprocessing = state is PendingImageState.Preprocessing
        val hasPendingImage = state !is PendingImageState.Empty
        return ChatInputControls(
            textEnabled = true,
            sendEnabled = hasText && !isPreprocessing,
            mediaEnabled = !hasPendingImage,
            modelSettingsEnabled = !hasPendingImage
        )
    }
}
