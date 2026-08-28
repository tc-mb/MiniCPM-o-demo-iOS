package com.example.minicpm_v_demo.rag.work

import com.example.minicpm_v_demo.rag.db.ChunkDao
import com.example.minicpm_v_demo.rag.index.EmbeddingCorpusKey
import com.example.minicpm_v_demo.rag.index.HnswCorpusSource
import com.example.minicpm_v_demo.rag.index.HnswIndexBuildOutcome
import com.example.minicpm_v_demo.rag.index.HnswIndexBuilder
import com.example.minicpm_v_demo.rag.index.HnswIndexPublisher
import java.io.File

enum class HnswRebuildStage {
    READING_CORPUS_STAMP,
    LOADING_EMBEDDING_PAGE,
    BUILDING_INDEX,
    COMPLETED,
}

class HnswRebuildRunner(
    private val chunkDao: ChunkDao,
    indexDirectory: File,
    publisher: HnswIndexPublisher,
) {
    private val builder = HnswIndexBuilder(indexDirectory, publisher)

    suspend fun rebuild(
        input: HnswRebuildInput,
        shouldContinue: () -> Boolean = { true },
        onStage: (HnswRebuildStage) -> Unit = {},
    ): HnswIndexBuildOutcome {
        val source = object : HnswCorpusSource {
            override suspend fun currentKey(): EmbeddingCorpusKey {
                onStage(HnswRebuildStage.READING_CORPUS_STAMP)
                val stamp = chunkDao.findReadyEmbeddingStamp(
                    input.knowledgeBaseIds,
                    input.modelSha256,
                    input.corpusVersion,
                )
                return EmbeddingCorpusKey(
                    knowledgeBaseIds = input.knowledgeBaseIds,
                    modelSha256 = input.modelSha256,
                    corpusVersion = input.corpusVersion,
                    embeddingCount = stamp.embeddingCount,
                    maximumUpdatedAt = stamp.maximumUpdatedAt,
                    chunkIdSum = stamp.chunkIdSum,
                )
            }

            override suspend fun loadPage(offset: Int, pageSize: Int) =
                chunkDao.findReadyEmbeddingsPage(
                    knowledgeBaseIds = input.knowledgeBaseIds,
                    modelSha256 = input.modelSha256,
                    corpusVersion = input.corpusVersion,
                    pageSize = pageSize,
                    offset = offset,
                ).also { onStage(HnswRebuildStage.LOADING_EMBEDDING_PAGE) }
        }

        val expectedCorpus = source.currentKey()
        onStage(HnswRebuildStage.BUILDING_INDEX)
        return builder.build(
            expectedCorpus = expectedCorpus,
            source = source,
            shouldContinue = shouldContinue,
        ).also { onStage(HnswRebuildStage.COMPLETED) }
    }
}
