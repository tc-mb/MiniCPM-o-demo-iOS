package com.example.minicpm_v_demo.rag.ui

import com.example.minicpm_v_demo.CitationRef
import com.example.minicpm_v_demo.rag.db.ChunkEntity
import com.example.minicpm_v_demo.rag.db.DocumentEntity
import com.example.minicpm_v_demo.rag.db.DocumentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationSourceResolverTest {
    @Test
    fun `matching document and chunk resolve to current indexed source`() {
        val resolved = CitationSourceResolver.resolve(citation(), document(), chunk())

        assertEquals(
            CitationSourceResolution.Available(
                documentName = "policy.txt",
                locator = "line 8",
                indexedText = "Current indexed policy text",
            ),
            resolved,
        )
    }

    @Test
    fun `missing document resolves to deleted archived source`() {
        val resolved = CitationSourceResolver.resolve(citation(), null, null)

        assertEquals(
            CitationSourceResolution.Deleted("policy.txt", "line 8", "Archived excerpt"),
            resolved,
        )
    }

    @Test
    fun `cross document chunk never exposes unrelated text`() {
        val unrelated = chunk().copy(documentId = "other-document", text = "Unrelated private text")

        val resolved = CitationSourceResolver.resolve(citation(), document(), unrelated)

        assertTrue(resolved is CitationSourceResolution.Unavailable)
        assertEquals("Archived excerpt", (resolved as CitationSourceResolution.Unavailable).archivedExcerpt)
    }

    private fun citation() = CitationRef(
        sourceId = "S1",
        messageId = 1,
        chunkId = 7,
        documentId = "doc-1",
        documentNameSnapshot = "policy.txt",
        locator = "line 8",
        quotedText = "Archived excerpt",
        retrievalScore = 0.9,
        retrievalVersion = 1,
    )

    private fun document() = DocumentEntity(
        id = "doc-1",
        knowledgeBaseId = "kb-1",
        displayName = "policy.txt",
        sourceUri = null,
        privateFileName = "doc-1.src.enc",
        mimeType = "text/plain",
        detectedType = "TXT",
        sha256 = "a".repeat(64),
        sizeBytes = 100,
        status = DocumentStatus.READY,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chunk() = ChunkEntity(
        id = 7,
        documentId = "doc-1",
        knowledgeBaseId = "kb-1",
        ordinal = 0,
        text = "Current indexed policy text",
        searchText = "current indexed policy text",
        displayName = "policy.txt",
        locatorType = "line",
        locatorValue = "8",
        tokenCount = 5,
        contentSha256 = "b".repeat(64),
    )
}
