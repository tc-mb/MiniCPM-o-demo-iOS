package com.example.minicpm_v_demo.rag.embed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.MiniCPMApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E5EmbedderInstrumentedTest {
    @Test
    fun tokenizerAndInt8ModelMatchGoldenSemantics() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as MiniCPMApplication
        val embedder = requireNotNull(app.embeddingModelManager.openInstalled())
        run {
            val text = "query: \u6d4b\u8bd5 hello"
            assertEquals(
                listOf(0L, 41L, 1294L, 12L, 6L, 49125L, 33600L, 31L, 2L),
                embedder.tokenIds(text).toList(),
            )
            assertEquals(
                listOf("que", "ry", ":", " ", "\u6d4b\u8bd5", " hell", "o"),
                embedder.tokenSpans(text).map { text.substring(it.start, it.endExclusive) },
            )
            val vectors = embedder.embed(
                listOf(
                    "mahjong \u7684\u4e2d\u6587\u662f\u4ec0\u4e48",
                    "Mahjong \u4e2d\u6587\u901a\u5e38\u7ffb\u8bd1\u4e3a\u9ebb\u5c06\u3002",
                ),
                E5InputKind.QUERY,
            )
            assertEquals(2, vectors.size)
            assertTrue(vectors.all { it.size == 384 && E5Pooling.l2Norm(it) in 0.999f..1.001f })
            assertTrue(E5Embedder.cosine(vectors[0], vectors[1]) > 0.85f)
        }
    }
}
