package com.example.minicpm_v_demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.FileInputStream
import java.io.IOException

object StoredImageThumbnailLoader {
    fun load(cache: ImageSourceCache, token: String?): Bitmap? {
        val source = cache.resolve(token) ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageDecodePolicy.sampleSizeFor(
                    bounds.outWidth,
                    bounds.outHeight,
                    PendingImageViewModel.THUMBNAIL_MAX_DIMENSION,
                    PendingImageViewModel.THUMBNAIL_MAX_PIXEL_COUNT
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = FileInputStream(source).use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            val orientation = try {
                FileInputStream(source).use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            } catch (_: IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val transform = ExifOrientationPolicy.transformFor(orientation)
            if (transform.rotationDegrees == 0 && !transform.mirrorHorizontal) {
                decoded
            } else {
                val matrix = Matrix().apply {
                    postRotate(transform.rotationDegrees.toFloat())
                    if (transform.mirrorHorizontal) postScale(-1f, 1f)
                }
                Bitmap.createBitmap(
                    decoded, 0, 0, decoded.width, decoded.height, matrix, true
                ).also { if (it !== decoded) decoded.recycle() }
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
