package com.example.minicpm_v_demo.rag.retrieval

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AnswerabilityModelManifestTest {
    @Test
    fun `current model remains unpinned until a trained package is verified`() {
        assertNull(CurrentAnswerabilityModel.manifest)
    }

    @Test
    fun `manifest requires three unique output indices and bounded input`() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(partialLabelIndex = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(maxTokens = 257)
        }
    }

    @Test
    fun `package verifier requires exact hashes and rejects traversal`() {
        val root = createTempDirectory("answerability-package-").toFile()
        try {
            root.resolve("model.int8.onnx").writeText("model")
            root.resolve("tokenizer.json").writeText("tokenizer")
            val manifest = manifest().copy(
                files = mapOf(
                    "model.int8.onnx" to AnswerabilityModelPackageVerifier.sha256(
                        root.resolve("model.int8.onnx"),
                    ),
                    "tokenizer.json" to AnswerabilityModelPackageVerifier.sha256(
                        root.resolve("tokenizer.json"),
                    ),
                ),
            )

            assertEquals(root.canonicalFile, AnswerabilityModelPackageVerifier.verify(root, manifest))
            assertThrows(IllegalArgumentException::class.java) {
                AnswerabilityModelPackageVerifier.verify(
                    root,
                    manifest.copy(files = manifest.files + ("../escape" to "0".repeat(64))),
                )
            }
            root.resolve("model.int8.onnx").appendText("tampered")
            assertThrows(IllegalArgumentException::class.java) {
                AnswerabilityModelPackageVerifier.verify(root, manifest)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifest() = AnswerabilityModelManifest(
        modelId = "local/bilingual-answerability",
        revision = "fixed-revision",
        maxTokens = 256,
        supportedLabelIndex = 0,
        partialLabelIndex = 1,
        unsupportedLabelIndex = 2,
        files = mapOf("model.int8.onnx" to "a".repeat(64)),
    )
}
