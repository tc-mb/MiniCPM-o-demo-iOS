package com.example.minicpm_v_demo

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationPolicyTest {

    @Test
    fun allStandardExifOrientationsMapToExpectedTransform() {
        val expected = mapOf(
            1 to ExifOrientationTransform(),
            2 to ExifOrientationTransform(mirrorHorizontal = true),
            3 to ExifOrientationTransform(rotationDegrees = 180),
            4 to ExifOrientationTransform(
                rotationDegrees = 180,
                mirrorHorizontal = true
            ),
            5 to ExifOrientationTransform(
                rotationDegrees = 90,
                mirrorHorizontal = true
            ),
            6 to ExifOrientationTransform(rotationDegrees = 90),
            7 to ExifOrientationTransform(
                rotationDegrees = 270,
                mirrorHorizontal = true
            ),
            8 to ExifOrientationTransform(rotationDegrees = 270)
        )

        expected.forEach { (orientation, transform) ->
            assertEquals(transform, ExifOrientationPolicy.transformFor(orientation))
        }
    }

    @Test
    fun missingOrUnknownOrientationFallsBackToIdentity() {
        assertEquals(
            ExifOrientationTransform(),
            ExifOrientationPolicy.transformFor(0)
        )
        assertEquals(
            ExifOrientationTransform(),
            ExifOrientationPolicy.transformFor(99)
        )
    }
}
