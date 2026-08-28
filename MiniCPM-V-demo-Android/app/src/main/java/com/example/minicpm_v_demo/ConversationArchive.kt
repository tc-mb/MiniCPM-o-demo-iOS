package com.example.minicpm_v_demo

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class ConversationArchive(
    val activeConversationId: Long,
    val conversations: List<Conversation>
)

/**
 * A deliberately small, allow-listed archive format. It never instantiates a
 * class name from disk and bounds every collection and string before allocation.
 */
object ConversationArchiveCodec {
    private const val MAGIC = 0x4D435043 // MCPC
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1
    private const val USER_MESSAGE = 1
    private const val AI_MESSAGE = 2
    private const val WELCOME_CARD = 3
    private const val MAX_CONVERSATIONS = 128
    private const val MAX_MESSAGES_PER_CONVERSATION = 4_096
    private const val MAX_TOTAL_MESSAGES = 10_000
    private const val MAX_TITLE_BYTES = 4 * 1024
    private const val MAX_MESSAGE_BYTES = 1024 * 1024
    private const val MAX_INFO_BYTES = 16 * 1024
    private const val MAX_TOKEN_BYTES = 512
    private const val MAX_CITATIONS_PER_MESSAGE = 32
    private const val MAX_SOURCE_ID_BYTES = 64
    private const val MAX_DOCUMENT_ID_BYTES = 512
    private const val MAX_DOCUMENT_NAME_BYTES = 4 * 1024
    private const val MAX_LOCATOR_BYTES = 4 * 1024
    private const val MAX_QUOTED_TEXT_BYTES = 64 * 1024
    private const val MAX_RAG_RUN_ID_BYTES = 512

    @Throws(IOException::class)
    fun write(output: OutputStream, archive: ConversationArchive) {
        validateArchive(archive)
        val data = DataOutputStream(BufferedOutputStream(output))
        run {
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(archive.activeConversationId)
            data.writeInt(archive.conversations.size)
            archive.conversations.forEach { conversation ->
                data.writeLong(conversation.id)
                data.writeBoundedString(conversation.title, MAX_TITLE_BYTES)
                data.writeInt(conversation.messages.size)
                conversation.messages.forEach { message ->
                    when (message) {
                        is ChatMessage.UserMessage -> {
                            data.writeByte(USER_MESSAGE)
                            data.writeLong(message.id)
                            data.writeBoundedString(message.text, MAX_MESSAGE_BYTES)
                            data.writeNullableString(message.imageInfo, MAX_INFO_BYTES)
                            data.writeNullableString(message.originalImageToken, MAX_TOKEN_BYTES)
                            data.writeNullableString(message.previewImageToken, MAX_TOKEN_BYTES)
                            data.writeBoolean(message.isPrefilling)
                            data.writeBoolean(message.requiresPrivacyConfirmation)
                            data.writeBoolean(message.includeInModelContext)
                            data.writeBoolean(message.isVideo)
                        }
                        is ChatMessage.AiMessage -> {
                            data.writeByte(AI_MESSAGE)
                            data.writeLong(message.id)
                            data.writeBoundedString(message.text, MAX_MESSAGE_BYTES)
                            data.writeBoolean(message.isGenerating)
                            data.writeBoolean(message.includeInModelContext)
                            data.writeNullableString(message.ragRunId, MAX_RAG_RUN_ID_BYTES)
                            data.writeBoolean(message.answerEdited)
                            if (message.citations.size > MAX_CITATIONS_PER_MESSAGE) {
                                throw IOException("Too many archived citations")
                            }
                            data.writeInt(message.citations.size)
                            message.citations.forEach { citation ->
                                if (citation.messageId != message.id) {
                                    throw IOException("Citation belongs to a different message")
                                }
                                data.writeLong(citation.messageId)
                                data.writeBoundedString(citation.sourceId, MAX_SOURCE_ID_BYTES)
                                data.writeLong(citation.chunkId)
                                data.writeBoundedString(citation.documentId, MAX_DOCUMENT_ID_BYTES)
                                data.writeBoundedString(citation.documentNameSnapshot, MAX_DOCUMENT_NAME_BYTES)
                                data.writeBoundedString(citation.locator, MAX_LOCATOR_BYTES)
                                data.writeBoundedString(citation.quotedText, MAX_QUOTED_TEXT_BYTES)
                                data.writeDouble(citation.retrievalScore)
                                data.writeInt(citation.retrievalVersion)
                            }
                        }
                        is ChatMessage.WelcomeCard -> {
                            data.writeByte(WELCOME_CARD)
                            data.writeLong(message.id)
                            data.writeBoolean(message.isTextOnly)
                            data.writeBoolean(message.hasVisualContext)
                        }
                    }
                }
            }
            data.flush()
        }
    }

