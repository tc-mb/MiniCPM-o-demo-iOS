package com.example.minicpm_v_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStoreTest {
    @Test
    fun editingAssistantPreservesCitationsAndMarksAnswerEdited() {
        val store = ConversationStore()
        val citation = CitationRef(7, "S1", 3, "doc", "policy.txt", "line 2", "evidence", 0.8, 1)
        store.active.messages += ChatMessage.AiMessage(
            id = 7,
            text = "original",
            citations = listOf(citation),
            ragRunId = "run-7",
        )

        store.editAssistantText(7, "corrected")

        val edited = store.active.messages.single() as ChatMessage.AiMessage
        assertEquals("corrected", edited.text)
        assertEquals(listOf(citation), edited.citations)
        assertEquals("run-7", edited.ragRunId)
        assertTrue(edited.answerEdited)
    }

    @Test
    fun createsSwitchesAndDeletesIndependentConversations() {
        val store = ConversationStore { "新对话" }
        val first = store.activeConversationId
        store.active.messages += ChatMessage.UserMessage(1, "first")

        val second = store.createConversation()
        store.active.messages += ChatMessage.UserMessage(2, "second")

        assertNotEquals(first, second)
        assertTrue(store.switchTo(first))
        assertEquals("first", (store.active.messages.single() as ChatMessage.UserMessage).text)
        store.deleteConversation(first)
        assertEquals(second, store.activeConversationId)
        assertFalse(store.switchTo(first))
    }

    @Test
    fun editingUserTurnReplacesItAndTruncatesTail() {
        val store = populatedStore()
        val mutation = store.editUserAndTruncate(1, "edited")!!

        assertEquals(listOf(1L), mutation.retained.map { it.id })
        assertEquals(listOf(2L, 3L, 4L), mutation.removed.map { it.id })
        assertEquals("edited", (store.active.messages.single() as ChatMessage.UserMessage).text)
    }

    @Test
    fun editingOneConversationTruncatesItsGeneratingRagTailWithoutChangingAnotherConversation() {
        val store = populatedStore()
        val firstConversationId = store.activeConversationId
        store.active.messages += ChatMessage.AiMessage(
            id = 5,
            text = "",
            isGenerating = true,
            ragRunId = "active-rag-run",
            ragGenerationStage = RagGenerationStage.RETRIEVING,
        )
        val secondConversationId = store.createConversation(
            listOf(
                ChatMessage.UserMessage(6, "independent question"),
                ChatMessage.AiMessage(7, "independent answer"),
            ),
        )

        assertTrue(store.switchTo(firstConversationId))
        val mutation = store.editUserAndTruncate(1, "edited first question")!!

        assertEquals(listOf(2L, 3L, 4L, 5L), mutation.removed.map { it.id })
        assertEquals(listOf(1L), store.active.messages.map { it.id })
        assertEquals(listOf(1L), store.replayMessages().map { it.id })
        assertTrue(store.switchTo(secondConversationId))
        assertEquals(listOf(6L, 7L), store.active.messages.map { it.id })
        assertEquals(
            listOf("independent question", "independent answer"),
            store.active.messages.map { message ->
                when (message) {
                    is ChatMessage.UserMessage -> message.text
                    is ChatMessage.AiMessage -> message.text
                    is ChatMessage.WelcomeCard -> error("Unexpected welcome card")
                }
            },
        )
    }

    @Test
    fun editingAssistantTurnOnlyChangesTextAndPreservesLaterTurns() {
        val store = populatedStore()
        val mutation = store.editAssistantText(2, "corrected answer")!!

        assertEquals(listOf(1L, 2L, 3L, 4L), mutation.retained.map { it.id })
        assertEquals(
            "corrected answer",
            (mutation.retained[1] as ChatMessage.AiMessage).text
        )
        assertTrue(mutation.removed.isEmpty())
    }

    @Test
    fun roleSpecificEditMethodsRejectTheWrongMessageType() {
        val store = populatedStore()

        assertEquals(null, store.editAssistantText(1, "wrong role"))
        assertEquals(null, store.editUserAndTruncate(2, "wrong role"))
        assertEquals(listOf(1L, 2L, 3L, 4L), store.active.messages.map { it.id })
    }

    @Test
    fun resubmittingEditedImageMessageWithoutNewAttachmentPreservesItsImage() {
        val original = ChatMessage.UserMessage(
            id = 8,
            text = "edited",
            imageInfo = "512 x 512",
            originalImageToken = "source-original.img",
            previewImageToken = "source-preview.img",
            requiresPrivacyConfirmation = true
        )

        val confirmed = original.confirmedForSubmission(attachment = null)

        assertEquals("source-original.img", confirmed.originalImageToken)
        assertEquals("source-preview.img", confirmed.previewImageToken)
        assertEquals("512 x 512", confirmed.imageInfo)
        assertFalse(confirmed.requiresPrivacyConfirmation)
    }

    @Test
    fun editingPreviouslyBlockedUserMessageMakesReplacementEligibleForContext() {
        val store = ConversationStore()
        store.active.messages += ChatMessage.UserMessage(
            id = 1,
            text = "blocked",
            includeInModelContext = false
        )

        store.editUserAndTruncate(1, "safe replacement")

        assertTrue((store.active.messages.single() as ChatMessage.UserMessage).includeInModelContext)
    }

    @Test
    fun editRemainsAvailableWhileGenerationIsBusyButDeleteDoesNot() {
        assertEquals(
            listOf(MessageTimelineAction.EDIT),
            MessageTimelineActionPolicy.availableActions(
                mutationInProgress = false,
                destructiveMutationAllowed = false
            )
        )
        assertTrue(
            MessageTimelineActionPolicy.availableActions(
                mutationInProgress = true,
                destructiveMutationAllowed = false
            ).isEmpty()
        )
    }

    @Test
    fun deletingAssistantOnlyRemovesSelectedBubbleWithoutTruncation() {
        val store = populatedStore()
        val mutation = store.deleteMessage(2)!!

        assertEquals(listOf(1L, 3L, 4L), mutation.retained.map { it.id })
        assertEquals(listOf(2L), mutation.removed.map { it.id })
    }

    @Test
    fun replayExcludesLocalOnlyAndUnconfirmedMessages() {
        val store = ConversationStore()
        store.active.messages += ChatMessage.UserMessage(1, "real")
        store.active.messages += ChatMessage.AiMessage(2, "answer")
        store.active.messages += ChatMessage.UserMessage(3, "blocked", includeInModelContext = false)
        store.active.messages += ChatMessage.AiMessage(4, "local guard", includeInModelContext = false)
        store.active.messages += ChatMessage.UserMessage(5, "private", requiresPrivacyConfirmation = true)

        assertEquals(listOf(1L, 2L), store.replayMessages().map { it.id })
    }

    @Test
    fun referencedImagesIncludeAllConversations() {
        val store = ConversationStore()
        store.active.messages += ChatMessage.UserMessage(1, "one", originalImageToken = "source-one.img")
        store.createConversation()
        store.active.messages += ChatMessage.UserMessage(2, "two", originalImageToken = "source-two.img")

        store.active.messages += ChatMessage.UserMessage(
            3,
            "preview",
            previewImageToken = "source-preview.img"
        )

        assertEquals(
            setOf("source-one.img", "source-two.img", "source-preview.img"),
            store.referencedImageTokens()
        )
    }

    @Test
    fun assistantReplayDropsCompletedPrivateThinkingBlock() {
        assertEquals(
            "visible answer",
            ModelHistoryText.assistant("<think>private reasoning</think>\n\nvisible answer")
        )
        assertEquals("plain answer", ModelHistoryText.assistant("plain answer"))
    }

    @Test
    fun restorePreservesActiveConversationAndAdvancesGeneratedIds() {
        val store = ConversationStore { "New conversation" }
        store.restore(
            ConversationArchive(
                activeConversationId = 9,
                conversations = listOf(
                    Conversation(
                        id = 4,
                        title = "older",
                        messages = mutableListOf(ChatMessage.UserMessage(40, "old"))
                    ),
                    Conversation(
                        id = 9,
                        title = "active",
                        messages = mutableListOf(ChatMessage.AiMessage(51, "answer"))
                    )
                )
            )
        )

        assertEquals(9L, store.activeConversationId)
        assertEquals(52L, store.nextMessageId())
        assertEquals(10L, store.createConversation())
    }

    private fun populatedStore(): ConversationStore = ConversationStore().also { store ->
        store.active.messages += ChatMessage.UserMessage(1, "question")
        store.active.messages += ChatMessage.AiMessage(2, "answer")
        store.active.messages += ChatMessage.UserMessage(3, "follow up")
        store.active.messages += ChatMessage.AiMessage(4, "follow answer")
    }
}
