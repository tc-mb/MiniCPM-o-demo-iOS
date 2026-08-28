package com.example.minicpm_v_demo.rag.guard

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RagGuardModelManifestTest {
    @Test
    fun `pinned manifest matches the exported dual-head package`() {
        val manifest = CurrentRagGuardModel.PINNED

        assertEquals(256, manifest.maxTokens)
        assertEquals(0, manifest.answerabilityTaskId)
        assertEquals(1, manifest.groundednessTaskId)
        assertEquals("shared_encoder_three_plus_four_heads", manifest.architecture)
        assertEquals(3, manifest.answerabilityClassCount)
        assertEquals(4, manifest.groundednessClassCount)
        assertEquals(-10000f, manifest.answerabilityPaddingLogit)
        assertEquals(118_171_779L, manifest.model.bytes)
        assertEquals(
            "d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2",
            manifest.model.sha256,
        )
        assertEquals(
            "3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe",
            manifest.externalTokenizerSha256,
        )
    }

    @Test
    fun `verifier enforces exact size hash and canonical child path`() {
        val root = createTempDirectory("rag-guard-package-").toFile()
        try {
            val model = root.resolve("model.int8.onnx").apply { writeText("guard") }
            val manifest = CurrentRagGuardModel.PINNED.copy(
                model = RagGuardModelFile(
                    name = model.name,
                    bytes = model.length(),
                    sha256 = RagGuardModelPackageVerifier.sha256(model),
                ),
            )

            assertEquals(root.canonicalFile, RagGuardModelPackageVerifier.verify(root, manifest))
            assertThrows(IllegalArgumentException::class.java) {
                RagGuardModelPackageVerifier.verify(
                    root,
                    manifest.copy(model = manifest.model.copy(name = "../model.int8.onnx")),
                )
            }
            model.appendText("tampered")
            assertThrows(IllegalArgumentException::class.java) {
                RagGuardModelPackageVerifier.verify(root, manifest)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
