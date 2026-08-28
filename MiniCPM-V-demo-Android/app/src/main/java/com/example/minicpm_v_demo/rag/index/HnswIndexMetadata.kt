package com.example.minicpm_v_demo.rag.index

import com.example.minicpm_v_demo.rag.embed.E5ModelSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class HnswIndexMetadata(
    val corpusKey: EmbeddingCorpusKey,
    val dimension: Int,
    val indexGeneration: Long,
    val maximumChunkId: Long,
    val plaintextLength: Long,
    val plaintextSha256: String,
    val builtAt: Long,
) {
    init {
        require(dimension == E5ModelSpec.PINNED.dimension)
        require(indexGeneration > 0 && maximumChunkId >= 0 && builtAt > 0)
        require(plaintextLength in 1..MAX_INDEX_BYTES)
        require(plaintextSha256.isCanonicalSha256())
        require(corpusKey.embeddingCount > 0)
        require(corpusKey.knowledgeBaseIds.size <= MAX_KNOWLEDGE_BASES)
        require(
            corpusKey.knowledgeBaseIds.all { id ->
                val encodedLength = id.toByteArray(Charsets.UTF_8).size
                id.isNotBlank() && encodedLength <= MAX_KNOWLEDGE_BASE_ID_BYTES &&
                    id.none(Char::isISOControl)
            },
        )
    }

    fun matches(expected: EmbeddingCorpusKey): Boolean = corpusKey == expected

    companion object {
        const val MAX_INDEX_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAX_KNOWLEDGE_BASES = 64
        const val MAX_KNOWLEDGE_BASE_ID_BYTES = 256
    }
}

object HnswIndexMetadataCodec {
    private val MAGIC = "MCPHNSW1".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val SHA256_BYTES = 32
    private const val MAX_METADATA_BYTES = 32 * 1024

    fun encode(metadata: HnswIndexMetadata): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(metadata.dimension)
            output.writeLong(metadata.indexGeneration)
            output.writeLong(metadata.maximumChunkId)
            output.writeLong(metadata.plaintextLength)
            output.writeLong(metadata.builtAt)
            output.writeInt(metadata.corpusKey.knowledgeBaseIds.size)
            metadata.corpusKey.knowledgeBaseIds.forEach { output.writeBoundedString(it) }
            output.write(metadata.corpusKey.modelSha256.hexToBytes())
            output.writeInt(metadata.corpusKey.corpusVersion)
            output.writeInt(metadata.corpusKey.embeddingCount)
            output.writeLong(metadata.corpusKey.maximumUpdatedAt)
            output.writeLong(metadata.corpusKey.chunkIdSum)
            output.write(metadata.plaintextSha256.hexToBytes())
        }
        bytes.toByteArray().also { require(it.size <= MAX_METADATA_BYTES) }
    }

    @Throws(IOException::class)
    fun decode(source: InputStream): HnswIndexMetadata {
        val encoded = source.readBounded(MAX_METADATA_BYTES)
        try {
            return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) throw IOException("Invalid HNSW metadata magic")
                if (input.readInt() != FORMAT_VERSION) throw IOException("Unsupported HNSW metadata version")
                val dimension = input.readInt()
                val generation = input.readLong()
                val maximumChunkId = input.readLong()
                val plaintextLength = input.readLong()
                val builtAt = input.readLong()
                val knowledgeBaseCount = input.readInt()
                if (knowledgeBaseCount !in 1..HnswIndexMetadata.MAX_KNOWLEDGE_BASES) {
                    throw IOException("Invalid HNSW knowledge-base count")
                }
                val knowledgeBaseIds = List(knowledgeBaseCount) { input.readBoundedString() }
                val modelSha = ByteArray(SHA256_BYTES).also(input::readFully).toHex()
                val corpusVersion = input.readInt()
                val embeddingCount = input.readInt()
                val maximumUpdatedAt = input.readLong()
                val chunkIdSum = input.readLong()
                val plaintextSha = ByteArray(SHA256_BYTES).also(input::readFully).toHex()
                if (input.read() != -1) throw IOException("Trailing HNSW metadata bytes")
                try {
                    HnswIndexMetadata(
                        corpusKey = EmbeddingCorpusKey(
                            knowledgeBaseIds = knowledgeBaseIds,
                            modelSha256 = modelSha,
                            corpusVersion = corpusVersion,
                            embeddingCount = embeddingCount,
                            maximumUpdatedAt = maximumUpdatedAt,
                            chunkIdSum = chunkIdSum,
                        ),
                        dimension = dimension,
                        indexGeneration = generation,
                        maximumChunkId = maximumChunkId,
                        plaintextLength = plaintextLength,
                        plaintextSha256 = plaintextSha,
                        builtAt = builtAt,
                    )
                } catch (error: IllegalArgumentException) {
                    throw IOException("Invalid HNSW metadata values", error)
                }
            }
        } catch (error: EOFException) {
            throw IOException("Truncated HNSW metadata", error)
        }
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..HnswIndexMetadata.MAX_KNOWLEDGE_BASE_ID_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): String {
        val size = readInt()
        if (size !in 1..HnswIndexMetadata.MAX_KNOWLEDGE_BASE_ID_BYTES) {
            throw IOException("Invalid HNSW metadata string length")
        }
        val bytes = ByteArray(size).also(::readFully)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IOException("Invalid UTF-8 in HNSW metadata", error)
        }
    }

    private fun InputStream.readBounded(maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maximum) throw IOException("HNSW metadata is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

data class HnswIndexPaths(
    val encryptedIndex: File,
    val metadata: File,
)

class HnswIndexPathPolicy(indexDirectory: File) {
    private val directory = indexDirectory.canonicalFile.also { root ->
        require((root.isDirectory || root.mkdirs()) && root.isDirectory) {
            "HNSW index directory is unavailable"
        }
    }

    fun pathsFor(corpusKey: EmbeddingCorpusKey): HnswIndexPaths {
        val baseName = corpusKey.stableDigest()
        return HnswIndexPaths(
            encryptedIndex = requireManaged(File(directory, "$baseName.hnsw.enc")),
            metadata = requireManaged(File(directory, "$baseName.hnsw.meta")),
        )
    }

    fun requireManaged(candidate: File): File {
        val canonical = candidate.canonicalFile
        require(canonical.parentFile == directory && canonical.name.matches(MANAGED_NAME)) {
            "HNSW path escapes the managed index directory"
        }
        return canonical
    }

    private companion object {
        val MANAGED_NAME = Regex("[0-9a-f]{64}\\.hnsw\\.(enc|meta)")
    }
}

object HnswIndexIntegrity {
    private data class DigestResult(val length: Long, val sha256: String)

    fun sha256(file: File): String = digest(file).sha256

    fun verify(file: File, metadata: HnswIndexMetadata): Boolean {
        val actual = runCatching { digest(file) }.getOrNull() ?: return false
        return actual.length == metadata.plaintextLength &&
            MessageDigest.isEqual(
                actual.sha256.hexToBytes(),
                metadata.plaintextSha256.hexToBytes(),
            )
    }

    private fun digest(file: File): DigestResult {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                length = Math.addExact(length, count.toLong())
                messageDigest.update(buffer, 0, count)
            }
        }
        return DigestResult(length, messageDigest.digest().toHex())
    }
}

