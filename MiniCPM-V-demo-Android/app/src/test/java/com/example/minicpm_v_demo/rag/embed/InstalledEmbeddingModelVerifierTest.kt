package com.example.minicpm_v_demo.rag.embed

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstalledEmbeddingModelVerifierTest {
    @Test
    fun `package identity is verified without opening an inference session`() {
        val root = Files.createTempDirectory("e5-installed-identity").toFile()
        try {
            val model = root.resolve("model.onnx").apply { writeText("verified model") }
            val manifest = EmbeddingModelManifest(
                modelId = "e5-test",
                revision = "fixed",
                dimension = 384,
                maxTokens = 512,
                files = mapOf("model.onnx" to EmbeddingModelPackageVerifier.sha256(model)),
            )

            assertEquals(
                InstalledEmbeddingModel("e5-test", manifest.files.getValue("model.onnx")),
                InstalledEmbeddingModelVerifier.verify(root, manifest, "model.onnx"),
            )

            model.writeText("tampered")
            assertNull(InstalledEmbeddingModelVerifier.verify(root, manifest, "model.onnx"))
        } finally {
            root.deleteRecursively()
        }
    }
}
