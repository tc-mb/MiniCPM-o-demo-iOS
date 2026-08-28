package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.DocumentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagWorkRecoveryPolicyTest {
    @Test
    fun `OCR work is recoverable after process restart`() {
        assertTrue(RagWorkRecoveryPolicy.shouldReschedule(DocumentStatus.OCR))
        assertTrue(RagWorkRecoveryPolicy.shouldReschedule(DocumentStatus.CHUNKING))
    }
    @Test
    fun `copying and parsing documents are rescheduled after app restart`() {
        assertTrue(RagWorkRecoveryPolicy.shouldReschedule(DocumentStatus.QUEUED))
        assertTrue(RagWorkRecoveryPolicy.shouldReschedule(DocumentStatus.COPYING))
        assertTrue(RagWorkRecoveryPolicy.shouldReschedule(DocumentStatus.PARSING))
        assertFalse(RagWorkRecoveryPolicy.shouldReschedule(DocumentStatus.CANCELLED))
        assertFalse(RagWorkRecoveryPolicy.shouldReschedule(DocumentStatus.FAILED))
    }

    @Test
    fun `active work is selected before stale finished work`() {
        assertEquals(
            "running",
            RagWorkRecoveryPolicy.selectObservable(
                listOf(
                    Candidate("old", active = false, failed = false),
                    Candidate("running", active = true, failed = false),
                ),
                Candidate::active,
                Candidate::failed,
            )?.id,
        )
    }

    @Test
    fun `failed stage is selected after the remaining chain is blocked`() {
        assertEquals(
            "parse-failed",
            RagWorkRecoveryPolicy.selectObservable(
                listOf(
                    Candidate("copy-succeeded", active = false, failed = false),
                    Candidate("parse-failed", active = false, failed = true),
                    Candidate("chunk-blocked", active = false, failed = false),
                ),
                Candidate::active,
                Candidate::failed,
            )?.id,
        )
    }

    private data class Candidate(val id: String, val active: Boolean, val failed: Boolean)
}
