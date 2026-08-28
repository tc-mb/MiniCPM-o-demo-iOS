package com.example.minicpm_v_demo.rag.naming

import java.text.Normalizer
import java.util.Locale

data class ValidatedKnowledgeBaseName(
    val displayName: String,
    val normalizedName: String,
)

enum class KnowledgeBaseNameError {
    EMPTY,
    FORBIDDEN_CHARACTER,
    TOO_LONG,
}

class KnowledgeBaseNameValidationException(
    val reason: KnowledgeBaseNameError,
) : IllegalArgumentException(reason.name)

object KnowledgeBaseNamePolicy {
    const val MAX_CODE_POINTS = 50

    fun validateAndNormalize(raw: String): ValidatedKnowledgeBaseName {
        val compatibilityNormalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val displayName = collapseWhitespace(compatibilityNormalized)
        if (displayName.isEmpty()) {
            throw KnowledgeBaseNameValidationException(KnowledgeBaseNameError.EMPTY)
        }
        if (displayName.codePointCount(0, displayName.length) > MAX_CODE_POINTS) {
            throw KnowledgeBaseNameValidationException(KnowledgeBaseNameError.TOO_LONG)
        }
        return ValidatedKnowledgeBaseName(
            displayName = displayName,
            normalizedName = displayName.lowercase(Locale.ROOT),
        )
    }

    private fun collapseWhitespace(value: String): String {
        val result = StringBuilder(value.length)
        var pendingSpace = false
        value.codePoints().forEachOrdered { codePoint ->
            if (isForbidden(codePoint)) {
                throw KnowledgeBaseNameValidationException(KnowledgeBaseNameError.FORBIDDEN_CHARACTER)
            }
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.isNotEmpty()
            } else {
                if (pendingSpace) result.append(' ')
                result.appendCodePoint(codePoint)
                pendingSpace = false
            }
        }
        return result.toString()
    }

    private fun isForbidden(codePoint: Int): Boolean =
        Character.isISOControl(codePoint) ||
            Character.getType(codePoint) == Character.LINE_SEPARATOR.toInt() ||
            Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR.toInt() ||
            Character.getType(codePoint) == Character.SURROGATE.toInt() ||
            codePoint == ZERO_WIDTH_SPACE ||
            codePoint == LEFT_TO_RIGHT_MARK ||
            codePoint == RIGHT_TO_LEFT_MARK ||
            codePoint in BIDI_EMBEDDING_RANGE ||
            codePoint == WORD_JOINER ||
            codePoint in BIDI_ISOLATE_RANGE ||
            codePoint == ZERO_WIDTH_NO_BREAK_SPACE

    private const val ZERO_WIDTH_SPACE = 0x200B
    private const val LEFT_TO_RIGHT_MARK = 0x200E
    private const val RIGHT_TO_LEFT_MARK = 0x200F
    private val BIDI_EMBEDDING_RANGE = 0x202A..0x202E
    private const val WORD_JOINER = 0x2060
    private val BIDI_ISOLATE_RANGE = 0x2066..0x2069
    private const val ZERO_WIDTH_NO_BREAK_SPACE = 0xFEFF
}