    @Throws(IOException::class)
    fun read(input: InputStream): ConversationArchive {
        try {
            val data = DataInputStream(BufferedInputStream(input))
            run {
                if (data.readInt() != MAGIC) throw IOException("Invalid conversation archive")
                val version = data.readInt()
                if (version !in LEGACY_VERSION..VERSION) {
                    throw IOException("Unsupported conversation archive version")
                }
                val activeId = data.readLong()
                val conversationCount = data.readBoundedCount(MAX_CONVERSATIONS)
                if (conversationCount == 0) throw IOException("Empty conversation archive")
                var totalMessages = 0
                val conversations = ArrayList<Conversation>(conversationCount)
                val conversationIds = HashSet<Long>(conversationCount)
                repeat(conversationCount) {
                    val id = data.readLong()
                    if (id <= 0 || !conversationIds.add(id)) throw IOException("Invalid conversation id")
                    val title = data.readBoundedString(MAX_TITLE_BYTES)
                    val messageCount = data.readBoundedCount(MAX_MESSAGES_PER_CONVERSATION)
                    totalMessages += messageCount
                    if (totalMessages > MAX_TOTAL_MESSAGES) throw IOException("Too many archived messages")
                    val messageIds = HashSet<Long>(messageCount)
                    val messages = ArrayList<ChatMessage>(messageCount)
                    repeat(messageCount) {
                        val type = data.readUnsignedByte()
                        val messageId = data.readLong()
                        if (messageId < 0 || !messageIds.add(messageId)) throw IOException("Invalid message id")
                        messages += when (type) {
                            USER_MESSAGE -> ChatMessage.UserMessage(
                                id = messageId,
                                text = data.readBoundedString(MAX_MESSAGE_BYTES),
                                imageInfo = data.readNullableString(MAX_INFO_BYTES),
                                originalImageToken = data.readNullableString(MAX_TOKEN_BYTES),
                                previewImageToken = data.readNullableString(MAX_TOKEN_BYTES),
                                isPrefilling = data.readBoolean(),
                                requiresPrivacyConfirmation = data.readBoolean(),
                                includeInModelContext = data.readBoolean(),
                                isVideo = data.readBoolean()
                            )
                            AI_MESSAGE -> {
                                val text = data.readBoundedString(MAX_MESSAGE_BYTES)
                                val generating = data.readBoolean()
                                val includeInContext = data.readBoolean()
                                if (version == LEGACY_VERSION) {
                                    ChatMessage.AiMessage(messageId, text, generating, includeInContext)
                                } else {
                                    val ragRunId = data.readNullableString(MAX_RAG_RUN_ID_BYTES)
                                    val answerEdited = data.readBoolean()
                                    val citationCount = data.readBoundedCount(MAX_CITATIONS_PER_MESSAGE)
                                    val citations = List(citationCount) {
                                        CitationRef(
                                            messageId = data.readLong(),
                                            sourceId = data.readBoundedString(MAX_SOURCE_ID_BYTES),
                                            chunkId = data.readLong(),
                                            documentId = data.readBoundedString(MAX_DOCUMENT_ID_BYTES),
                                            documentNameSnapshot = data.readBoundedString(MAX_DOCUMENT_NAME_BYTES),
                                            locator = data.readBoundedString(MAX_LOCATOR_BYTES),
                                            quotedText = data.readBoundedString(MAX_QUOTED_TEXT_BYTES),
                                            retrievalScore = data.readDouble(),
                                            retrievalVersion = data.readInt(),
                                        ).also { citation ->
                                            if (citation.messageId != messageId) {
                                                throw IOException("Citation belongs to a different message")
                                            }
                                        }
                                    }
                                    ChatMessage.AiMessage(
                                        messageId,
                                        text,
                                        generating,
                                        includeInContext,
                                        citations.toList(),
                                        ragRunId,
                                        answerEdited,
                                    )
                                }
                            }
                            WELCOME_CARD -> ChatMessage.WelcomeCard(
                                id = messageId,
                                isTextOnly = data.readBoolean(),
                                hasVisualContext = data.readBoolean()
                            )
                            else -> throw IOException("Unknown archived message type")
                        }
                    }
                    conversations += Conversation(id, title, messages.toMutableList())
                }
                if (conversations.none { it.id == activeId }) throw IOException("Missing active conversation")
                if (data.read() != -1) throw IOException("Trailing conversation archive data")
                return ConversationArchive(activeId, conversations)
            }
        } catch (error: EOFException) {
            throw IOException("Truncated conversation archive", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid conversation archive", error)
        }
    }

    private fun validateArchive(archive: ConversationArchive) {
        if (archive.conversations.isEmpty() || archive.conversations.size > MAX_CONVERSATIONS) {
            throw IOException("Invalid conversation count")
        }
        if (archive.conversations.none { it.id == archive.activeConversationId }) {
            throw IOException("Missing active conversation")
        }
        var totalMessages = 0
        val ids = HashSet<Long>()
        archive.conversations.forEach { conversation ->
            if (conversation.id <= 0 || !ids.add(conversation.id)) throw IOException("Invalid conversation id")
            if (conversation.messages.size > MAX_MESSAGES_PER_CONVERSATION) throw IOException("Too many messages")
            totalMessages += conversation.messages.size
            if (totalMessages > MAX_TOTAL_MESSAGES) throw IOException("Too many messages")
        }
    }

    private fun DataOutputStream.writeBoundedString(value: String, maximum: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maximum) throw IOException("Archived string is too large")
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.writeNullableString(value: String?, maximum: Int) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value, maximum)
    }

    private fun DataInputStream.readBoundedCount(maximum: Int): Int {
        val value = readInt()
        if (value < 0 || value > maximum) throw IOException("Invalid archive count")
        return value
    }

    private fun DataInputStream.readBoundedString(maximum: Int): String {
        val length = readBoundedCount(maximum)
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataInputStream.readNullableString(maximum: Int): String? =
        if (readBoolean()) readBoundedString(maximum) else null
}

