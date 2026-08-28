package com.example.minicpm_v_demo

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VisualPromptIntent {
    NEED_VISUAL,
    TEXT_ONLY,
    UNCERTAIN
}

enum class VisualPromptDecision {
    ALLOW,
    BLOCK_NEEDS_VISUAL,
    BLOCK_UNCERTAIN
}

enum class VisualResponseAssertion {
    VISUAL_ASSERTION,
    NON_VISUAL_RESPONSE,
    UNCERTAIN_VISUAL_ASSERTION
}

enum class VisualResponseDecision {
    ALLOW,
    BLOCK_VISUAL_ASSERTION,
    BLOCK_UNCERTAIN_ASSERTION
}

class VisualContextPolicy {
    private val _hasVisualContext = MutableStateFlow(false)
    val hasVisualContext: StateFlow<Boolean> = _hasVisualContext.asStateFlow()

    fun markVisualContextAvailable() {
        _hasVisualContext.value = true
    }

    fun reset() {
        _hasVisualContext.value = false
    }

    fun evaluatePrompt(message: String): VisualPromptDecision {
        if (_hasVisualContext.value) return VisualPromptDecision.ALLOW

        return when (VisualRequestDetector.classify(message)) {
            VisualPromptIntent.NEED_VISUAL -> VisualPromptDecision.BLOCK_NEEDS_VISUAL
            VisualPromptIntent.UNCERTAIN -> VisualPromptDecision.BLOCK_UNCERTAIN
            VisualPromptIntent.TEXT_ONLY -> VisualPromptDecision.ALLOW
        }
    }

    fun shouldBlock(message: String): Boolean =
        evaluatePrompt(message) != VisualPromptDecision.ALLOW

    fun evaluateResponse(
        response: String,
        hadVisualContext: Boolean = _hasVisualContext.value
    ): VisualResponseDecision {
        if (hadVisualContext) return VisualResponseDecision.ALLOW

        return when (VisualResponseDetector.classify(response)) {
            VisualResponseAssertion.VISUAL_ASSERTION ->
                VisualResponseDecision.BLOCK_VISUAL_ASSERTION
            VisualResponseAssertion.UNCERTAIN_VISUAL_ASSERTION ->
                VisualResponseDecision.BLOCK_UNCERTAIN_ASSERTION
            VisualResponseAssertion.NON_VISUAL_RESPONSE -> VisualResponseDecision.ALLOW
        }
    }
}

object VisualRequestDetector {
    private val explicitVisualReferences = listOf(
        "这张图",
        "这幅图",
        "这张图片",
        "这张照片",
        "这张截图",
        "该图片",
        "该照片",
        "上图",
        "我上传的图",
        "上传的图片",
        "附件图片",
        "this image",
        "this photo",
        "this picture",
        "attached image",
        "attached photo",
        "uploaded image",
        "uploaded photo",
        "the image",
        "the photo",
        "the picture",
        "the screenshot"
    )

    private val visualLocationReferences = listOf(
        "图中",
        "图里",
        "图片中",
        "图片里",
        "照片中",
        "照片里",
        "截图中",
        "截图里",
        "画面中",
        "画面里",
        "in the image",
        "in this image",
        "in the photo",
        "in this photo",
        "in the picture",
        "in this picture",
        "on the image",
        "on this image"
    )

    private val indirectReferences = listOf(
        "它",
        "这个",
        "那个",
        "上面",
        "上边",
        "下面",
        "左边",
        "右边",
        "眼前",
        " it ",
        " this ",
        " that ",
        " them ",
        " above ",
        " below ",
        " left ",
        " right "
    )

    private val strongVisualActions = listOf(
        "拿的",
        "拿着",
        "手里",
        "穿什么",
        "穿着",
        "什么颜色",
        "告诉我颜色",
        "有谁",
        "几个",
        "数一数",
        "读出来",
        "写了什么",
        "是什么字",
        "what color",
        "wearing",
        "holding",
        "count",
        "how many",
        "who is",
        "what is above",
        "what is below",
        "what is on",
        "read the",
        "written"
    )

    private val vagueRequests = listOf(
        "帮我看看",
        "帮忙看看",
        "看一下",
        "它正常吗",
        "这是什么意思",
        "这个是什么意思",
        "take a look",
        "is it normal",
        "what is this",
        "what does this mean"
    )

    private val genericLookActions = listOf(
        "看看",
        "看一下",
        "瞅瞅",
        "look at",
        "take a look"
    )

