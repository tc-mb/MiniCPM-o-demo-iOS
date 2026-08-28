package com.example.minicpm_v_demo.rag.work

data class TokenizerIdentity(
    val modelId: String,
    val modelSha256: String,
    val tokenizerSha256: String,
)

enum class ChunkPrerequisiteDecision(val recoverable: Boolean) {
    READY(false),
    MODEL_REQUIRED(true),
    TOKENIZER_MISMATCH(false),
}

object ChunkWorkPolicy {
    fun decide(
        tokenizer: TokenizerIdentity?,
        expectedModelId: String,
        expectedModelSha256: String,
    ): ChunkPrerequisiteDecision = when {
        tokenizer == null -> ChunkPrerequisiteDecision.MODEL_REQUIRED
        tokenizer.modelId != expectedModelId || tokenizer.modelSha256 != expectedModelSha256 ||
            !SHA256.matches(tokenizer.tokenizerSha256) ->
            ChunkPrerequisiteDecision.TOKENIZER_MISMATCH
        else -> ChunkPrerequisiteDecision.READY
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