/** Crash-recoverable app-private archive store using same-directory renames. */
class ConversationArchiveDiskStore(rootDirectory: File) {
    private val directory = rootDirectory.canonicalFile
    val archiveFile: File = File(directory, ARCHIVE_NAME)
    private val backupFile: File = File(directory, BACKUP_NAME)

    @Synchronized
    fun save(archive: ConversationArchive) {
        ensureDirectory()
        val temporary = File.createTempFile("conversations-", ".tmp", directory)
        check(temporary.canonicalFile.parentFile == directory)
        try {
            FileOutputStream(temporary).use { output ->
                ConversationArchiveCodec.write(output, archive)
                output.fd.sync()
            }
            if (backupFile.exists() && !backupFile.delete()) throw IOException("Cannot replace archive backup")
            if (archiveFile.exists() && !archiveFile.renameTo(backupFile)) {
                throw IOException("Cannot rotate conversation archive")
            }
            if (!temporary.renameTo(archiveFile)) {
                if (backupFile.exists()) backupFile.renameTo(archiveFile)
                throw IOException("Cannot install conversation archive")
            }
            backupFile.delete()
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun load(): ConversationArchive? {
        ensureDirectory()
        if (archiveFile.isFile) {
            readCandidate(archiveFile)?.let { return it }
        }
        if (backupFile.isFile) {
            return readCandidate(backupFile)?.also {
                if (!archiveFile.exists()) backupFile.renameTo(archiveFile)
            }
        }
        return null
    }

    private fun readCandidate(candidate: File): ConversationArchive? {
        if (candidate.length() <= 0 || candidate.length() > MAX_ARCHIVE_BYTES) {
            quarantine(candidate)
            return null
        }
        return try {
            FileInputStream(candidate).use(ConversationArchiveCodec::read)
        } catch (_: IOException) {
            quarantine(candidate)
            null
        }
    }

    private fun ensureDirectory() {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("Conversation directory is unavailable")
        }
    }

    private fun quarantine(file: File) {
        val target = File(
            directory,
            "conversations.corrupt-${System.currentTimeMillis()}-${file.name}"
        )
        if (target.canonicalFile.parentFile == directory) file.renameTo(target)
    }

    companion object {
        private const val ARCHIVE_NAME = "conversations.bin"
        private const val BACKUP_NAME = "conversations.backup.bin"
        private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    }
}
