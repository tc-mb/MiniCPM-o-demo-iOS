package com.example.minicpm_v_demo.rag.parser

object PdfPageSelection {
    fun needsOcr(text: String): Boolean {
        val visible = text.count { !it.isWhitespace() }
        if (visible < MIN_VISIBLE_CHARACTERS) return true
        val replacement = text.count { it == '\uFFFD' }
        return replacement.toDouble() / visible > MAX_REPLACEMENT_RATIO
    }

    fun choose(selectableText: String, ocrText: String): String =
        if (needsOcr(selectableText)) ocrText.trim() else selectableText.trim()

    private const val MIN_VISIBLE_CHARACTERS = 40
    private const val MAX_REPLACEMENT_RATIO = 0.25
}

typealias PdfOcrFallback = PdfPageSelection
