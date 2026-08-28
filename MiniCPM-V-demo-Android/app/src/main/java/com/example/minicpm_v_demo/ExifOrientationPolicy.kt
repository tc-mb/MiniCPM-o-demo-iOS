package com.example.minicpm_v_demo

data class ExifOrientationTransform(
    val rotationDegrees: Int = 0,
    val mirrorHorizontal: Boolean = false
)

object ExifOrientationPolicy {

    fun transformFor(orientation: Int): ExifOrientationTransform =
        when (orientation) {
            2 -> ExifOrientationTransform(mirrorHorizontal = true)
            3 -> ExifOrientationTransform(rotationDegrees = 180)
            4 -> ExifOrientationTransform(
                rotationDegrees = 180,
                mirrorHorizontal = true
            )
            5 -> ExifOrientationTransform(
                rotationDegrees = 90,
                mirrorHorizontal = true
            )
            6 -> ExifOrientationTransform(rotationDegrees = 90)
            7 -> ExifOrientationTransform(
                rotationDegrees = 270,
                mirrorHorizontal = true
            )
            8 -> ExifOrientationTransform(rotationDegrees = 270)
            else -> ExifOrientationTransform()
        }
}
