package com.example.minicpm_v_demo.rag.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddingModelManifestTest {
    @Test
    fun `verified package requires every exact hash and rejects traversal`() {
        val root = createTempDir(prefix = "e5-package-")
        try {
            root.resolve("model.onnx").writeText("model")
            root.resolve("tokenizer.onnx").writeText("tokenizer")
            val manifest = EmbeddingModelManifest(
                modelId = "intfloat/multilingual-e5-small",
                revision = "fixed-revision",
                dimension = 384,
                maxTokens = 512,
                files = mapOf(
                    "model.onnx" to sha256(root.resolve("model.onnx")),
                    "tokenizer.onnx" to sha256(root.resolve("tokenizer.onnx")),
                ),
            )

            assertEquals(root.canonicalFile, EmbeddingModelPackageVerifier.verify(root, manifest))
            assertTrue(runCatching {
                EmbeddingModelPackageVerifier.verify(root, manifest.copy(files = manifest.files + ("../escape" to "0".repeat(64))))
            }.isFailure)
            root.resolve("model.onnx").appendText("tampered")
            assertTrue(runCatching { EmbeddingModelPackageVerifier.verify(root, manifest) }.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File) = EmbeddingModelPackageVerifier.sha256(file)
}
