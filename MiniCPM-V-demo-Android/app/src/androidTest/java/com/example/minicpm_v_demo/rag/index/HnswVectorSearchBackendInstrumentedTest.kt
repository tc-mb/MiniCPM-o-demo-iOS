package com.example.minicpm_v_demo.rag.index

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import com.example.minicpm_v_demo.rag.retrieval.RankedChunkId
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnswVectorSearchBackendInstrumentedTest {
    @Test
    fun validSidecarBypassesExactEmbeddingReads() = runBlocking {
        val fixture = fixture()
        try {
            fixture.buildPublishedIndex()
            val source = CountingSource(fixture.embeddings)

            val results = fixture.backend.search(
                VectorSearchRequest(fixture.corpusKey, fixture.unitVector(0), 1),
                source,
            )

            assertEquals(1L, results.single().chunkId)
            assertEquals(0, source.reads)
            assertEquals(0, fixture.fallback.searches)
            assertFalse(fixture.root.walkTopDown().any { it.name.endsWith(".plain") })
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun corruptSidecarFallsBackToExactSearch() = runBlocking {
        val fixture = fixture()
        try {
            fixture.buildPublishedIndex()
            val encrypted = HnswIndexManager(fixture.root) { Long.MAX_VALUE }
                .pathsFor(fixture.corpusKey).encryptedIndex
            RandomAccessFile(encrypted, "rw").use { file ->
                file.seek(file.length() - 1)
                val value = file.read()
                file.seek(file.length() - 1)
                file.write(value xor 1)
            }
            val source = CountingSource(fixture.embeddings)

            val results = fixture.backend.search(
                VectorSearchRequest(fixture.corpusKey, fixture.unitVector(0), 1),
                source,
            )

            assertEquals(999L, results.single().chunkId)
            assertEquals(1, fixture.fallback.searches)
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "rag/hnsw-backend-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val key = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
        val store = EncryptedFileStore { key }
        val publisher = HnswIndexPublisher(root, store)
        val fallback = FakeFallback()
        val corpusKey = corpusKey()
        val embeddings = embeddings()
        return Fixture(
            root = root,
            publisher = publisher,
            corpusKey = corpusKey,
            embeddings = embeddings,
            fallback = fallback,
            backend = HnswVectorSearchBackend(
                indexDirectory = root,
                publisher = publisher,
                appMemoryBudgetBytes = { Long.MAX_VALUE },
                exactFallback = fallback,
                minimumEmbeddingCount = 3,
                efSearch = 8,
            ),
        )
    }

    private fun corpusKey() = EmbeddingCorpusKey(
        knowledgeBaseIds = listOf("kb-backend"),
        modelSha256 = "0".repeat(64),
        corpusVersion = 1,
        embeddingCount = 3,
        maximumUpdatedAt = 10,
        chunkIdSum = 6,
    )

    private fun embeddings() = (1..3).map { id ->
        ChunkEmbeddingEntity(
            chunkId = id.toLong(),
            modelSha256 = "0".repeat(64),
            dimension = E5ModelSpec.PINNED.dimension,
            vector = FloatVectorCodec.encode(unitVector(id - 1)),
            updatedAt = 10,
        )
    }

    private fun unitVector(index: Int) = FloatArray(E5ModelSpec.PINNED.dimension).apply {
        this[index] = 1f
    }

    private class CountingSource(private val values: List<ChunkEmbeddingEntity>) : VectorEmbeddingSource {
        var reads = 0

        override suspend fun loadAll(): List<ChunkEmbeddingEntity> {
            reads++
            return values
        }

        override suspend fun loadPage(offset: Int, pageSize: Int): List<ChunkEmbeddingEntity> {
            reads++
            return values.drop(offset).take(pageSize)
        }
    }

    private class FakeFallback : VectorSearchBackend {
        var searches = 0

        override suspend fun search(
            request: VectorSearchRequest,
            source: VectorEmbeddingSource,
        ): List<RankedChunkId> {
            searches++
            return listOf(RankedChunkId(999, 0.5f))
        }
    }

    private data class Fixture(
        val root: File,
        val publisher: HnswIndexPublisher,
        val corpusKey: EmbeddingCorpusKey,
        val embeddings: List<ChunkEmbeddingEntity>,
        val fallback: FakeFallback,
        val backend: HnswVectorSearchBackend,
    ) {
        suspend fun buildPublishedIndex() {
            val source = object : HnswCorpusSource {
                override suspend fun currentKey() = corpusKey
                override suspend fun loadPage(offset: Int, pageSize: Int) =
                    embeddings.drop(offset).take(pageSize)
            }
            HnswIndexBuilder(root, publisher, minimumEmbeddingCount = 3, pageSize = 2)
                .build(corpusKey, source)
        }

        fun unitVector(index: Int) = FloatArray(E5ModelSpec.PINNED.dimension).apply {
            this[index] = 1f
        }
    }
}
