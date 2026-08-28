package com.example.minicpm_v_demo.rag.parser

import java.util.Locale

object ParserRegistry {
    fun forDocument(displayName: String, mimeType: String): DocumentParser {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        return when {
            extension == "pdf" || mime == "application/pdf" -> PdfDocumentParser()
            extension == "docx" || mime == DOCX_MIME -> DocxParser()
            extension == "xlsx" || mime == XLSX_MIME -> XlsxParser()
            extension == "pptx" || mime == PPTX_MIME -> PptxParser()
            extension in setOf("md", "markdown") || mime == "text/markdown" -> MarkdownParser()
            extension == "csv" || mime in setOf("text/csv", "application/csv") -> CsvParser()
            extension in setOf("html", "htm") || mime == "text/html" -> HtmlParser()
            extension == "txt" || mime == "text/plain" -> TextParser()
            else -> fail(ParserError.UNSUPPORTED_FORMAT)
        }
    }

    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
}
