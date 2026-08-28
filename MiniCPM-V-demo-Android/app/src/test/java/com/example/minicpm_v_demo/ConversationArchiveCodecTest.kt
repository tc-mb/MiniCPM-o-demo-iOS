package com.example.minicpm_v_demo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationArchiveCodecTest {
    @Test
    fun roundTripPreservesConversationsMessagesAndFlags() {
        val archive = ConversationArchive(
            activeConversationId = 8,
            conversations = listOf(
                Conversation(
                    id = 7,
                    title = "图片会话",
                    messages = mutableListOf(
                        ChatMessage.UserMessage(
                            id = 21,
                            text = "这是什么？",
                            imageInfo = "800 x 600",
                            originalImageToken = "source-original.img",
                            previewImageToken = "source-preview.img",
                            requiresPrivacyConfirmation = true,
                            includeInModelContext = false
                        ),
                        ChatMessage.AiMessage(
                            id = 22,
                            text = "本地提示",
                            includeInModelContext = false,
                            citations = listOf(
                                CitationRef(
                                    messageId = 22,
                                    sourceId = "S1",
                                    chunkId = 91,
                                    documentId = "doc-7",
                                    documentNameSnapshot = "采购制度.txt",
                                    locator = "line 8",
                                    quotedText = "采购限额为 200 元",
                                    retrievalScore = 0.87,
                                    retrievalVersion = 1,
                                )
                            ),
                            ragRunId = "rag-run-22",
                            answerEdited = true,
                        )
                    )
                ),
                Conversation(
                    id = 8,
                    title = "文本会话",
                    messages = mutableListOf(ChatMessage.UserMessage(23, "你好"))
                )
            )
        )

        val output = ByteArrayOutputStream()
        ConversationArchiveCodec.write(output, archive)
        val restored = ConversationArchiveCodec.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(8L, restored.activeConversationId)
        assertEquals(listOf(7L, 8L), restored.conversations.map { it.id })
        val image = restored.conversations.first().messages.first() as ChatMessage.UserMessage
        assertEquals("source-original.img", image.originalImageToken)
        assertEquals("source-preview.img", image.previewImageToken)
        assertTrue(image.requiresPrivacyConfirmation)
        assertFalse(image.includeInModelContext)
        val local = restored.conversations.first().messages[1] as ChatMessage.AiMessage
        assertFalse(local.includeInModelContext)
        assertEquals("rag-run-22", local.ragRunId)
        assertTrue(local.answerEdited)
        assertEquals("采购制度.txt", local.citations.single().documentNameSnapshot)
    }

    @Test
    fun readsLegacyVersionOneArchiveWithEmptyRagMetadata() {
        val output = ByteArrayOutputStream()
        java.io.DataOutputStream(output).use { data ->
            data.writeInt(0x4D435043)
            data.writeInt(1)
            data.writeLong(1)
            data.writeInt(1)
            data.writeLong(1)
            data.writeUtf8("legacy")
            data.writeInt(1)
            data.writeByte(2)
            data.writeLong(9)
            data.writeUtf8("old answer")
            data.writeBoolean(false)
            data.writeBoolean(true)
        }

        val restored = ConversationArchiveCodec.read(ByteArrayInputStream(output.toByteArray()))
        val answer = restored.conversations.single().messages.single() as ChatMessage.AiMessage
        assertEquals("old answer", answer.text)
        assertTrue(answer.citations.isEmpty())
        assertNull(answer.ragRunId)
        assertFalse(answer.answerEdited)
    }

    @Test
    fun transientRagGenerationStageIsNotPersisted() {
        val archive = ConversationArchive(
            activeConversationId = 1,
            conversations = listOf(
                Conversation(
                    id = 1,
                    title = "working",
                    messages = mutableListOf(
                        ChatMessage.AiMessage(
                            id = 5,
                            text = "",
                            isGenerating = true,
                            includeInModelContext = false,
                            ragGenerationStage = RagGenerationStage.RETRIEVING,
                        ),
                    ),
                ),
            ),
        )

        val restored = ConversationArchiveCodec.read(ByteArrayInputStream(encoded(archive)))
        val message = restored.conversations.single().messages.single() as ChatMessage.AiMessage

        assertNull(message.ragGenerationStage)
    }

    @Test
    fun rejectsUnknownVersionAndTruncatedArchive() {
        val bytes = encoded(sampleArchive())
        ByteBuffer.wrap(bytes, 4, 4).putInt(99)

        expectIOException { ConversationArchiveCodec.read(ByteArrayInputStream(bytes)) }
        expectIOException {
            ConversationArchiveCodec.read(ByteArrayInputStream(encoded(sampleArchive()).dropLast(2).toByteArray()))
        }
    }

    @Test
    fun rejectsOversizedStringsBeforeWriting() {
        val archive = ConversationArchive(
            activeConversationId = 1,
            conversations = listOf(Conversation(1, "x".repeat(5_000)))
        )

        expectIOException { ConversationArchiveCodec.write(ByteArrayOutputStream(), archive) }
    }

    @Test
    fun diskStoreAtomicallyReplacesArchiveAndQuarantinesCorruption() {
        val root = java.nio.file.Files.createTempDirectory("conversation-archive-test").toFile()
        try {
            val store = ConversationArchiveDiskStore(root)
            store.save(sampleArchive())
            assertNotNull(store.load())
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })

            store.archiveFile.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(store.load())
            assertTrue(root.listFiles().orEmpty().any { it.name.startsWith("conversations.corrupt-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun diskStoreFallsBackToLastGoodBackupWhenPrimaryIsCorrupt() {
        val root = java.nio.file.Files.createTempDirectory("conversation-backup-test").toFile()
        try {
            val store = ConversationArchiveDiskStore(root)
            ByteArrayOutputStream().also {
                ConversationArchiveCodec.write(it, sampleArchive())
                root.resolve("conversations.backup.bin").writeBytes(it.toByteArray())
            }
            store.archiveFile.writeBytes(byteArrayOf(1, 2, 3))

            assertEquals("hello", ((store.load()!!.conversations.single().messages.single()) as ChatMessage.UserMessage).text)
            assertTrue(store.archiveFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sampleArchive() = ConversationArchive(
        activeConversationId = 1,
        conversations = listOf(
            Conversation(1, "conversation", mutableListOf(ChatMessage.UserMessage(4, "hello")))
        )
    )

    private fun encoded(archive: ConversationArchive): ByteArray =
        ByteArrayOutputStream().also { ConversationArchiveCodec.write(it, archive) }.toByteArray()

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected.
        }
    }

    private fun java.io.DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
