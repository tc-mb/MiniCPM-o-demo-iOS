package com.example.minicpm_v_demo.rag.chunk

import java.nio.ByteBuffer
import java.security.MessageDigest

object ChunkIdentity {
    fun id(documentId: String, ordinal: Int, contentSha256: String): Long {
        require(documentId.isNotBlank() && ordinal >= 0 && HEX_SHA256.matches(contentSha256))
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$documentId\u0000$ordinal\u0000$contentSha256".toByteArray())
        return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE or 1L
    }

    private val HEX_SHA256 = Regex("[0-9a-f]{64}")
}