    fun classify(message: String): VisualPromptIntent {
        val text = VisualTextNormalizer.normalize(message)
        if (text.spaced.isEmpty()) return VisualPromptIntent.TEXT_ONLY

        if (explicitVisualReferences.any(text::contains)) return VisualPromptIntent.NEED_VISUAL
        if (visualLocationReferences.any(text::contains)) return VisualPromptIntent.NEED_VISUAL
        if (text.contains("看到附件") ||
            text.contains("别说没看到") ||
            text.contains("seen the attachment")
        ) {
            return VisualPromptIntent.NEED_VISUAL
        }
        if (vagueRequests.any(text::contains)) return VisualPromptIntent.UNCERTAIN

        val hasIndirectReference = indirectReferences.any(text::contains)
        val hasStrongVisualAction = strongVisualActions.any(text::contains)
        val hasGenericLookAction = genericLookActions.any(text::contains)
        if (hasIndirectReference && (hasStrongVisualAction || hasGenericLookAction)) {
            return VisualPromptIntent.NEED_VISUAL
        }
        if (hasIndirectReference || hasGenericLookAction) {
            return VisualPromptIntent.UNCERTAIN
        }

        return VisualPromptIntent.TEXT_ONLY
    }

    fun requiresVisualContext(message: String): Boolean =
        classify(message) == VisualPromptIntent.NEED_VISUAL
}

object VisualResponseDetector {
    private val safeNoVisualResponses = listOf(
        "没有图片",
        "没有可用图片",
        "无法看到任何图片",
        "无法看到图片",
        "请先上传",
        "请上传图片",
        "no image",
        "cannot see an image",
        "can't see an image",
        "please upload"
    )

    private val explicitVisualAssertions = listOf(
        "图片中",
        "图片里",
        "图中",
        "图里",
        "照片中",
        "照片里",
        "截图中",
        "画面中",
        "我看到",
        "可以看到",
        "能够看到",
        "上面写着",
        "左边是",
        "右边是",
        "上方是",
        "下方是",
        "in the image",
        "in the photo",
        "in the picture",
        "i can see",
        "the object on the left",
        "the object on the right",
        "on the left is",
        "on the right is"
    )

    private val implicitAppearanceAssertions = listOf(
        "他穿着",
        "她穿着",
        "它穿着",
        "手里拿着",
        "颜色是",
        "he is wearing",
        "she is wearing",
        "it is wearing",
        "is holding"
    )

    private val uncertainAssertions = listOf(
        "看起来",
        "似乎是",
        "似乎有",
        "这个可能",
        "它可能",
        "appears to be",
        "seems to be",
        "looks like",
        "it may be",
        "this may be"
    )

    fun classify(response: String): VisualResponseAssertion {
        val text = VisualTextNormalizer.normalize(response)
        if (text.spaced.isEmpty()) return VisualResponseAssertion.NON_VISUAL_RESPONSE
        if (explicitVisualAssertions.any(text::contains) ||
            implicitAppearanceAssertions.any(text::contains)
        ) {
            return VisualResponseAssertion.VISUAL_ASSERTION
        }
        if (uncertainAssertions.any(text::contains)) {
            return VisualResponseAssertion.UNCERTAIN_VISUAL_ASSERTION
        }
        if (safeNoVisualResponses.any(text::contains)) {
            return VisualResponseAssertion.NON_VISUAL_RESPONSE
        }

        return VisualResponseAssertion.NON_VISUAL_RESPONSE
    }
}

private data class NormalizedVisualText(
    val spaced: String,
    val compact: String
) {
    fun contains(needle: String): Boolean {
        val normalizedNeedle = needle.lowercase(Locale.ROOT)
        return if (normalizedNeedle.any(Char::isWhitespace)) {
            " $spaced ".contains(" ${normalizedNeedle.trim()} ")
        } else {
            compact.contains(normalizedNeedle)
        }
    }
}

private object VisualTextNormalizer {
    private const val MAX_CLASSIFIER_CHARS = 8_192

    fun normalize(raw: String): NormalizedVisualText {
        val bounded = raw.take(MAX_CLASSIFIER_CHARS)
        val unicodeNormalized = Normalizer.normalize(bounded, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val spaced = buildString(unicodeNormalized.length) {
            unicodeNormalized.forEach { character ->
                if (character.isLetterOrDigit()) {
                    append(character)
                } else if (isNotEmpty() && last() != ' ') {
                    append(' ')
                }
            }
        }.trim()

        return NormalizedVisualText(
            spaced = spaced,
            compact = spaced.filterNot(Char::isWhitespace)
        )
    }
}

enum class WelcomeSuggestionMode {
    TEXT_PROMPTS,
    VISUAL_INPUT_ACTIONS,
    VISUAL_PROMPTS
}

sealed interface WelcomeAction {
    data class SendPrompt(val prompt: String) : WelcomeAction
    data object PickMedia : WelcomeAction
    data object TakePhoto : WelcomeAction
}

object WelcomeSuggestionPolicy {
    fun mode(isTextOnly: Boolean, hasVisualContext: Boolean): WelcomeSuggestionMode =
        when {
            isTextOnly -> WelcomeSuggestionMode.TEXT_PROMPTS
            hasVisualContext -> WelcomeSuggestionMode.VISUAL_PROMPTS
            else -> WelcomeSuggestionMode.VISUAL_INPUT_ACTIONS
        }
}
