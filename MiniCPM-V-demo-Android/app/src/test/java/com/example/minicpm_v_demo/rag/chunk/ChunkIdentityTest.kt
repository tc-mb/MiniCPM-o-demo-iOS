package com.example.minicpm_v_demo.rag.chunk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkIdentityTest {
    @Test
    fun `chunk IDs are stable positive and document scoped`() {
        val first = ChunkIdentity.id("doc-a", 0, "a".repeat(64))

        assertEquals(first, ChunkIdentity.id("doc-a", 0, "a".repeat(64)))
        assertTrue(first > 0)
        assertNotEquals(first, ChunkIdentity.id("doc-b", 0, "a".repeat(64)))
        assertNotEquals(first, ChunkIdentity.id("doc-a", 1, "a".repeat(64)))
    }
}