object HnswIndexRssPolicy {
    private const val M = 16L
    private const val FLOAT_BYTES = 4L
    private const val LINK_BYTES = 4L
    private const val PER_NODE_OVERHEAD_BYTES = 64L

    fun estimateBytes(metadata: HnswIndexMetadata): Long = try {
        val count = metadata.corpusKey.embeddingCount.toLong()
        val vectors = Math.multiplyExact(Math.multiplyExact(count, metadata.dimension.toLong()), FLOAT_BYTES)
        val links = Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(count, M), 2L), LINK_BYTES)
        val overhead = Math.multiplyExact(count, PER_NODE_OVERHEAD_BYTES)
        Math.addExact(Math.addExact(vectors, links), overhead)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

enum class HnswIndexRejection {
    CORPUS_MISMATCH,
    RSS_BUDGET_EXCEEDED,
}

data class HnswIndexAdmission(
    val allowed: Boolean,
    val rejection: HnswIndexRejection?,
    val estimatedRssBytes: Long,
)

object HnswIndexAdmissionPolicy {
    fun assess(
        expectedCorpus: EmbeddingCorpusKey,
        metadata: HnswIndexMetadata,
        appMemoryBudgetBytes: Long,
    ): HnswIndexAdmission {
        require(appMemoryBudgetBytes > 0)
        val estimate = HnswIndexRssPolicy.estimateBytes(metadata)
        if (!metadata.matches(expectedCorpus)) {
            return HnswIndexAdmission(false, HnswIndexRejection.CORPUS_MISMATCH, estimate)
        }
        if (estimate > appMemoryBudgetBytes / 10L) {
            return HnswIndexAdmission(false, HnswIndexRejection.RSS_BUDGET_EXCEEDED, estimate)
        }
        return HnswIndexAdmission(true, null, estimate)
    }
}

private fun String.isCanonicalSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

private fun String.hexToBytes(): ByteArray {
    require(isCanonicalSha256()) { "Digest must be lowercase SHA-256" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
