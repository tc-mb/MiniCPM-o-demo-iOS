package com.example.minicpm_v_demo

data class Conversation(
    val id: Long,
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf()
)

data class TimelineMutation(
    val retained: List<ChatMessage>,
    val removed: List<ChatMessage>
)

object ModelHistoryText {
    /** Native generation stores only the answer after a completed think block. */
    fun assistant(text: String): String {
        val end = text.indexOf("</think>")
        return if (end >= 0) text.substring(end + "</think>".length).trimStart() else text
    }
}

/** Owner of independent chat timelines. Native model state is rebuilt by the UI. */
class ConversationStore(
    private val untitledName: () -> String = { "New conversation" }
) {
    private val conversations = mutableListOf<Conversation>()
    private var nextConversationId = 1L
    private var nextMessageId = 1L

    var activeConversationId: Long = 0L
        private set

    init {
        createConversation()
    }

    val active: Conversation
        get() = requireNotNull(conversations.firstOrNull { it.id == activeConversationId })

    fun all(): List<Conversation> = conversations.toList()

    fun snapshot(): ConversationArchive = ConversationArchive(
        activeConversationId = activeConversationId,
        conversations = conversations.map { conversation ->
            conversation.copy(messages = conversation.messages.toMutableList())
        }
    )

    fun restore(archive: ConversationArchive) {
        require(archive.conversations.isNotEmpty()) { "Archive must contain a conversation" }
        require(archive.conversations.any { it.id == archive.activeConversationId }) {
            "Archive active conversation is missing"
        }
        conversations.clear()
        conversations += archive.conversations.map { conversation ->
            conversation.copy(messages = conversation.messages.toMutableList())
        }
        activeConversationId = archive.activeConversationId
        nextConversationId = (conversations.maxOfOrNull { it.id } ?: 0L) + 1L
        nextMessageId = ((conversations.asSequence()
            .flatMap { it.messages.asSequence() }
            .maxOfOrNull { it.id }) ?: 0L) + 1L
    }

    fun nextMessageId(): Long = nextMessageId++

    fun createConversation(initialMessages: List<ChatMessage> = emptyList()): Long {
        val conversation = Conversation(
            id = nextConversationId++,
            title = untitledName(),
            messages = initialMessages.toMutableList()
        )
        conversations.add(0, conversation)
        activeConversationId = conversation.id
        updateNextMessageId(initialMessages)
        return conversation.id
    }

    fun switchTo(id: Long): Boolean {
        if (conversations.none { it.id == id }) return false
        activeConversationId = id
        return true
    }

    /** Deletes a session and always leaves one active session available. */
    fun deleteConversation(id: Long, initialMessages: List<ChatMessage> = emptyList()): Conversation? {
        val index = conversations.indexOfFirst { it.id == id }
        if (index < 0) return null
        val removed = conversations.removeAt(index)
        if (conversations.isEmpty()) {
            createConversation(initialMessages)
        } else if (activeConversationId == id) {
            activeConversationId = conversations[minOf(index, conversations.lastIndex)].id
        }
        return removed
    }

    fun updateTitleFromFirstUserMessage() {
        val firstPrompt = active.messages
            .filterIsInstance<ChatMessage.UserMessage>()
            .firstOrNull { it.includeInModelContext && it.text.isNotBlank() }
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: return
        active.title = if (firstPrompt.length <= TITLE_LIMIT) {
            firstPrompt
        } else {
            firstPrompt.take(TITLE_LIMIT - 1) + "…"
        }
    }

    fun editUserAndTruncate(messageId: Long, newText: String): TimelineMutation? {
        val index = active.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val current = active.messages[index] as? ChatMessage.UserMessage ?: return null
        val replacement = current.copy(
            text = newText,
            requiresPrivacyConfirmation = false,
            includeInModelContext = true
        )
        val removed = active.messages.subList(index + 1, active.messages.size).toList()
        active.messages.subList(index + 1, active.messages.size).clear()
        active.messages[index] = replacement
        updateTitleFromFirstUserMessage()
        return TimelineMutation(active.messages.toList(), removed)
    }

    fun editAssistantText(messageId: Long, newText: String): TimelineMutation? {
        val index = active.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val current = active.messages[index] as? ChatMessage.AiMessage ?: return null
        active.messages[index] = current.copy(text = newText, isGenerating = false, answerEdited = true)
        return TimelineMutation(active.messages.toList(), emptyList())
    }

    fun deleteMessage(messageId: Long): TimelineMutation? {
        val index = active.messages.indexOfFirst { it.id == messageId }
        if (index < 0 || active.messages[index] is ChatMessage.WelcomeCard) return null
        val removed = listOf(active.messages.removeAt(index))
        updateTitleFromFirstUserMessage()
        return TimelineMutation(active.messages.toList(), removed)
    }

    fun replayMessages(): List<ChatMessage> = active.messages.filter {
        when (it) {
            is ChatMessage.UserMessage -> it.includeInModelContext && !it.requiresPrivacyConfirmation
            is ChatMessage.AiMessage -> it.includeInModelContext && !it.isGenerating && it.text.isNotBlank()
            is ChatMessage.WelcomeCard -> false
        }
    }

    fun referencedImageTokens(): Set<String> = conversations.asSequence()
        .flatMap { it.messages.asSequence() }
        .filterIsInstance<ChatMessage.UserMessage>()
        .flatMap { sequenceOf(it.originalImageToken, it.previewImageToken) }
        .filterNotNull()
        .toSet()

    private fun updateNextMessageId(messages: List<ChatMessage>) {
        val maximum = messages.maxOfOrNull { it.id } ?: return
        if (nextMessageId <= maximum) nextMessageId = maximum + 1
    }

    companion object {
        private const val TITLE_LIMIT = 28
    }
}
