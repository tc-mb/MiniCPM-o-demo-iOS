package com.example.minicpm_v_demo.rag.route

enum class RagQueryRoute {
    NO_RETRIEVAL,
    SINGLE_RETRIEVAL,
    COMPLEX_RETRIEVAL,
}

data class RagRouteInput(
    val ragEnabled: Boolean,
    val query: String,
    val knownDocumentNames: List<String>,
)

fun interface RagQueryRouter {
    fun route(input: RagRouteInput): RagQueryRoute
}

class DefaultRagQueryRouter : RagQueryRouter {
    override fun route(input: RagRouteInput): RagQueryRoute {
        if (!input.ragEnabled) return RagQueryRoute.NO_RETRIEVAL

        val features = RagQueryFeatureExtractor.extract(
            query = input.query,
            knownDocumentNames = input.knownDocumentNames,
        )
        if (features.normalizedQuery.isBlank()) return RagQueryRoute.NO_RETRIEVAL
        if (features.hasComplexAnchor) return RagQueryRoute.COMPLEX_RETRIEVAL
        if (features.hasKnowledgeAnchor) return RagQueryRoute.SINGLE_RETRIEVAL
        if (features.isSelfContainedRequest) return RagQueryRoute.NO_RETRIEVAL
        return RagQueryRoute.SINGLE_RETRIEVAL
    }
}
