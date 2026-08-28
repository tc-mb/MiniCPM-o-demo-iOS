package com.example.minicpm_v_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualContextPolicyTest {

    @Test
    fun inputClassifierReturnsThreeIntentLabels() {
        assertEquals(
            VisualPromptIntent.NEED_VISUAL,
            VisualRequestDetector.classify("它手里拿的是什么？")
        )
        assertEquals(
            VisualPromptIntent.TEXT_ONLY,
            VisualRequestDetector.classify("人类眼睛是如何识别颜色的？")
        )
        assertEquals(
            VisualPromptIntent.UNCERTAIN,
            VisualRequestDetector.classify("帮我看看")
        )
    }

    @Test
    fun outputClassifierReturnsThreeAssertionLabels() {
        assertEquals(
            VisualResponseAssertion.VISUAL_ASSERTION,
            VisualResponseDetector.classify("图片中有三个人，左边的人穿着蓝色衣服。")
        )
        assertEquals(
            VisualResponseAssertion.NON_VISUAL_RESPONSE,
            VisualResponseDetector.classify("当前没有图片，请先上传或拍照。")
        )
        assertEquals(
            VisualResponseAssertion.UNCERTAIN_VISUAL_ASSERTION,
            VisualResponseDetector.classify("它看起来可能坏了。")
        )
        assertEquals(
            VisualResponseAssertion.VISUAL_ASSERTION,
            VisualResponseDetector.classify("当前没有图片，但图片中有三个人。")
        )
    }

    @Test
    fun outputPolicyBlocksUnsupportedVisualClaimsBeforeDisplay() {
        val policy = VisualContextPolicy()

        assertEquals(
            VisualResponseDecision.BLOCK_VISUAL_ASSERTION,
            policy.evaluateResponse("图中是一只白色的狗。", hadVisualContext = false)
        )
        assertEquals(
            VisualResponseDecision.BLOCK_UNCERTAIN_ASSERTION,
            policy.evaluateResponse("这个似乎是塑料制品。", hadVisualContext = false)
        )
        assertEquals(
            VisualResponseDecision.ALLOW,
            policy.evaluateResponse("图像识别是一项计算机视觉技术。", hadVisualContext = false)
        )
        assertEquals(
            VisualResponseDecision.ALLOW,
            policy.evaluateResponse("图中是一只白色的狗。", hadVisualContext = true)
        )
    }

    @Test
    fun discoveredBypassCorpusRemainsBlocked() {
        val resource = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("visual_guard_regression_cases.tsv")
        ) { "visual_guard_regression_cases.tsv is missing" }

        resource.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .forEachIndexed { index, line ->
                    val columns = line.split('\t', limit = 3)
                    assertEquals("Malformed regression row ${index + 1}", 3, columns.size)
                    val (kind, expected, text) = columns
                    when (kind) {
                        "INPUT" -> assertEquals(
                            "Unexpected input label for: $text",
                            VisualPromptIntent.valueOf(expected),
                            VisualRequestDetector.classify(text)
                        )
                        "OUTPUT" -> assertEquals(
                            "Unexpected output label for: $text",
                            VisualResponseAssertion.valueOf(expected),
                            VisualResponseDetector.classify(text)
                        )
                        else -> error("Unknown regression kind '$kind'")
                    }
                }
        }
    }

    @Test
    fun explicitChineseImageQuestionIsBlockedWithoutVisualContext() {
        val policy = VisualContextPolicy()

        assertTrue(policy.shouldBlock("这张图说了什么？"))
        assertTrue(policy.shouldBlock("请读取截图中的文字"))
    }

    @Test
    fun explicitEnglishImageQuestionIsBlockedWithoutVisualContext() {
        val policy = VisualContextPolicy()

        assertTrue(policy.shouldBlock("Describe this image."))
        assertTrue(policy.shouldBlock("What does the photo show?"))
    }

    @Test
    fun ordinaryTextQuestionsAreAllowedWithoutVisualContext() {
        val policy = VisualContextPolicy()

        assertFalse(policy.shouldBlock("介绍一下图像识别技术"))
        assertFalse(policy.shouldBlock("请帮我生成一张图片的提示词"))
        assertFalse(policy.shouldBlock("What is image classification?"))
    }

    @Test
    fun successfulVisualPrefillAllowsImageFollowUp() {
        val policy = VisualContextPolicy()

        policy.markVisualContextAvailable()

        assertTrue(policy.hasVisualContext.value)
        assertFalse(policy.shouldBlock("这张图说了什么？"))
    }

    @Test
    fun resetBlocksVisualQuestionsAgain() {
        val policy = VisualContextPolicy()
        policy.markVisualContextAvailable()

        policy.reset()

        assertFalse(policy.hasVisualContext.value)
        assertTrue(policy.shouldBlock("Describe this image."))
    }

    @Test
    fun welcomeActionsAcquireVisualInputUntilContextExists() {
        assertEquals(
            WelcomeSuggestionMode.VISUAL_INPUT_ACTIONS,
            WelcomeSuggestionPolicy.mode(isTextOnly = false, hasVisualContext = false)
        )
        assertEquals(
            WelcomeSuggestionMode.VISUAL_PROMPTS,
            WelcomeSuggestionPolicy.mode(isTextOnly = false, hasVisualContext = true)
        )
        assertEquals(
            WelcomeSuggestionMode.TEXT_PROMPTS,
            WelcomeSuggestionPolicy.mode(isTextOnly = true, hasVisualContext = false)
        )
    }
}
