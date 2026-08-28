package com.example.minicpm_v_demo.rag.guard

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RagGuardModelManagerTest {
    @Test
    fun `manager opens once caches the classifier and closes it`() {
        val root = createTempDirectory("rag-guard-manager-").toFile()
        var opens = 0
        var closed = false
        val classifier = OnnxRagGuardClassifier.forTest(
            CurrentRagGuardModel.PINNED,
            encode = { longArrayOf(0, 2) },
            infer = { _, _, _ -> floatArrayOf(1f, 0f, -1f) },
            closeAction = { closed = true },
        )
        try {
            val manager = RagGuardModelManager.forTest(root) {
                opens += 1
                classifier
            }

            assertSame(classifier, manager.openInstalled())
            assertSame(classifier, manager.openInstalled())
            assertTrue(opens == 1)
            manager.close()
            assertTrue(closed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing directory remains unavailable without invoking opener`() {
        val missing = File(createTempDirectory("rag-guard-missing-").toFile(), "absent")
        var opened = false
        val manager = RagGuardModelManager.forTest(missing) {
            opened = true
            error("must not open")
        }

        assertNull(manager.openInstalled())
        assertTrue(!opened)
    }
}
