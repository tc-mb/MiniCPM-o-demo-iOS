package com.example.minicpm_v_demo.rag.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagLimitsTest {
    @Test
    fun `defaults enforce reviewed document parsing bounds`() {
        assertEquals(100L * 1024 * 1024, RagLimits.MAX_SOURCE_BYTES)
        assertEquals(2L * 1024 * 1024 * 1024, RagLimits.MAX_TOTAL_PRIVATE_BYTES)
        assertEquals(1_000, RagLimits.MAX_PDF_PAGES)
        assertEquals(20_000, RagLimits.MAX_OOXML_ENTRIES)
        assertEquals(500L * 1024 * 1024, RagLimits.MAX_OOXML_UNCOMPRESSED_BYTES)
        assertEquals(100.0, RagLimits.MAX_COMPRESSION_RATIO, 0.0)
        assertEquals(128, RagLimits.MAX_XML_DEPTH)
        assertEquals(20_000_000, RagLimits.MAX_TEXT_CHARS_PER_DOCUMENT)
        assertEquals(15 * 60 * 1_000L, RagLimits.MAX_PARSE_WALL_TIME_MS)
    }

    @Test
    fun `all parsing bounds are positive and total storage exceeds one file`() {
        assertTrue(RagLimits.MAX_SOURCE_BYTES > 0)
        assertTrue(RagLimits.MAX_TOTAL_PRIVATE_BYTES >= RagLimits.MAX_SOURCE_BYTES)
        assertTrue(RagLimits.MAX_PDF_PAGES > 0)
        assertTrue(RagLimits.MAX_OOXML_ENTRIES > 0)
        assertTrue(RagLimits.MAX_OOXML_UNCOMPRESSED_BYTES > RagLimits.MAX_SOURCE_BYTES)
        assertTrue(RagLimits.MAX_COMPRESSION_RATIO >= 1.0)
        assertTrue(RagLimits.MAX_XML_DEPTH > 0)
        assertTrue(RagLimits.MAX_TEXT_CHARS_PER_DOCUMENT > 0)
        assertTrue(RagLimits.MAX_PARSE_WALL_TIME_MS > 0)
    }
}
