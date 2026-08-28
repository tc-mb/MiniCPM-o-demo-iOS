package com.example.minicpm_v_demo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMessageEditAffordanceTest {
    @Test
    fun longPressIsBoundToTheCompleteAiBubble() {
        val layout = File("src/main/res/layout/item_ai_message.xml").readText()
        val adapter = File("src/main/java/com/example/minicpm_v_demo/ChatAdapter.kt").readText()

        assertTrue(
            "The AI card needs a stable bubble ID so its full bounds can receive long presses",
            layout.contains("android:id=\"@+id/ai_message_bubble\"")
        )
        assertTrue(
            "Long press must be registered across the AI bubble and its child views",
            adapter.contains("bindLongPressToWholeBubble(item)") &&
                adapter.contains("bindLongPressRecursively(messageBubble")
        )
    }
}
