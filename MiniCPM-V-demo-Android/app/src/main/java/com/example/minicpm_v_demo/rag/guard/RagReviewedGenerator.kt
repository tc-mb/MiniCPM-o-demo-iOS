package com.example.minicpm_v_demo.rag.guard

import com.example.minicpm_v_demo.rag.retrieval.RagPromptAssembler
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

fun interface GroundednessClassifier {
    suspend fun classify(
        question: String,
        sources: List<RetrievedChunk>,
        answer: String,
    ): GroundednessVerdict
}

class WatchdogGroundednessClassifier(
    private val delegate: GroundednessClassifier,
    private val timeoutMs: Long,
) : GroundednessClassifier {
    init {
        require(timeoutMs > 0)
    }

    override suspend fun classify(
        question: String,
        sources: List<RetrievedChunk>,
        answer: String,
    ): GroundednessVerdict = withTimeoutOrNull(timeoutMs) {
        delegate.classify(question, sources, answer)
    } ?: throw GroundednessReviewTimeoutException()

    private class GroundednessReviewTimeoutException : IllegalStateException()
}

data class GroundednessCalibrationProfile(
    val classifierSha256: String,
    val groundedProbabilityThreshold: Float,
) {
    init {
        require(
            classifierSha256.length == SHA256_HEX_LENGTH &&
                classifierSha256.all { it in '0'..'9' || it in 'a'..'f' },
        )
        require(groundedProbabilityThreshold.isFinite() && groundedProbabilityThreshold in 0f..1f)
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}

object CurrentGroundednessCalibration {
    val profile = GroundednessCalibrationProfile(
        classifierSha256 =
            "d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2",
        groundedProbabilityThreshold = 0.95f,
    )
}

object ExperimentalGroundednessCalibration {
    val profile = CurrentGroundednessCalibration.profile
}

sealed interface ReviewedRagGeneration {
    data class Accepted(
        val answer: String,
        val regenerationCount: Int,
    ) : ReviewedRagGeneration

    data object FallbackToNormalGeneration : ReviewedRagGeneration
}

/**
 * Keeps unreviewed model output out of both the UI and durable conversation history.
 * A rejected candidate is never copied into the correction prompt or returned to callers.
 */
class RagReviewedGenerator(
    private val classifier: GroundednessClassifier,
    private val profile: GroundednessCalibrationProfile,
) {
    suspend fun review(
        question: String,
        sources: List<RetrievedChunk>,
        firstCandidate: String,
        regenerate: suspend (prompt: String) -> String,
    ): ReviewedRagGeneration {
        require(question.isNotBlank())
        require(sources.isNotEmpty())

        return try {
            when (reviewAction(classifyVisible(question, sources, firstCandidate), regenerationCount = 0)) {
                RagOutputReviewAction.ACCEPT -> ReviewedRagGeneration.Accepted(
                    attributedAnswer(question, firstCandidate),
                    regenerationCount = 0,
                )
                RagOutputReviewAction.FALLBACK_TO_NORMAL_GENERATION ->
                    ReviewedRagGeneration.FallbackToNormalGeneration
                RagOutputReviewAction.REPLACE_WITH_KNOWLEDGE_BASE -> ReviewedRagGeneration.Accepted(
                    knowledgeBaseEvidenceAnswer(question, sources),
                    regenerationCount = 0,
                )
                RagOutputReviewAction.REGENERATE -> {
                    val correctionPrompt = buildCorrectionPrompt(question, sources)
                    val correctedCandidate = regenerate(correctionPrompt)
                    when (
                        reviewAction(
                            classifyVisible(question, sources, correctedCandidate),
                            regenerationCount = 1,
                        )
                    ) {
                        RagOutputReviewAction.ACCEPT -> ReviewedRagGeneration.Accepted(
                            attributedAnswer(question, correctedCandidate),
                            regenerationCount = 1,
                        )
                        RagOutputReviewAction.FALLBACK_TO_NORMAL_GENERATION ->
                            ReviewedRagGeneration.FallbackToNormalGeneration
                        RagOutputReviewAction.REGENERATE,
                        RagOutputReviewAction.REPLACE_WITH_KNOWLEDGE_BASE ->
                            ReviewedRagGeneration.Accepted(
                                knowledgeBaseEvidenceAnswer(question, sources),
                                regenerationCount = 1,
                            )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ReviewedRagGeneration.FallbackToNormalGeneration
        }
    }

    private fun reviewAction(
        verdict: GroundednessVerdict,
        regenerationCount: Int,
    ): RagOutputReviewAction {
        if (verdict.modelSha256 != profile.classifierSha256) {
            throw ClassifierIdentityMismatchException()
        }
        val calibratedLabel = if (
            verdict.label == GroundednessLabel.GROUNDED &&
            verdict.groundedProbability < profile.groundedProbabilityThreshold
        ) {
            GroundednessLabel.PARTIAL
        } else {
            verdict.label
        }
        return RagOutputReviewPolicy.decide(calibratedLabel, regenerationCount)
    }

    private suspend fun classifyVisible(
        question: String,
        sources: List<RetrievedChunk>,
        candidate: String,
    ): GroundednessVerdict {
        val visibleAnswer = visibleAnswer(candidate)
        if (visibleAnswer.isBlank()) throw EmptyVisibleAnswerException()
        return classifier.classify(question, sources, visibleAnswer)
    }

    private fun visibleAnswer(candidate: String): String {
        val start = candidate.indexOf(THINKING_START_TAG)
        if (start < 0) return candidate.trim()
        val end = candidate.indexOf(THINKING_END_TAG, start + THINKING_START_TAG.length)
        if (end < 0) return ""
        return (
            candidate.substring(0, start) +
                candidate.substring(end + THINKING_END_TAG.length)
            ).trim()
    }

    private fun buildCorrectionPrompt(
        question: String,
        sources: List<RetrievedChunk>,
    ): String = RagPromptAssembler.assemble(question, sources) + "\n\n" + correctionInstruction(question)

    private fun correctionInstruction(question: String): String =
        if (usesChinese(question)) {
            "上一次草稿未通过依据性审核。请重新回答，并确保每一项事实断言都能由以上摘录直接支持；不要猜测或补充摘录之外的事实。"
        } else {
            "The previous draft failed grounding review. Answer again, ensuring every factual claim is directly supported by the excerpts above. Do not guess or add facts outside the excerpts."
        }

    private fun knowledgeBaseEvidenceAnswer(
        question: String,
        sources: List<RetrievedChunk>,
    ): String {
        val prefix = if (usesChinese(question)) {
            "根据数据库中内容："
        } else {
            "According to the knowledge base:"
        }
        val excerpts = sources.mapIndexed { index, source ->
            "[S${index + 1}] ${neutralizeDisplayControlTags(source.text.trim())}"
        }
        return prefix + "\n" + excerpts.joinToString("\n\n")
    }

    private fun neutralizeDisplayControlTags(text: String): String =
        text.replace(THINKING_START_TAG, "＜think＞", ignoreCase = true)
            .replace(THINKING_END_TAG, "＜/think＞", ignoreCase = true)

    private fun attributedAnswer(question: String, answer: String): String {
        val prefix = if (usesChinese(question)) {
            "根据数据库中内容，"
        } else {
            "According to the knowledge base, "
        }
        val thinkingEnd = answer.indexOf(THINKING_END_TAG)
        return if (thinkingEnd >= 0) {
            val contentStart = thinkingEnd + THINKING_END_TAG.length
            answer.substring(0, contentStart) + "\n" + prefix + answer.substring(contentStart).trimStart()
        } else {
            prefix + answer.trimStart()
        }
    }

    private fun usesChinese(text: String): Boolean =
        text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

    private class ClassifierIdentityMismatchException : IllegalStateException()
    private class EmptyVisibleAnswerException : IllegalStateException()

    private companion object {
        const val THINKING_START_TAG = "<think>"
        const val THINKING_END_TAG = "</think>"
    }
}
