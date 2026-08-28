package com.example.minicpm_v_demo.rag.index

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.crypto.RagTempFileCleaner
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Properties
import java.util.concurrent.CountDownLatch
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two-process test: the host force-stops the package after ready.marker appears. */
@RunWith(AndroidJUnit4::class)
class HnswForceStopRecoveryInstrumentedTest {
    @Test
    fun stageBuildPlaintextForForceStop(): Unit {
        val root = freshRoot()
        root.resolve(BUILD_CANDIDATE).writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() })
        persistMarker(root, Scenario.BUILD.name)
        awaitForceStop()
    }

    @Test
    fun verifyBuildPlaintextCleanupAfterForceStop() {
        val root = existingRoot()
        assertEquals(Scenario.BUILD.name, root.resolve(MARKER).readText())
        assertTrue(root.resolve(BUILD_CANDIDATE).isFile)
        assertTrue(RagTempFileCleaner.cleanupHnswPlaintext(root, System.currentTimeMillis()))
        assertFalse(root.resolve(BUILD_CANDIDATE).exists())
        deleteTestRoot(root)
    }

    @Test
    fun stagePublicationForForceStop(): Unit {
        val scenario = requestedScenario()
        require(scenario != Scenario.BUILD)
        val root = freshRoot()
        val keyBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        root.resolve(KEY_FILE).writeBytes(keyBytes)
        val publisher = publisher(root, keyBytes)
        val corpusKey = corpusKey()

        val stable = candidate(root, "hnsw-build-stable.hnsw", STABLE_SIZE, 17)
        val stableMetadata = metadata(corpusKey, stable, generation = 1)
        publisher.publish(stableMetadata, stable)

        val replacement = candidate(root, "hnsw-build-replacement.hnsw", REPLACEMENT_SIZE, 83)
        val replacementMetadata = metadata(corpusKey, replacement, generation = 2)
        Properties().apply {
            setProperty("scenario", scenario.name)
            setProperty("stableSha256", stableMetadata.plaintextSha256)
            setProperty("replacementSha256", replacementMetadata.plaintextSha256)
        }.store(root.resolve(STATE_FILE).outputStream(), "HNSW force-stop aggregate state")

        var continuationChecks = 0
        publisher.publish(
            metadata = replacementMetadata,
            plaintextIndex = replacement,
            shouldContinue = {
                continuationChecks += 1
                if (scenario == Scenario.MID_PAYLOAD_ENCRYPTION && continuationChecks == 3) {
                    persistMarker(root, scenario.name)
                    awaitForceStop()
                }
                true
            },
            onStage = { stage ->
                val shouldBlock =
                    (scenario == Scenario.AFTER_PAYLOAD_PUBLISH && stage == HnswPublicationStage.PAYLOAD_PUBLISHED) ||
                        (scenario == Scenario.AFTER_METADATA_PUBLISH && stage == HnswPublicationStage.METADATA_PUBLISHED)
                if (shouldBlock) {
                    persistMarker(root, scenario.name)
                    awaitForceStop()
                }
            },
        )
        error("Publication scenario completed before host force-stop")
    }

    @Test
    fun verifyPublicationRecoveryAfterForceStop() {
        val root = existingRoot()
        val state = Properties().apply { root.resolve(STATE_FILE).inputStream().use(::load) }
        val scenario = Scenario.valueOf(state.getProperty("scenario"))
        assertEquals(scenario.name, root.resolve(MARKER).readText())
        val keyBytes = root.resolve(KEY_FILE).readBytes()
        val recoveredHash = publisher(root, keyBytes).withVerifiedPlaintext(corpusKey()) { plaintext ->
            HnswIndexIntegrity.sha256(plaintext)
        }
        val expectedHash = if (scenario == Scenario.AFTER_METADATA_PUBLISH) {
            state.getProperty("replacementSha256")
        } else {
            state.getProperty("stableSha256")
        }
        assertEquals(expectedHash, recoveredHash)

        RagTempFileCleaner.cleanupHnswPlaintext(root, System.currentTimeMillis())
        assertTrue(
            root.listFiles().orEmpty().none { file ->
                file.name.endsWith(".previous") ||
                    file.name.endsWith(".bak") ||
                    file.name.endsWith(".new") ||
                    file.name.endsWith(".plain") ||
                    file.name.startsWith("hnsw-build-")
            },
        )
        deleteTestRoot(root)
    }

    private fun requestedScenario(): Scenario {
        val raw = InstrumentationRegistry.getArguments().getString("scenario")
        return Scenario.valueOf(requireNotNull(raw) { "Missing scenario argument" })
    }

    private fun freshRoot(): File = testRoot().apply {
        deleteTestRoot(this)
        check(mkdirs() && isDirectory)
    }

    private fun existingRoot(): File = testRoot().also { root ->
        check(root.isDirectory) { "HNSW force-stop test root is missing" }
    }

    private fun testRoot(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return File(context.noBackupFilesDir, "rag/hnsw-force-stop-test").canonicalFile.also { root ->
            val allowedParent = File(context.noBackupFilesDir, "rag").canonicalFile
            check(root.parentFile == allowedParent) { "Unsafe HNSW force-stop test root" }
        }
    }

    private fun deleteTestRoot(root: File) {
        check(root.name == "hnsw-force-stop-test" && root.parentFile?.name == "rag")
        if (root.exists()) check(root.deleteRecursively())
    }

    private fun publisher(root: File, keyBytes: ByteArray) = HnswIndexPublisher(
        root,
        EncryptedFileStore { SecretKeySpec(keyBytes, "AES") },
    )

    private fun corpusKey() = EmbeddingCorpusKey(
        knowledgeBaseIds = listOf("force-stop-kb"),
        modelSha256 = "a".repeat(64),
        corpusVersion = 1,
        embeddingCount = 5_001,
        maximumUpdatedAt = 1,
        chunkIdSum = 12_507_501,
    )

    private fun candidate(root: File, name: String, size: Int, seed: Int): File =
        root.resolve(name).apply { writeBytes(ByteArray(size) { ((it + seed) % 251).toByte() }) }

    private fun metadata(key: EmbeddingCorpusKey, file: File, generation: Long) = HnswIndexMetadata(
        corpusKey = key,
        dimension = 384,
        indexGeneration = generation,
        maximumChunkId = 5_001,
        plaintextLength = file.length(),
        plaintextSha256 = HnswIndexIntegrity.sha256(file),
        builtAt = generation,
    )

    private fun persistMarker(root: File, value: String) {
        FileOutputStream(root.resolve(MARKER)).use { output ->
            output.write(value.toByteArray())
            output.fd.sync()
        }
    }

    private fun awaitForceStop(): Nothing {
        CountDownLatch(1).await()
        error("Unreachable")
    }

    private enum class Scenario {
        BUILD,
        MID_PAYLOAD_ENCRYPTION,
        AFTER_PAYLOAD_PUBLISH,
        AFTER_METADATA_PUBLISH,
    }

    private companion object {
        const val MARKER = "ready.marker"
        const val STATE_FILE = "state.properties"
        const val KEY_FILE = "test-key.bin"
        const val BUILD_CANDIDATE = "hnsw-build-force-stop.hnsw"
        const val STABLE_SIZE = 128 * 1024
        const val REPLACEMENT_SIZE = 512 * 1024
    }
}
