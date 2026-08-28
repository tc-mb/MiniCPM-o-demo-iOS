package com.example.minicpm_v_demo.rag.config

/** Hard safety ceilings for untrusted documents processed by the local RAG pipeline. */
object RagLimits {
    const val MAX_SOURCE_BYTES = 100L * 1024 * 1024
    const val MAX_TOTAL_PRIVATE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_PDF_PAGES = 1_000
    const val MAX_OOXML_ENTRIES = 20_000
    const val MAX_OOXML_UNCOMPRESSED_BYTES = 500L * 1024 * 1024
    const val MAX_COMPRESSION_RATIO = 100.0
    const val MAX_XML_DEPTH = 128
    const val MAX_TEXT_CHARS_PER_DOCUMENT = 20_000_000
    const val MAX_PARSE_WALL_TIME_MS = 15 * 60 * 1_000L
}
