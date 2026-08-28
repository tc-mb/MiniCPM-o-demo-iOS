package com.example.minicpm_v_demo

object ImageDecodePolicy {
    const val MAX_DIMENSION = 4096
    const val MAX_PIXEL_COUNT = 4L * 1024L * 1024L
    const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

    fun sampleSizeFor(
        width: Int,
        height: Int,
        maxDimension: Int = MAX_DIMENSION,
        maxPixelCount: Long = MAX_PIXEL_COUNT
    ): Int {
        require(width > 0 && height > 0) { "Image dimensions must be positive" }
        require(maxDimension > 0) { "Maximum image dimension must be positive" }
        require(maxPixelCount > 0) { "Maximum pixel count must be positive" }

        var sampleSize = 1
        while (
            ceilDiv(width, sampleSize) > maxDimension ||
            ceilDiv(height, sampleSize) > maxDimension ||
            !isPixelCountAllowed(
                width = ceilDiv(width, sampleSize),
                height = ceilDiv(height, sampleSize),
                maxPixelCount = maxPixelCount
            )
        ) {
            sampleSize = Math.multiplyExact(sampleSize, 2)
        }
        return sampleSize
    }

    fun isPixelCountAllowed(
        width: Int,
        height: Int,
        maxPixelCount: Long = MAX_PIXEL_COUNT
    ): Boolean =
        width > 0 &&
            height > 0 &&
            maxPixelCount > 0 &&
            width.toLong() * height.toLong() <= maxPixelCount

    fun isSourceLengthAllowed(lengthBytes: Long): Boolean =
        lengthBytes == -1L || lengthBytes in 1..MAX_SOURCE_BYTES

    private fun ceilDiv(value: Int, divisor: Int): Int =
        1 + (value - 1) / divisor
}
