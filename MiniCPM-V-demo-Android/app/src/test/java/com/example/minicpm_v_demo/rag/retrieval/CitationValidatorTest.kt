package com.example.minicpm_v_demo.rag.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class CitationValidatorTest {
    private val candidates = listOf(
        RetrievedChunk(1, "a.txt", "line 1", "alpha", 0.9f, documentId = "doc-1"),
        RetrievedChunk(2, "b.txt", "line 2", "beta", 0.8f, documentId = "doc-2"),
    )

    @Test
    fun keepsOnlyCandidateSourcesActuallyReferencedByAnswer() {
        assertEquals(
            listOf("S2", "S1"),
            CitationValidator.validate("Result [S2], confirmed by [S1] and [S99].", candidates)
                .map { it.sourceId },
        )
    }

    @Test
    fun ignoresMalformedAndEmbeddedCitationLikeText() {
        assertEquals(
            emptyList<ValidatedCitation>(),
            CitationValidator.validate("No proof: [S0] [S-1] prefix[S1]word [S 2].", candidates),
        )
    }
}
