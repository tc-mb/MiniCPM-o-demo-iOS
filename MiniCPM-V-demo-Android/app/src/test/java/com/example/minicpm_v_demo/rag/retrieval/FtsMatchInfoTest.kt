package com.example.minicpm_v_demo.rag.retrieval

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FtsMatchInfoTest {
    @Test
    fun `decodes pcnalx and computes hand checked BM25`() {
        val blob = littleEndianInts(
            1, // phrases
            1, // columns
            10, // documents
            100, // average tokens in the column
            50, // tokens in this document
            3, 20, 2, // tf in row, total hits, rows containing phrase
        )

        val score = FtsMatchInfo.parse(blob).bm25()

        val idf = ln(1.0 + (10 - 2 + 0.5) / (2 + 0.5))
        val expected = idf * (3.0 * (1.2 + 1.0)) /
            (3.0 + 1.2 * (1.0 - 0.75 + 0.75 * 50.0 / 100.0))
        assertEquals(expected, score, 1e-9)
    }

    @Test
    fun `computes corpus size independent matched phrase coverage`() {
        val blob = littleEndianInts(
            2, // phrases
            1, // columns
            10, // documents
            100, // average tokens in the column
            50, // tokens in this document
            3, 20, 2, // first phrase is present
            0, 0, 0, // second phrase is absent
        )

        assertEquals(0.5, FtsMatchInfo.parse(blob).matchedPhraseRatio(), 0.0)
    }

    @Test
    fun `rejects truncated negative and oversized matchinfo blobs`() {
        assertThrows(FtsMatchInfoFormatException::class.java) {
            FtsMatchInfo.parse(littleEndianInts(1, 1, 10))
        }
        assertThrows(FtsMatchInfoFormatException::class.java) {
            FtsMatchInfo.parse(littleEndianInts(1, 1, 10, 10, 5, -1, 1, 1))
        }
        assertThrows(FtsMatchInfoFormatException::class.java) {
            FtsMatchInfo.parse(littleEndianInts(10_000, 10_000, 1))
        }
    }

    @Test
    fun `builds CJK bigram word number and quoted phrase queries`() {
        assertEquals(
            "\"项目\" OR \"目验\" OR \"验收\" OR \"收编\" OR \"编号\" OR \"AB-2026-0810\"",
            SafeFtsQuery.build("项目验收编号 AB-2026-0810"),
        )
        assertEquals(
            "\"travel reimbursement\" OR \"policy\"",
            SafeFtsQuery.build("\"travel reimbursement\" policy"),
        )
    }

    @Test
    fun `quotes operator injection as data and rejects empty input`() {
        val query = SafeFtsQuery.build("x\" OR * DELETE")

        assertEquals("\"x\" OR \"OR\" OR \"DELETE\"", query)
        assertFalse(query.orEmpty().contains('*'))
        assertNull(SafeFtsQuery.build("  😀  "))
    }

    private fun littleEndianInts(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * Int.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> values.forEach(buffer::putInt) }
            .array()
}
