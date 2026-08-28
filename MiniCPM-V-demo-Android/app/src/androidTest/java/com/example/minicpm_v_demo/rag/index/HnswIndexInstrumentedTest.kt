package com.example.minicpm_v_demo.rag.index

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnswIndexInstrumentedTest {
    @Test
    fun createAddSearchSaveLoadAndClose() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "hnsw-native-test",
        ).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val saved = File(root, "round-trip.hnsw")

        HnswIndex.create(
            indexDirectory = root,
            dimension = 3,
            maximumElements = 4,
            m = 2,
            efConstruction = 16,
        ).use { index ->
            index.add(11, floatArrayOf(4f, 0f, 0f))
            index.add(22, floatArrayOf(0f, 2f, 0f))
            index.add(33, floatArrayOf(0f, 0f, 1f))

            assertEquals(
                listOf(11L, 22L),
                index.search(floatArrayOf(9f, 1f, 0f), topK = 2, efSearch = 8)
                    .map { it.chunkId },
            )
            index.save(saved)
        }

        HnswIndex.load(
            indexDirectory = root,
            indexFile = saved,
            dimension = 3,
            maximumElements = 4,
        ).use { restored ->
            assertEquals(
                33L,
                restored.search(floatArrayOf(0f, 0f, 5f), topK = 1, efSearch = 8).single().chunkId,
            )
        }
    }

    @Test
    fun invalidInputsDuplicatesAndClosedHandlesAreRejected() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "hnsw-native-invalid-test",
        ).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val index = HnswIndex.create(root, dimension = 3, maximumElements = 2, m = 2, efConstruction = 8)

        assertThrows(IllegalArgumentException::class.java) {
            index.add(-1, floatArrayOf(1f, 0f, 0f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            index.add(1, floatArrayOf(Float.NaN, 0f, 0f))
        }
        index.add(1, floatArrayOf(1f, 0f, 0f))
        assertThrows(IllegalArgumentException::class.java) {
            index.add(1, floatArrayOf(1f, 0f, 0f))
        }

        index.close()
        index.close()
        assertThrows(IllegalStateException::class.java) {
            index.search(floatArrayOf(1f, 0f, 0f), topK = 1, efSearch = 8)
        }
    }

    @Test
    fun corruptedFilesWrongDimensionsAndEscapingPathsAreRejected() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "hnsw-native-corruption-test",
        ).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val saved = File(root, "valid.hnsw")
        HnswIndex.create(root, dimension = 3, maximumElements = 2, m = 2, efConstruction = 8).use { index ->
            index.add(7, floatArrayOf(1f, 0f, 0f))
            index.save(saved)
        }
        val truncated = File(root, "truncated.hnsw").apply {
            writeBytes(saved.readBytes().copyOf(saved.length().toInt() - 1))
        }

        assertThrows(IOException::class.java) {
            HnswIndex.load(root, truncated, dimension = 3, maximumElements = 2)
        }
        assertThrows(IOException::class.java) {
            HnswIndex.load(root, saved, dimension = 4, maximumElements = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnswIndex.load(root, File(root, "../outside.hnsw"), dimension = 3, maximumElements = 2)
        }
    }

    @Test
    fun equalScoresUseChunkIdOrderingBeforeTopKIsCut() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "hnsw-native-tie-test",
        ).apply {
            deleteRecursively()
            check(mkdirs())
        }

        HnswIndex.create(root, dimension = 3, maximumElements = 3, m = 2, efConstruction = 8).use { index ->
            index.add(30, floatArrayOf(1f, 0f, 0f))
            index.add(10, floatArrayOf(1f, 0f, 0f))
            index.add(20, floatArrayOf(1f, 0f, 0f))

            assertEquals(
                listOf(10L, 20L),
                index.search(floatArrayOf(1f, 0f, 0f), topK = 2, efSearch = 8).map { it.chunkId },
            )
        }
    }

    @Test
    fun concurrentSearchAndCloseNeverUsesFreedNativeMemory() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "hnsw-native-close-race-test",
        ).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val index = HnswIndex.create(root, dimension = 3, maximumElements = 64, m = 4, efConstruction = 16)
        repeat(64) { id ->
            index.add(id.toLong(), floatArrayOf(1f, id.toFloat() + 1f, 0.5f))
        }
        val started = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val searcher = Thread {
            started.countDown()
            repeat(200) {
                try {
                    index.search(floatArrayOf(1f, 2f, 0.5f), topK = 5, efSearch = 16)
                } catch (_: IllegalStateException) {
                    return@Thread
                } catch (error: Throwable) {
                    failures += error
                    return@Thread
                }
            }
        }
        searcher.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        index.close()
        searcher.join(5_000)

        assertTrue("Search thread did not stop", !searcher.isAlive)
        assertTrue("Unexpected native failure: $failures", failures.isEmpty())
    }

    @Test
    fun recallAtTenMeetsThePinnedQualityGate() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "hnsw-native-recall-test",
        ).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val random = Random(731)
        val vectors = List(1_000) {
            normalized(FloatArray(384) { random.nextFloat() * 2f - 1f })
        }
        var recalled = 0
        var expected = 0

        HnswIndex.create(root, dimension = 384, maximumElements = vectors.size).use { index ->
            vectors.forEachIndexed { id, vector -> index.add(id.toLong(), vector) }
            repeat(100) { queryIndex ->
                val source = vectors[(queryIndex * 7) % vectors.size]
                val query = normalized(
                    FloatArray(384) { dimension ->
                        source[dimension] + (random.nextFloat() - 0.5f) * 0.01f
                    },
                )
                val exact = vectors.indices
                    .map { id -> id.toLong() to dot(query, vectors[id]) }
                    .sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
                    .take(10)
                    .mapTo(mutableSetOf()) { it.first }
                val approximate = index.search(query, topK = 10, efSearch = 48)
                    .mapTo(mutableSetOf()) { it.chunkId }
                recalled += exact.intersect(approximate).size
                expected += exact.size
            }
        }

        val recallAtTen = recalled.toDouble() / expected.toDouble()
        assertTrue("Recall@10 was $recallAtTen", recallAtTen >= 0.95)
    }

    @Test
    fun repeatedLoadSearchCloseReturnsTheNativeHandleCountToZero() {
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "hnsw-native-handle-pressure-test",
        ).apply {
            deleteRecursively()
            check(mkdirs())
        }
        val saved = File(root, "pressure.hnsw")
        HnswIndex.create(root, dimension = 3, maximumElements = 3, m = 2, efConstruction = 8).use { index ->
            index.add(1, floatArrayOf(1f, 0f, 0f))
            index.add(2, floatArrayOf(0f, 1f, 0f))
            index.add(3, floatArrayOf(0f, 0f, 1f))
            index.save(saved)
        }
        assertEquals(0, HnswIndex.activeNativeHandleCountForDebug())

        repeat(50) {
            HnswIndex.load(root, saved, dimension = 3, maximumElements = 3).use { index ->
                assertEquals(1L, index.search(floatArrayOf(1f, 0f, 0f), 1, 8).single().chunkId)
            }
            assertEquals(0, HnswIndex.activeNativeHandleCountForDebug())
        }
    }

    private fun normalized(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { value -> value.toDouble() * value.toDouble() })
        return FloatArray(values.size) { index -> (values[index] / norm).toFloat() }
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        var score = 0f
        for (index in left.indices) score += left[index] * right[index]
        return score
    }
}
