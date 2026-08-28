package com.example.minicpm_v_demo.rag.index

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnswIndexBuilderInstrumentedTest {
    @Test
    fun frozenCorpusBuildsAndPublishesAnAuthenticatedIndex() = runBlocking {
        val fixture = fixture()
        try {
            val key = corpusKey(count = 3, updatedAt = 10)
            val source = FakeSource(key, embeddings(3))

            val outcome = fixture.builder.build(key, source)

            assertTrue(outcome is HnswIndexBuildOutcome.Published)
            val metadata = fixture.publisher.readMetadata(key)
            assertEquals(3, metadata.corpusKey.embeddingCount)
            assertEquals(3, metadata.maximumChunkId)
            fixture.publisher.withVerifiedPlaintext(key) { plaintext ->
                HnswIndex.load(fixture.root, plaintext, E5ModelSpec.PINNED.dimension, 3).use { index ->
                    assertEquals(1L, index.search(unitVector(0), 1, 8).single().chunkId)
                }
            }
            assertFalse(fixture.root.walkTopDown().any { it.name.endsWith(".plain") })
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun changedCorpusDiscardsCandidateWithoutPublishing() = runBlocking {
        val fixture = fixture()
        try {
            val expected = corpusKey(count = 3, updatedAt = 10)
            val changed = corpusKey(count = 3, updatedAt = 11)
            val source = FakeSource(expected, embeddings(3), finalKey = changed)

            val outcome = fixture.builder.build(expected, source)

            assertEquals(HnswIndexBuildOutcome.StaleCorpus, outcome)
            val paths = HnswIndexManager(fixture.root) { Long.MAX_VALUE }.pathsFor(expected)
            assertFalse(paths.encryptedIndex.exists())
            assertFalse(paths.metadata.exists())
            assertFalse(fixture.root.walkTopDown().any { it.name.endsWith(".hnsw") || it.name.endsWith(".plain") })
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    @Test
    fun multiKnowledgeBaseCorpusBuildsOneSearchableGeneration() = runBlocking {
        val fixture = fixture()
        try {
            val key = corpusKey(
                count = 4,
                updatedAt = 20,
                knowledgeBaseIds = listOf("kb-a", "kb-b"),
            )
            val source = FakeSource(key, embeddings(4))

            assertTrue(fixture.builder.build(key, source) is HnswIndexBuildOutcome.Published)
            assertEquals(listOf("kb-a", "kb-b"), fixture.publisher.readMetadata(key).corpusKey.knowledgeBaseIds)
            fixture.publisher.withVerifiedPlaintext(key) { plaintext ->
                HnswIndex.load(fixture.root, plaintext, E5ModelSpec.PINNED.dimension, 4).use { index ->
                    assertEquals(4L, index.search(unitVector(3), 1, 8).single().chunkId)
                }
            }
        } finally {
            fixture.root.deleteRecursively()
        }
    }

    private fun fixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "rag/index-builder-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val key = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
        val publisher = HnswIndexPublisher(root, EncryptedFileStore { key })
        return Fixture(
            root,
            publisher,
            HnswIndexBuilder(root, publisher, minimumEmbeddingCount = 3, pageSize = 2),
        )
    }

    private fun embeddings(count: Int) = (1..count).map { id ->
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

    private fun corpusKey(
        count: Int,
        updatedAt: Long,
        knowledgeBaseIds: List<String> = listOf("kb-builder"),
    ) = EmbeddingCorpusKey(
        knowledgeBaseIds = knowledgeBaseIds,
        modelSha256 = "0".repeat(64),
        corpusVersion = 1,
        embeddingCount = count,
        maximumUpdatedAt = updatedAt,
        chunkIdSum = count.toLong() * (count + 1L) / 2L,
    )

    private class FakeSource(
        private val initialKey: EmbeddingCorpusKey,
        private val values: List<ChunkEmbeddingEntity>,
        private val finalKey: EmbeddingCorpusKey = initialKey,
    ) : HnswCorpusSource {
        private var keyReads = 0

        override suspend fun currentKey(): EmbeddingCorpusKey =
            if (keyReads++ == 0) initialKey else finalKey

        override suspend fun loadPage(offset: Int, pageSize: Int): List<ChunkEmbeddingEntity> =
            values.drop(offset).take(pageSize)
    }

    private data class Fixture(
        val root: File,
        val publisher: HnswIndexPublisher,
        val builder: HnswIndexBuilder,
    )
}
