package com.example.minicpm_v_demo.rag.retrieval

class LazyAnswerabilityClassifier(
    private val opener: () -> AnswerabilityClassifier?,
) : AnswerabilityClassifier {
    @Volatile
    private var opened: AnswerabilityClassifier? = null

    override suspend fun classify(
        question: String,
        sources: List<RetrievedChunk>,
    ): AnswerabilityVerdict = delegate().classify(question, sources)

    private fun delegate(): AnswerabilityClassifier {
        opened?.let { return it }
        return synchronized(this) {
            opened ?: checkNotNull(opener()) {
                "Verified RAG guard model is unavailable"
            }.also { opened = it }
        }
    }
}
