package com.example.minicpm_v_demo.rag.embed

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FloatVectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        require(vector.isNotEmpty() && vector.all(Float::isFinite)) { "Vector must be finite and non-empty" }
        return ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach(::putFloat) }
            .array()
    }

    fun decode(bytes: ByteArray, dimension: Int): FloatArray {
        require(dimension > 0 && bytes.size == dimension * Float.SIZE_BYTES) { "Invalid vector size" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimension) { buffer.float }.also { vector ->
            require(vector.all(Float::isFinite)) { "Stored vector contains a non-finite value" }
        }
    }
}
