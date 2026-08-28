package com.example.minicpm_v_demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodePolicyTest {

    @Test
    fun imageWithinLimitKeepsOriginalResolution() {
        assertEquals(1, ImageDecodePolicy.sampleSizeFor(2048, 1536))
        assertEquals(1, ImageDecodePolicy.sampleSizeFor(2_000, 2_000))
        assertEquals(2, ImageDecodePolicy.sampleSizeFor(4_096, 4_096))
    }

    @Test
    fun largeImageUsesPowerOfTwoSamplingUntilDimensionsAndPixelCountAreBounded() {
        val sampleSize = ImageDecodePolicy.sampleSizeFor(12_000, 9_000)

        assertEquals(8, sampleSize)
        assertTrue(12_000 / sampleSize <= ImageDecodePolicy.MAX_DIMENSION)
        assertTrue(9_000 / sampleSize <= ImageDecodePolicy.MAX_DIMENSION)
        assertTrue(ImageDecodePolicy.isPixelCountAllowed(
            width = 12_000 / sampleSize,
            height = 9_000 / sampleSize
        ))
    }

    @Test
    fun fourMegapixelBoundaryIsAcceptedButLargerDecodeIsSampled() {
        assertEquals(1, ImageDecodePolicy.sampleSizeFor(2_048, 2_048))
        assertEquals(2, ImageDecodePolicy.sampleSizeFor(2_049, 2_048))

        assertTrue(ImageDecodePolicy.isPixelCountAllowed(2_048, 2_048))
        assertFalse(ImageDecodePolicy.isPixelCountAllowed(2_049, 2_048))
    }

    @Test
    fun invalidDimensionsAreRejectedBeforeDecode() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageDecodePolicy.sampleSizeFor(0, 1080)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageDecodePolicy.sampleSizeFor(1920, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageDecodePolicy.sampleSizeFor(1920, 1080, maxPixelCount = 0)
        }
    }

    @Test
    fun knownAndUnknownSourceLengthsAreHandledWithoutOverflow() {
        assertTrue(ImageDecodePolicy.isSourceLengthAllowed(-1L))
        assertTrue(ImageDecodePolicy.isSourceLengthAllowed(8L * 1024 * 1024))
        assertFalse(ImageDecodePolicy.isSourceLengthAllowed(
            ImageDecodePolicy.MAX_SOURCE_BYTES + 1L
        ))
    }
}
