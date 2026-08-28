package com.example.minicpm_v_demo.rag.guard

import java.io.File
import java.security.MessageDigest

data class RagGuardModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(SAFE_NAME.matches(name))
        require(bytes in 1..MAX_MODEL_BYTES)
        require(SHA256.matches(sha256))
    }

    private companion object {
        val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,128}")
        val SHA256 = Regex("[0-9a-f]{64}")
        const val MAX_MODEL_BYTES = 256L * 1024L * 1024L
    }
}

data class RagGuardModelManifest(
    val modelId: String,
    val revision: String,
    val architecture: String,
    val maxTokens: Int,
    val externalTokenizerSha256: String,
    val answerabilityTaskId: Int,
    val groundednessTaskId: Int,
    val answerabilityClassCount: Int,
    val groundednessClassCount: Int,
    val answerabilityPaddingLogit: Float,
    val model: RagGuardModelFile,
) {
    init {
        require(modelId.isNotBlank() && revision.isNotBlank())
        require(architecture == "shared_encoder_three_plus_four_heads")
        require(maxTokens in 1..256)
        require(SHA256.matches(externalTokenizerSha256))
        require(setOf(answerabilityTaskId, groundednessTaskId) == setOf(0, 1))
        require(answerabilityClassCount == 3 && groundednessClassCount == 4)
        require(answerabilityPaddingLogit == -10000f)
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

object CurrentRagGuardModel {
    val PINNED = RagGuardModelManifest(
        modelId = "local/minicpm-rag-guard-v4.2-e5-experimental",
        revision = "df1cca834ff8d37fb286221ed8a9cc67bc7c91ee30e0757913dccd766acf87850",
        architecture = "shared_encoder_three_plus_four_heads",
        maxTokens = 256,
        externalTokenizerSha256 =
            "3396f311d68a8ee4351c0949ab2626543334c5566d7f8ea17b026952ac14d0fe",
        answerabilityTaskId = 0,
        groundednessTaskId = 1,
        answerabilityClassCount = 3,
        groundednessClassCount = 4,
        answerabilityPaddingLogit = -10000f,
        model = RagGuardModelFile(
            name = "model.int8.onnx",
            bytes = 118_171_779L,
            sha256 = "d674ef4ef4fb2b4dce37d43c46eeb4b0e8038eb66da7cde1b568ca78dc45e1c2",
        ),
    )
}

object RagGuardModelPackageVerifier {
    fun verify(root: File, manifest: RagGuardModelManifest): File {
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.isDirectory) { "RAG guard model directory is missing" }
        val model = canonicalRoot.resolve(manifest.model.name).canonicalFile
        require(model.parentFile == canonicalRoot && model.isFile) {
            "RAG guard model file is missing"
        }
        require(model.length() == manifest.model.bytes) { "RAG guard model size mismatch" }
        require(sha256(model) == manifest.model.sha256) { "RAG guard model hash mismatch" }
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
