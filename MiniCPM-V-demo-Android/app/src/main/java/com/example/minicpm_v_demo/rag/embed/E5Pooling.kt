package com.example.minicpm_v_demo.rag.embed

import kotlin.math.sqrt

object E5Pooling {
    fun maskedMeanAndNormalize(hidden: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        require(hidden.isNotEmpty() && hidden.size == attentionMask.size)
        val dimension = hidden.first().size
        require(dimension > 0 && hidden.all { it.size == dimension })
        val sum = FloatArray(dimension)
        var included = 0
        hidden.indices.forEach { token ->
            if (attentionMask[token] != 0L) {
                included++
                hidden[token].indices.forEach { index -> sum[index] += hidden[token][index] }
            }
        }
        require(included > 0) { "Attention mask contains no tokens" }
        sum.indices.forEach { sum[it] /= included.toFloat() }
        val norm = l2Norm(sum)
        require(norm.isFinite() && norm > 0f) { "Embedding norm is invalid" }
        sum.indices.forEach { sum[it] /= norm }
        return sum
    }

    fun l2Norm(vector: FloatArray): Float = sqrt(vector.sumOf { value -> (value * value).toDouble() }).toFloat()
}
