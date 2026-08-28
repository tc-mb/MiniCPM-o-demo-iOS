package com.example.minicpm_v_demo.rag.telemetry

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeLogPrivacyTest {
    @Test
    fun nativeInferenceLogsNeverFormatPromptHistoryOrGeneratedTokenText() {
        val source = File("src/main/cpp/llama_jni.cpp").takeIf(File::isFile)
            ?: File("app/src/main/cpp/llama_jni.cpp")
        val text = source.readText(Charsets.UTF_8)
        val forbiddenFragments = listOf(
            "Formatted and added %s message",
            "System prompt received",
            "User prompt received",
            "Formatted user prompt (mtmd, image=%s, minicpmv=%d):",
            "common_token_to_piece(g_context, id).c_str()",
            "cached: `%s`",
        )

        forbiddenFragments.forEach { fragment ->
            assertFalse("Native logs must not expose inference text: $fragment", text.contains(fragment))
        }
    }
}
