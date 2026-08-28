package com.example.minicpm_v_demo.rag.index

import java.io.File

class HnswIndexManager(
    indexDirectory: File,
    private val appMemoryBudgetBytes: () -> Long,
) {
    private val pathPolicy = HnswIndexPathPolicy(indexDirectory)

    fun pathsFor(corpusKey: EmbeddingCorpusKey): HnswIndexPaths = pathPolicy.pathsFor(corpusKey)

    fun requireManaged(file: File): File = pathPolicy.requireManaged(file)

    fun assess(
        expectedCorpus: EmbeddingCorpusKey,
        metadata: HnswIndexMetadata,
    ): HnswIndexAdmission = HnswIndexAdmissionPolicy.assess(
        expectedCorpus = expectedCorpus,
        metadata = metadata,
        appMemoryBudgetBytes = appMemoryBudgetBytes(),
    )
}
