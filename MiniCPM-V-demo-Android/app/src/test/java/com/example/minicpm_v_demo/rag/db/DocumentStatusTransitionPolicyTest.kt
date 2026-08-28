package com.example.minicpm_v_demo.rag.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentStatusTransitionPolicyTest {
    @Test
    fun `happy path allows text and OCR indexing pipelines`() {
        assertAllowed(DocumentStatus.QUEUED, DocumentStatus.COPYING)
        assertAllowed(DocumentStatus.COPYING, DocumentStatus.PARSING)
        assertAllowed(DocumentStatus.PARSING, DocumentStatus.CHUNKING)
        assertAllowed(DocumentStatus.PARSING, DocumentStatus.OCR)
        assertAllowed(DocumentStatus.OCR, DocumentStatus.CHUNKING)
        assertAllowed(DocumentStatus.CHUNKING, DocumentStatus.EMBEDDING)
        assertAllowed(DocumentStatus.EMBEDDING, DocumentStatus.INDEXING)
        assertAllowed(DocumentStatus.INDEXING, DocumentStatus.READY)
    }

    @Test
    fun `only READY documents can become stale or start deletion`() {
        assertAllowed(DocumentStatus.READY, DocumentStatus.STALE)
        assertAllowed(DocumentStatus.READY, DocumentStatus.DELETING)
        assertAllowed(DocumentStatus.STALE, DocumentStatus.EMBEDDING)
        assertAllowed(DocumentStatus.STALE, DocumentStatus.INDEXING)
        assertBlocked(DocumentStatus.PARSING, DocumentStatus.READY)
        assertBlocked(DocumentStatus.FAILED, DocumentStatus.READY)
    }

    @Test
    fun `active work may pause fail or cancel but deleting is terminal`() {
        DocumentStatus.activeWorkStates.forEach { active ->
            assertAllowed(active, DocumentStatus.PAUSED)
            assertAllowed(active, DocumentStatus.FAILED)
            assertAllowed(active, DocumentStatus.CANCELLED)
        }
        DocumentStatus.entries.forEach { target ->
            assertBlocked(DocumentStatus.DELETING, target)
        }
    }

    @Test
    fun `state cannot transition to itself`() {
        DocumentStatus.entries.forEach { status ->
            assertBlocked(status, status)
        }
    }

    private fun assertAllowed(from: DocumentStatus, to: DocumentStatus) {
        assertTrue("Expected $from -> $to", DocumentStatusTransitionPolicy.canTransition(from, to))
    }

    private fun assertBlocked(from: DocumentStatus, to: DocumentStatus) {
        assertFalse("Expected $from -/-> $to", DocumentStatusTransitionPolicy.canTransition(from, to))
    }
}
