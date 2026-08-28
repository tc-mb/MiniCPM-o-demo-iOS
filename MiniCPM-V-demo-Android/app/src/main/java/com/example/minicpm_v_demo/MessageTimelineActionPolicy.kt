package com.example.minicpm_v_demo

enum class MessageTimelineAction {
    EDIT,
    DELETE
}

object MessageTimelineActionPolicy {
    fun availableActions(
        mutationInProgress: Boolean,
        destructiveMutationAllowed: Boolean
    ): List<MessageTimelineAction> {
        if (mutationInProgress) return emptyList()
        return if (destructiveMutationAllowed) {
            listOf(MessageTimelineAction.EDIT, MessageTimelineAction.DELETE)
        } else {
            listOf(MessageTimelineAction.EDIT)
        }
    }
}
