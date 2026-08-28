package com.example.minicpm_v_demo.rag.retrieval

import java.io.File
import java.security.MessageDigest

data class AnswerabilityModelManifest(
    val modelId: String,
    val revision: String,
    val maxTokens: Int,
    val supportedLabelIndex: Int,
    val partialLabelIndex: Int,
    val unsupportedLabelIndex: Int,
    val files: Map<String, String>,
) {
    init {
        require(modelId.isNotBlank() && revision.isNotBlank())
        require(maxTokens in 1..256)
        require(
            setOf(supportedLabelIndex, partialLabelIndex, unsupportedLabelIndex) == setOf(0, 1, 2),
        )
        require(files.isNotEmpty())
    }
}

object CurrentAnswerabilityModel {
    // Populated only after the bilingual answerability model clears quality,
    // integrity, and real-device performance gates.
    val manifest: AnswerabilityModelManifest? = null
}

object AnswerabilityModelPackageVerifier {
    private val safeName = Regex("[A-Za-z0-9._-]{1,128}")
    private val sha = Regex("[0-9a-f]{64}")

    fun verify(root: File, manifest: AnswerabilityModelManifest): File {
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory)
        manifest.files.forEach { (name, expectedSha) ->
            require(safeName.matches(name) && sha.matches(expectedSha)) {
                "Invalid answerability model manifest entry"
            }
            val file = canonicalRoot.resolve(name).canonicalFile
            require(file.parentFile == canonicalRoot && file.isFile) {
                "Answerability model file is missing"
            }
            require(sha256(file) == expectedSha) {
                "Answerability model file hash mismatch"
            }
        }
        return canonicalRoot
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
