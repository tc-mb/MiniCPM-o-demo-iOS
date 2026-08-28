package com.example.minicpm_v_demo.rag.embed

/** Process-local holder populated only after Task 8 verifies and opens a signed model package. */
object E5TokenizerRegistry {
    @Volatile private var verified: E5Tokenizer? = null

    fun current(): E5Tokenizer? = verified

    fun installVerified(tokenizer: E5Tokenizer) {
        require(
            tokenizer.modelId.isNotBlank() &&
                SHA256.matches(tokenizer.modelSha256) &&
                SHA256.matches(tokenizer.tokenizerSha256)
        )
        verified = tokenizer
    }

    fun clear() {
        verified = null
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
