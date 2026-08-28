package com.example.minicpm_v_demo.rag.embed

data class TokenSpan(val start: Int, val endExclusive: Int) {
    init { require(start >= 0 && endExclusive > start) }
}

/** Exact tokenizer boundary contract. Task 8 supplies the implementation from the signed E5 model package. */
interface E5Tokenizer {
    val modelId: String
    val modelSha256: String
    val tokenizerSha256: String
    fun tokenSpans(text: String): List<TokenSpan>
}

fun E5Tokenizer.validatedTokenSpans(text: String): List<TokenSpan> {
    val spans = tokenSpans(text)
    var previousEnd = 0
    spans.forEach { span ->
        require(span.start >= previousEnd && span.endExclusive <= text.length) { "Invalid tokenizer boundary" }
        require(span.start !in 1 until text.length || !Character.isLowSurrogate(text[span.start])) {
            "Tokenizer split a Unicode surrogate pair"
        }
        require(span.endExclusive !in 1 until text.length || !Character.isHighSurrogate(text[span.endExclusive - 1])) {
            "Tokenizer split a Unicode surrogate pair"
        }
        previousEnd = span.endExclusive
    }
    return spans
}
