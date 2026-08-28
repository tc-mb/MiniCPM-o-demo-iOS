package com.example.minicpm_v_demo.rag.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageSelectionTest {
    @Test
    fun `short or damaged text layer requests OCR`() {
        assertTrue(PdfPageSelection.needsOcr("invoice"))
        assertTrue(PdfPageSelection.needsOcr("\uFFFD".repeat(20) + "readable text that is otherwise long enough to inspect"))
        assertFalse(PdfPageSelection.needsOcr("This selectable paragraph contains enough readable characters for local indexing."))
    }

    @Test
    fun `page selection chooses one source and never concatenates duplicates`() {
        val selectable = "This is the accurate selectable PDF text layer with sufficient detail."
        assertEquals(selectable, PdfPageSelection.choose(selectable, "noisy OCR copy"))

        val ocr = "Recognized scanned invoice number 12345 and payment terms."
        assertEquals(ocr, PdfPageSelection.choose("", ocr))
        assertFalse(PdfPageSelection.choose("", ocr).contains("\n\n"))
    }
}
