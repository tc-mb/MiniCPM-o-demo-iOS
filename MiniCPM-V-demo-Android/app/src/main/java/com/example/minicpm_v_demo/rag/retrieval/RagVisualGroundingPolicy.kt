package com.example.minicpm_v_demo.rag.retrieval

import com.example.minicpm_v_demo.VisualResponseAssertion
import com.example.minicpm_v_demo.VisualResponseDecision
import com.example.minicpm_v_demo.VisualResponseDetector

/**
 * Allows a text-grounded RAG answer through the visual hallucination guard only
 * when every visual assertion sentence carries a valid citation to an accepted
 * source. Content-safety and privacy policy decisions are intentionally outside
 * this narrow override and retain their existing higher priority.
 */
object RagVisualGroundingPolicy {
    fun resolve(
        baseline: VisualResponseDecision,
        response: String,
        sources: List<RetrievedChunk>,
    ): VisualResponseDecision {
        if (baseline == VisualResponseDecision.ALLOW) return baseline
        if (response.isBlank() || sources.isEmpty()) return baseline

        val visualAssertionSentences = response.sentences().filter { sentence ->
            VisualResponseDetector.classify(sentence) != VisualResponseAssertion.NON_VISUAL_RESPONSE
        }
        if (visualAssertionSentences.isEmpty()) return baseline

        return if (
            visualAssertionSentences.all { sentence ->
                CitationValidator.validate(sentence, sources).isNotEmpty()
            }
        ) {
            VisualResponseDecision.ALLOW
        } else {
            baseline
        }
    }

    private fun String.sentences(): List<String> = buildList {
        val sentence = StringBuilder()
        this@sentences.forEach { character ->
            sentence.append(character)
            if (character in SENTENCE_TERMINATORS) {
                sentence.toString().trim().takeIf(String::isNotEmpty)?.let(::add)
                sentence.setLength(0)
            }
        }
        sentence.toString().trim().takeIf(String::isNotEmpty)?.let(::add)
    }

    private val SENTENCE_TERMINATORS = setOf('。', '！', '？', '!', '?', '\n', '\r')
}
