package com.example.minicpm_v_demo.rag.embed

import java.io.File
import java.security.MessageDigest

data class EmbeddingModelManifest(
    val modelId: String,
    val revision: String,
    val dimension: Int,
    val maxTokens: Int,
    val files: Map<String, String>,
) {
    init {
        require(modelId.isNotBlank() && revision.isNotBlank())
        require(dimension > 0 && maxTokens in 1..512)
        require(files.isNotEmpty())
    }
}

object EmbeddingModelPackageVerifier {
    private val safeName = Regex("[A-Za-z0-9._-]{1,128}")
    private val sha = Regex("[0-9a-f]{64}")

    fun verify(root: File, manifest: EmbeddingModelManifest): File {
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory)
        manifest.files.forEach { (name, expectedSha) ->
            require(safeName.matches(name) && sha.matches(expectedSha)) { "Invalid model manifest entry" }
            val file = canonicalRoot.resolve(name).canonicalFile
            require(file.parentFile == canonicalRoot && file.isFile) { "Model file is missing" }
            require(sha256(file) == expectedSha) { "Model file hash mismatch" }
        }
        return canonicalRoot
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
