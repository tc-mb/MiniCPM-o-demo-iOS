package com.example.minicpm_v_demo.rag.index

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.rag.crypto.EncryptedFileStore
import com.example.minicpm_v_demo.rag.db.ChunkEmbeddingEntity
import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import com.example.minicpm_v_demo.rag.embed.FloatVectorCodec
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnswScaleBenchmarkInstrumentedTest {
    @Test
    fun deterministicOneFiveAndTwentyThousandVectorBenchmarkMeetsReleaseGate() = runBlocking {
        keepDebugTargetForeground()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.noBackupFilesDir, "rag/hnsw-scale-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val reports = mutableListOf<ScaleReport>()
        try {
            for (size in SCALES) reports += benchmarkScale(root, size)
            val outputDirectory = requireNotNull(context.getExternalFilesDir("benchmarks"))
            File(outputDirectory, OUTPUT_FILE_NAME).writeText(renderJson(reports), Charsets.UTF_8)
            reports.forEach { report ->
                val best = report.hnswRuns.maxBy(HnswRun::recallAt10)
                assertTrue(
                    "Best Recall@10 for ${report.size} vectors was ${best.recallAt10}",
                    best.recallAt10 >= MINIMUM_RECALL_AT_TEN,
                )
                report.productionHnsw?.let { production ->
                    assertTrue(
                        "Production Recall@10 for ${report.size} vectors was ${production.recallAt10}",
                        production.recallAt10 >= MINIMUM_RECALL_AT_TEN,
                    )
                    assertTrue(
                        "Production HNSW P95 for ${report.size} vectors was ${production.p95Ms} ms",
                        production.p95Ms < MAXIMUM_PRODUCTION_HNSW_P95_MS,
                    )
                }
                assertEquals(0, report.activeHandlesAfterClose)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun keepDebugTargetForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/.CheckpointTestHostActivity",
        ).close()
    }

    private suspend fun benchmarkScale(root: File, size: Int): ScaleReport {
        val dimension = E5ModelSpec.PINNED.dimension
        val vectors = deterministicCorpus(size, dimension)
        val modelSha = E5ModelSpec.PINNED.files.getValue("model.int8.onnx")
        val embeddings = vectors.mapIndexed { index, vector ->
            ChunkEmbeddingEntity(
                chunkId = index + 1L,
                modelSha256 = modelSha,
                dimension = dimension,
                vector = FloatVectorCodec.encode(vector),
                updatedAt = index + 1L,
            )
        }
        val source = ListEmbeddingSource(embeddings)
        val corpusKey = EmbeddingCorpusKey(
            knowledgeBaseIds = listOf("benchmark-$size"),
            modelSha256 = modelSha,
            corpusVersion = 1,
            embeddingCount = size,
            maximumUpdatedAt = size.toLong(),
            chunkIdSum = size.toLong() * (size + 1L) / 2L,
        )
        val queries = deterministicQueries(vectors)
        val request = { query: FloatArray -> VectorSearchRequest(corpusKey, query, TOP_K) }
        val pagedExact = ExactVectorSearchBackend(maximumCachedChunks = 1, partitionChunks = 1_000)
        val cacheExact = ExactVectorSearchBackend(maximumCachedChunks = 5_000)
        val exactResults = mutableListOf<Set<Long>>()
        val pagedTimes = mutableListOf<Double>()
        for (query in queries) {
            val start = SystemClock.elapsedRealtimeNanos()
            val result = pagedExact.search(request(query), source).mapTo(linkedSetOf()) { it.chunkId }
            pagedTimes += elapsedMillis(start)
            exactResults += result
            if (size <= 5_000) {
                assertEquals(result, cacheExact.search(request(query), source).mapTo(linkedSetOf()) { it.chunkId })
            }
        }

        val pssBeforeKb = Debug.getPss().toLong()
        val indexFile = File(root, "benchmark-$size.hnsw")
        val buildStarted = SystemClock.elapsedRealtimeNanos()
        HnswIndex.create(
            indexDirectory = root,
            dimension = dimension,
            maximumElements = size,
            m = 16,
            efConstruction = 100,
        ).use { index ->
            vectors.forEachIndexed { vectorIndex, vector -> index.add(vectorIndex + 1L, vector) }
            index.save(indexFile)
        }
        val buildMs = elapsedMillis(buildStarted)
        val pssAfterBuildKb = Debug.getPss().toLong()

        val encryptedFile = File(root, "benchmark-$size.hnsw.enc")
        val plaintextBytes = indexFile.length()
        val encryptionStarted = SystemClock.elapsedRealtimeNanos()
        indexFile.inputStream().buffered().use { input ->
            EncryptedFileStore { generatedKey() }.encrypt(input, encryptedFile)
        }
        val encryptionMs = elapsedMillis(encryptionStarted)

        val loadStarted = SystemClock.elapsedRealtimeNanos()
        val loaded = HnswIndex.load(root, indexFile, dimension, size)
        val loadMs = elapsedMillis(loadStarted)
        val hnswRuns = mutableListOf<HnswRun>()
        loaded.use { index ->
            EF_SEARCH_VALUES.forEach { efSearch ->
                var recalled = 0
                var expected = 0
                val hnswTimes = mutableListOf<Double>()
                queries.forEachIndexed { queryIndex, query ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val approximate = index.search(query, TOP_K, efSearch)
                        .mapTo(mutableSetOf()) { it.chunkId }
                    hnswTimes += elapsedMillis(start)
                    recalled += exactResults[queryIndex].intersect(approximate).size
                    expected += exactResults[queryIndex].size
                }
                hnswRuns += HnswRun(
                    efSearch = efSearch,
                    recallAt10 = recalled.toDouble() / expected.toDouble(),
                    p50Ms = percentile(hnswTimes, 0.50),
                    p95Ms = percentile(hnswTimes, 0.95),
                )
            }
        }

        val productionRun = if (size > 5_000) {
            val candidate = File(root, "production-$size.hnsw")
            indexFile.copyTo(candidate)
            val publicationKey = generatedKey()
            val publisher = HnswIndexPublisher(root, EncryptedFileStore { publicationKey })
            val metadata = HnswIndexMetadata(
                corpusKey = corpusKey,
                dimension = dimension,
                indexGeneration = size.toLong(),
                maximumChunkId = size.toLong(),
                plaintextLength = candidate.length(),
                plaintextSha256 = HnswIndexIntegrity.sha256(candidate),
                builtAt = size.toLong(),
            )
            publisher.publish(metadata, candidate)
            source.resetCounters()
            val backend = HnswVectorSearchBackend(
                indexDirectory = root,
                publisher = publisher,
                appMemoryBudgetBytes = { Long.MAX_VALUE },
            )
            var recalled = 0
            var expected = 0
            val times = mutableListOf<Double>()
            queries.forEachIndexed { queryIndex, query ->
                val start = SystemClock.elapsedRealtimeNanos()
                val approximate = backend.search(request(query), source)
                    .mapTo(mutableSetOf()) { it.chunkId }
                times += elapsedMillis(start)
                recalled += exactResults[queryIndex].intersect(approximate).size
                expected += exactResults[queryIndex].size
            }
            check(source.loadAllCalls == 0 && source.loadPageCalls == 0) {
                "Production HNSW unexpectedly used exact-vector reads"
            }
            HnswRun(
                efSearch = HnswSearchPolicy.DEFAULT_EF_SEARCH,
                recallAt10 = recalled.toDouble() / expected.toDouble(),
                p50Ms = percentile(times, 0.50),
                p95Ms = percentile(times, 0.95),
            )
        } else {
            null
        }

        return ScaleReport(
            size = size,
            queryCount = queries.size,
            pagedExactP50Ms = percentile(pagedTimes, 0.50),
            pagedExactP95Ms = percentile(pagedTimes, 0.95),
            hnswRuns = hnswRuns,
            productionHnsw = productionRun,
            buildMs = buildMs,
            loadMs = loadMs,
            encryptionMs = encryptionMs,
            plaintextBytes = plaintextBytes,
            encryptedBytes = encryptedFile.length(),
            pssDeltaBuildKb = (pssAfterBuildKb - pssBeforeKb).coerceAtLeast(0),
            activeHandlesAfterClose = HnswIndex.activeNativeHandleCountForDebug(),
        )
    }

    private fun deterministicCorpus(size: Int, dimension: Int): List<FloatArray> {
        val random = Random(0x5EED + size)
        val clusterBases = List((size + CLUSTER_SIZE - 1) / CLUSTER_SIZE) {
            normalized(FloatArray(dimension) { random.nextFloat() * 2f - 1f })
        }
        val vectors = ArrayList<FloatArray>(size)
        repeat(size) { index ->
            if (index > 0 && index % TIE_INTERVAL == 1) {
                vectors += vectors.last().copyOf()
                return@repeat
            }
            val base = clusterBases[index / CLUSTER_SIZE]
            vectors += normalized(
                FloatArray(dimension) { column ->
                    base[column] + (random.nextFloat() - 0.5f) * CLUSTER_NOISE
                },
            )
        }
        return vectors
    }

    private fun deterministicQueries(vectors: List<FloatArray>): List<FloatArray> {
        val random = Random(0xC0FFEE + vectors.size)
        return List(QUERY_COUNT) { queryIndex ->
            val source = vectors[(queryIndex * 1543 + 17) % vectors.size]
            normalized(
                FloatArray(source.size) { column ->
                    source[column] + (random.nextFloat() - 0.5f) * QUERY_NOISE
                },
            )
        }
    }

    private fun normalized(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { value -> value.toDouble() * value.toDouble() }).toFloat()
        return FloatArray(values.size) { index -> values[index] / norm }
    }

    private fun elapsedMillis(startNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

    private fun percentile(values: List<Double>, quantile: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * quantile).toInt()]
    }

    private fun renderJson(reports: List<ScaleReport>): String = buildString {
        append("{\n")
        append("  \"device\": \"").append(Build.MODEL).append("\",\n")
        append("  \"androidApi\": ").append(Build.VERSION.SDK_INT).append(",\n")
        append("  \"dimension\": ").append(E5ModelSpec.PINNED.dimension).append(",\n")
        append("  \"topK\": ").append(TOP_K).append(",\n")
        append("  \"efSearchValues\": ").append(EF_SEARCH_VALUES).append(",\n")
        append("  \"results\": [\n")
        reports.forEachIndexed { index, report ->
            append("    ").append(report.toJson())
            if (index != reports.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    private class ListEmbeddingSource(
        val embeddings: List<ChunkEmbeddingEntity>,
    ) : VectorEmbeddingSource {
        var loadAllCalls: Int = 0
            private set
        var loadPageCalls: Int = 0
            private set

        override suspend fun loadAll(): List<ChunkEmbeddingEntity> = embeddings.also { loadAllCalls++ }

        override suspend fun loadPage(offset: Int, pageSize: Int): List<ChunkEmbeddingEntity> =
            (if (offset >= embeddings.size) emptyList()
            else embeddings.subList(offset, minOf(offset + pageSize, embeddings.size))).also {
                loadPageCalls++
            }

        fun resetCounters() {
            loadAllCalls = 0
            loadPageCalls = 0
        }
    }

    private data class ScaleReport(
        val size: Int,
        val queryCount: Int,
        val pagedExactP50Ms: Double,
        val pagedExactP95Ms: Double,
        val hnswRuns: List<HnswRun>,
        val productionHnsw: HnswRun?,
        val buildMs: Double,
        val loadMs: Double,
        val encryptionMs: Double,
        val plaintextBytes: Long,
        val encryptedBytes: Long,
        val pssDeltaBuildKb: Long,
        val activeHandlesAfterClose: Int,
    ) {
        fun toJson(): String = listOf(
            "\"size\":$size",
            "\"queryCount\":$queryCount",
            "\"pagedExactP50Ms\":$pagedExactP50Ms",
            "\"pagedExactP95Ms\":$pagedExactP95Ms",
            "\"hnswRuns\":[${hnswRuns.joinToString { it.toJson() }}]",
            "\"productionHnsw\":${productionHnsw?.toJson() ?: "null"}",
            "\"buildMs\":$buildMs",
            "\"loadMs\":$loadMs",
            "\"encryptionMs\":$encryptionMs",
            "\"plaintextBytes\":$plaintextBytes",
            "\"encryptedBytes\":$encryptedBytes",
            "\"pssDeltaBuildKb\":$pssDeltaBuildKb",
            "\"activeHandlesAfterClose\":$activeHandlesAfterClose",
        ).joinToString(prefix = "{", postfix = "}")
    }

    private data class HnswRun(
        val efSearch: Int,
        val recallAt10: Double,
        val p50Ms: Double,
        val p95Ms: Double,
    ) {
        fun toJson(): String =
            "{\"efSearch\":$efSearch,\"recallAt10\":$recallAt10," +
                "\"p50Ms\":$p50Ms,\"p95Ms\":$p95Ms}"
    }

    private fun generatedKey() = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }

    private companion object {
        val SCALES = listOf(1_000, 5_000, 20_000)
        const val TOP_K = 10
        val EF_SEARCH_VALUES = listOf(48, 64, 96, 128, 256, 512)
        const val QUERY_COUNT = 12
        const val CLUSTER_SIZE = 50
        const val TIE_INTERVAL = 997
        const val CLUSTER_NOISE = 0.035f
        const val QUERY_NOISE = 0.008f
        const val MINIMUM_RECALL_AT_TEN = 0.95
        const val MAXIMUM_PRODUCTION_HNSW_P95_MS = 300.0
        const val OUTPUT_FILE_NAME = "hnsw-scale-benchmark.json"
    }
}
