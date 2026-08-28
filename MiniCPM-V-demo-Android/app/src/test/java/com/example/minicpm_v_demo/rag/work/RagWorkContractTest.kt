package com.example.minicpm_v_demo.rag.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RagWorkContractTest {
    @Test
    fun `unique work name and worker input contain only document id`() {
        assertEquals("rag-index-doc_123", RagWorkContract.uniqueWorkName("doc_123"))
        assertEquals(
            mapOf(RagWorkContract.KEY_DOCUMENT_ID to "doc_123"),
            RagWorkContract.inputValues("doc_123"),
        )
    }

    @Test
    fun `unsafe document ids are rejected before creating work`() {
        assertThrows(IllegalArgumentException::class.java) {
            RagWorkContract.uniqueWorkName("../outside")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RagWorkContract.inputValues("")
        }
    }
}
