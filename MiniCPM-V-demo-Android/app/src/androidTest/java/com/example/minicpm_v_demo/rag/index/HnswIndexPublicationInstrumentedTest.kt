package com.example.minicpm_v_demo.rag.index

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnswIndexPublicationInstrumentedTest {
    @Test
    fun publishEncryptsPayloadAuthenticatesMetadataAndLeavesNoPlaintext() {
        val root = testRoot()
        try {
            val key = generatedKey()
            val store = EncryptedFileStore { key }
            val publisher = HnswIndexPublisher(root, store)
            val plaintext = File(root, "candidate.hnsw").apply { writeBytes("native-index-v1".toByteArray()) }
            val metadata = metadata(plaintext, generation = 1)

            val published = publisher.publish(metadata, plaintext)

            assertFalse(plaintext.exists())
            assertTrue(published.encryptedIndex.isFile)
            assertTrue(published.metadata.isFile)
            assertEquals(metadata, publisher.readMetadata(metadata.corpusKey))
            assertArrayEquals(
                "native-index-v1".toByteArray(),
                publisher.withVerifiedPlaintext(metadata.corpusKey) { file -> file.readBytes() },
            )
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") || it.name.endsWith(".plain") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancelledReplacementPreservesThePreviousAuthenticatedGeneration() {
        val root = testRoot()
        try {
            val key = generatedKey()
            val publisher = HnswIndexPublisher(root, EncryptedFileStore { key })
            val firstPlaintext = File(root, "first.hnsw").apply { writeBytes("generation-one".toByteArray()) }
            val first = metadata(firstPlaintext, generation = 1)
            publisher.publish(first, firstPlaintext)

            val replacement = File(root, "replacement.hnsw").apply {
                writeBytes(ByteArray(256 * 1024) { 7 })
            }
            val second = metadata(replacement, generation = 2)
            var checks = 0
            assertThrows(IOException::class.java) {
                publisher.publish(second, replacement) { ++checks < 2 }
            }

            assertFalse(replacement.exists())
            assertEquals(first, publisher.readMetadata(first.corpusKey))
            assertArrayEquals(
                "generation-one".toByteArray(),
                publisher.withVerifiedPlaintext(first.corpusKey) { file -> file.readBytes() },
            )
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") || it.name.endsWith(".plain") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationAfterPayloadPublicationRestoresThePreviousGeneration() {
        val root = testRoot()
        try {
            val key = generatedKey()
            val publisher = HnswIndexPublisher(root, EncryptedFileStore { key })
            val firstPlaintext = File(root, "first-after-payload.hnsw").apply {
                writeBytes("stable-generation".toByteArray())
            }
            val first = metadata(firstPlaintext, generation = 11)
            publisher.publish(first, firstPlaintext)

            val replacement = File(root, "replacement-after-payload.hnsw").apply {
                writeBytes("uncommitted-generation".toByteArray())
            }
            val second = metadata(replacement, generation = 12)
            var checks = 0
            assertThrows(IOException::class.java) {
                publisher.publish(second, replacement) { ++checks < 4 }
            }

            assertEquals(first, publisher.readMetadata(first.corpusKey))
            assertArrayEquals(
                "stable-generation".toByteArray(),
                publisher.withVerifiedPlaintext(first.corpusKey) { file -> file.readBytes() },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nextReadRecoversPersistedPreviousGenerationAfterProcessDeathWindow() {
        val root = testRoot()
        try {
            val key = generatedKey()
            val store = EncryptedFileStore { key }
            val publisher = HnswIndexPublisher(root, store)
            val plaintext = File(root, "process-death-stable.hnsw").apply {
                writeBytes("process-death-stable".toByteArray())
            }
            val metadata = metadata(plaintext, generation = 21)
            val paths = publisher.publish(metadata, plaintext)
            val previousIndex = File(root, "${paths.encryptedIndex.name}.previous")
            val previousMetadata = File(root, "${paths.metadata.name}.previous")
            paths.encryptedIndex.copyTo(previousIndex)
            paths.metadata.copyTo(previousMetadata)

            store.encrypt(
                "process-died-after-this-payload".byteInputStream(),
                paths.encryptedIndex,
            )

            assertArrayEquals(
                "process-death-stable".toByteArray(),
                publisher.withVerifiedPlaintext(metadata.corpusKey) { file -> file.readBytes() },
            )
            assertFalse(previousIndex.exists())
            assertFalse(previousMetadata.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedReadFinalizesCommittedGenerationAfterProcessDeathWindow() {
        val root = testRoot()
        try {
            val key = generatedKey()
            val publisher = HnswIndexPublisher(root, EncryptedFileStore { key })
            val firstPlaintext = File(root, "finalize-first.hnsw").apply {
                writeBytes("finalize-generation-one".toByteArray())
            }
            val firstPaths = publisher.publish(metadata(firstPlaintext, generation = 41), firstPlaintext)
            val previousPayload = firstPaths.encryptedIndex.readBytes()
            val previousMetadata = firstPaths.metadata.readBytes()

            val replacement = File(root, "finalize-replacement.hnsw").apply {
                writeBytes("finalize-generation-two".toByteArray())
            }
            val second = metadata(replacement, generation = 42)
            val currentPaths = publisher.publish(second, replacement)
            val persistedPreviousIndex = File(root, "${currentPaths.encryptedIndex.name}.previous")
                .apply { writeBytes(previousPayload) }
            val persistedPreviousMetadata = File(root, "${currentPaths.metadata.name}.previous")
                .apply { writeBytes(previousMetadata) }

            assertArrayEquals(
                "finalize-generation-two".toByteArray(),
                publisher.withVerifiedPlaintext(second.corpusKey) { file -> file.readBytes() },
            )
            assertFalse(persistedPreviousIndex.exists())
            assertFalse(persistedPreviousMetadata.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readRecoversPreviousWhenMetadataAtomicCommitIsInterrupted() {
        val root = testRoot()
        try {
            val key = generatedKey()
            val store = EncryptedFileStore { key }
            val publisher = HnswIndexPublisher(root, store)
            val stablePlaintext = File(root, "metadata-interruption-stable.hnsw").apply {
                writeBytes("metadata-interruption-stable".toByteArray())
            }
            val stable = metadata(stablePlaintext, generation = 51)
            val paths = publisher.publish(stable, stablePlaintext)
            paths.encryptedIndex.copyTo(File(root, "${paths.encryptedIndex.name}.previous"))
            paths.metadata.copyTo(File(root, "${paths.metadata.name}.previous"))

            store.encrypt(
                "uncommitted-payload".byteInputStream(),
                paths.encryptedIndex,
            )
            val metadataBackup = File(root, "${paths.metadata.name}.bak")
            assertTrue(paths.metadata.renameTo(metadataBackup))
            val incompleteMetadata = File(root, "${paths.metadata.name}.new").apply {
                writeBytes("incomplete metadata".toByteArray())
            }

            val restartedPublisher = HnswIndexPublisher(root, EncryptedFileStore { key })
            assertArrayEquals(
                "metadata-interruption-stable".toByteArray(),
                restartedPublisher.withVerifiedPlaintext(stable.corpusKey) { file -> file.readBytes() },
            )
            assertFalse(metadataBackup.exists())
            assertFalse(incompleteMetadata.exists())
            assertFalse(File(root, "${paths.encryptedIndex.name}.previous").exists())
            assertFalse(File(root, "${paths.metadata.name}.previous").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun concurrentReadWaitsForReplacementPublicationToCommit() {
        val root = testRoot()
        val executor = Executors.newFixedThreadPool(2)
        val releasePublication = CountDownLatch(1)
        try {
            val key = generatedKey()
            val publisher = HnswIndexPublisher(root, EncryptedFileStore { key })
            val readerPublisher = HnswIndexPublisher(root, EncryptedFileStore { key })
            val firstPlaintext = File(root, "concurrent-first.hnsw").apply {
                writeBytes("concurrent-generation-one".toByteArray())
            }
            publisher.publish(metadata(firstPlaintext, generation = 31), firstPlaintext)

            val replacement = File(root, "concurrent-replacement.hnsw").apply {
                writeBytes(ByteArray(256 * 1024) { 9 })
            }
            val second = metadata(replacement, generation = 32)
            val publicationPaused = CountDownLatch(1)
            val continuationChecks = AtomicInteger()
            val publication = executor.submit<HnswIndexPaths> {
                publisher.publish(second, replacement) {
                    if (continuationChecks.incrementAndGet() == 2) {
                        publicationPaused.countDown()
                        check(releasePublication.await(10, TimeUnit.SECONDS))
                    }
                    true
                }
            }
            assertTrue(publicationPaused.await(10, TimeUnit.SECONDS))

            val readCompleted = CountDownLatch(1)
            val read = executor.submit<ByteArray> {
                readerPublisher.withVerifiedPlaintext(second.corpusKey) { file -> file.readBytes() }
                    .also { readCompleted.countDown() }
            }
            assertFalse(readCompleted.await(250, TimeUnit.MILLISECONDS))

            releasePublication.countDown()
            publication.get(10, TimeUnit.SECONDS)
            assertArrayEquals(ByteArray(256 * 1024) { 9 }, read.get(10, TimeUnit.SECONDS))
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperedMetadataIsRejectedByAuthenticatedRead() {
        val root = testRoot()
        try {
            val key = generatedKey()
            val publisher = HnswIndexPublisher(root, EncryptedFileStore { key })
            val plaintext = File(root, "metadata-tamper.hnsw").apply {
                writeBytes("metadata-authentication".toByteArray())
            }
            val metadata = metadata(plaintext, generation = 3)
            val paths = publisher.publish(metadata, plaintext)
            RandomAccessFile(paths.metadata, "rw").use { file ->
                file.seek(file.length() - 1)
                val value = file.read()
                file.seek(file.length() - 1)
                file.write(value xor 1)
            }

            assertThrows(IOException::class.java) {
                publisher.readMetadata(metadata.corpusKey)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testRoot(): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File(context.noBackupFilesDir, "rag/index-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
    }

    private fun metadata(file: File, generation: Long) = HnswIndexMetadata(
        corpusKey = EmbeddingCorpusKey(
            knowledgeBaseIds = listOf("kb-publication"),
            modelSha256 = "0".repeat(64),
            corpusVersion = 1,
            embeddingCount = 6_001,
            maximumUpdatedAt = 10,
            chunkIdSum = 18_009_001,
        ),
        dimension = E5ModelSpec.PINNED.dimension,
        indexGeneration = generation,
        maximumChunkId = 6_001,
        plaintextLength = file.length(),
        plaintextSha256 = HnswIndexIntegrity.sha256(file),
        builtAt = generation,
    )

    private fun generatedKey() = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
