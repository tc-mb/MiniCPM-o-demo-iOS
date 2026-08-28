package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.retrieval.RankedChunkId
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

internal data class NativeHnswSearchResult(
    val chunkIds: LongArray,
    val scores: FloatArray,
)

internal object HnswNative {
    init {
        System.loadLibrary("rag_hnsw")
    }

    external fun nativeCreate(
        indexDirectory: String,
        dimension: Int,
        maximumElements: Int,
        m: Int,
        efConstruction: Int,
    ): Long

    @Throws(IOException::class)
    external fun nativeLoad(
        indexDirectory: String,
        indexFile: String,
        dimension: Int,
        maximumElements: Int,
    ): Long

    external fun nativeAdd(handle: Long, chunkId: Long, vector: FloatArray)

    external fun nativeSearch(
        handle: Long,
        query: FloatArray,
        topK: Int,
        efSearch: Int,
    ): NativeHnswSearchResult

    @Throws(IOException::class)
    external fun nativeSave(handle: Long, indexDirectory: String, indexFile: String)

    external fun nativeClose(handle: Long)

    external fun nativeActiveHandleCount(): Int
}

class HnswIndex private constructor(
    private val indexDirectory: File,
    private val dimension: Int,
    handle: Long,
) : AutoCloseable {
    private val nativeHandle = AtomicLong(handle.also { require(it != 0L) })

    fun add(chunkId: Long, vector: FloatArray) {
        require(chunkId >= 0) { "HNSW chunk ID must be non-negative" }
        HnswNative.nativeAdd(requireOpen(), chunkId, normalize(vector))
    }

    fun search(query: FloatArray, topK: Int, efSearch: Int): List<RankedChunkId> {
        require(topK > 0) { "HNSW topK must be positive" }
        require(efSearch >= topK) { "HNSW efSearch must be at least topK" }
        val result = HnswNative.nativeSearch(requireOpen(), normalize(query), topK, efSearch)
        check(result.chunkIds.size == result.scores.size) { "Invalid native HNSW result" }
        return result.chunkIds.indices
            .map { index -> RankedChunkId(result.chunkIds[index], result.scores[index]) }
            .sortedWith(compareByDescending<RankedChunkId> { it.score }.thenBy { it.chunkId })
    }

    @Throws(IOException::class)
    fun save(indexFile: File) {
        val managed = requireIndexFile(indexDirectory, indexFile, mustExist = false)
        HnswNative.nativeSave(requireOpen(), indexDirectory.path, managed.path)
    }

    override fun close() {
        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L) HnswNative.nativeClose(handle)
    }

    private fun requireOpen(): Long = nativeHandle.get().takeIf { it != 0L }
        ?: throw IllegalStateException("HNSW index is closed")

    private fun normalize(vector: FloatArray): FloatArray {
        require(vector.size == dimension && vector.all(Float::isFinite)) {
            "HNSW vector must contain exactly $dimension finite values"
        }
        var squaredNorm = 0.0
        vector.forEach { value -> squaredNorm += value.toDouble() * value.toDouble() }
        require(squaredNorm.isFinite() && squaredNorm > 0.0) { "HNSW vector norm must be positive" }
        val inverseNorm = 1.0 / sqrt(squaredNorm)
        return FloatArray(vector.size) { index -> (vector[index] * inverseNorm).toFloat() }
    }

    companion object {
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private const val MAX_DIMENSION = 4096
        private const val MAXIMUM_ELEMENTS = 10_000_000
        private const val MAX_M = 128
        private const val MAX_EF = 1_000_000

        fun create(
            indexDirectory: File,
            dimension: Int,
            maximumElements: Int,
            m: Int = 16,
            efConstruction: Int = 100,
        ): HnswIndex {
            val root = requireIndexDirectory(indexDirectory)
            requireParameters(dimension, maximumElements)
            require(m in 2..MAX_M) { "HNSW M is out of range" }
            require(efConstruction in m..MAX_EF) { "HNSW efConstruction is out of range" }
            return HnswIndex(
                root,
                dimension,
                HnswNative.nativeCreate(root.path, dimension, maximumElements, m, efConstruction),
            )
        }

        @Throws(IOException::class)
        fun load(
            indexDirectory: File,
            indexFile: File,
            dimension: Int,
            maximumElements: Int,
        ): HnswIndex {
            val root = requireIndexDirectory(indexDirectory)
            requireParameters(dimension, maximumElements)
            val managed = requireIndexFile(root, indexFile, mustExist = true)
            return HnswIndex(
                root,
                dimension,
                HnswNative.nativeLoad(root.path, managed.path, dimension, maximumElements),
            )
        }

        private fun requireParameters(dimension: Int, maximumElements: Int) {
            require(dimension in 1..MAX_DIMENSION) { "HNSW dimension is out of range" }
            require(maximumElements in 1..MAXIMUM_ELEMENTS) { "HNSW capacity is out of range" }
        }

        private fun requireIndexDirectory(directory: File): File = directory.canonicalFile.also { root ->
            require(root.isDirectory) { "HNSW index directory is unavailable" }
        }

        private fun requireIndexFile(root: File, candidate: File, mustExist: Boolean): File {
            val canonical = candidate.canonicalFile
            require(canonical.parentFile == root && canonical.name.matches(SAFE_FILE_NAME)) {
                "HNSW index path escapes its dedicated directory"
            }
            if (mustExist) require(canonical.isFile && canonical.length() > 0L) {
                "HNSW index file is unavailable"
            }
            return canonical
        }

        internal fun activeNativeHandleCountForDebug(): Int = HnswNative.nativeActiveHandleCount()
    }
}
