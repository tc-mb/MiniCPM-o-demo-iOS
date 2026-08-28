package com.example.minicpm_v_demo

enum class PromptDestination {
    MODEL,
    LOCAL_ONLY
}

enum class LocalGuardReplyKind {
    NO_VISUAL_CONTEXT,
    UNCERTAIN_VISUAL_REQUEST
}

data class PromptDispatchPlan(
    val destination: PromptDestination,
    val localReplyKind: LocalGuardReplyKind? = null
) {
    val includeInModelContext: Boolean
        get() = destination == PromptDestination.MODEL
}

object LocalGuardReplyPolicy {
    fun plan(decision: VisualPromptDecision): PromptDispatchPlan =
        when (decision) {
            VisualPromptDecision.ALLOW -> PromptDispatchPlan(
                destination = PromptDestination.MODEL
            )
            VisualPromptDecision.BLOCK_NEEDS_VISUAL -> PromptDispatchPlan(
                destination = PromptDestination.LOCAL_ONLY,
                localReplyKind = LocalGuardReplyKind.NO_VISUAL_CONTEXT
            )
            VisualPromptDecision.BLOCK_UNCERTAIN -> PromptDispatchPlan(
                destination = PromptDestination.LOCAL_ONLY,
                localReplyKind = LocalGuardReplyKind.UNCERTAIN_VISUAL_REQUEST
            )
        }
}

object LocalResponseStreamer {
    fun frames(text: String): Sequence<String> = sequence {
        val accumulated = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            accumulated.appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
            yield(accumulated.toString())
        }
    }
}
