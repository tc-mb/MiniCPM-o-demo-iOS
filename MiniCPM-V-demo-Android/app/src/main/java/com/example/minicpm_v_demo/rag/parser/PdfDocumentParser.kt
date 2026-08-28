package com.example.minicpm_v_demo.rag.parser

import com.example.minicpm_v_demo.rag.config.RagLimits
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

interface OcrAwareDocumentParser {
    val requiresOcr: Boolean
}

class PdfDocumentParser : DocumentParser, OcrAwareDocumentParser {
    override var requiresOcr: Boolean = false
        private set
    val textByPage: MutableMap<Int, String> = linkedMapOf()

    override fun parse(input: ParserInput): Sequence<ParsedBlock> {
        requiresOcr = false
        textByPage.clear()
        val blocks = mutableListOf<ParsedBlock>()
        var totalChars = 0
        try {
            PDDocument.load(input.input, MemoryUsageSetting.setupMainMemoryOnly()).use { document ->
                if (document.numberOfPages > RagLimits.MAX_PDF_PAGES) fail(ParserError.PDF_PAGE_LIMIT)
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (page in 1..document.numberOfPages) {
                    if (!input.shouldContinue()) fail(ParserError.CANCELLED)
                    stripper.startPage = page
                    stripper.endPage = page
                    val text = stripper.getText(document).trim()
                    textByPage[page] = text
                    if (PdfOcrFallback.needsOcr(text)) {
                        requiresOcr = true
                    } else {
                        totalChars += text.length
                        if (totalChars > input.maxChars) fail(ParserError.TEXT_LIMIT_EXCEEDED)
                        blocks += ParsedBlock(text, BlockStructure.PARAGRAPH, null, "page", page.toString())
                    }
                }
            }
        } catch (error: ParserException) {
            throw error
        } catch (_: Exception) {
            fail(ParserError.PDF_CORRUPT)
        }
        return blocks.asSequence()
    }
}
