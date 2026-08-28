package com.example.minicpm_v_demo

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OriginalImageViewerActivity : StatusBarVisibleActivity() {

    private var displayedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_original_image_viewer)

        findViewById<ImageButton>(R.id.btn_close_original_image).setOnClickListener {
            finish()
        }
        val imageView = findViewById<ImageView>(R.id.iv_original_image)
        val progress = findViewById<ProgressBar>(R.id.progress_original_image)
        val token = intent.getStringExtra(EXTRA_IMAGE_TOKEN)
        val cache = ImageSourceCache(
            File(filesDir, PendingImageViewModel.SOURCE_CACHE_DIRECTORY),
            ImageDecodePolicy.MAX_SOURCE_BYTES
        )
        val source = cache.resolve(token)
        if (source == null) {
            Toast.makeText(this, R.string.original_image_unavailable, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeOriginal(source) }
            progress.visibility = View.GONE
            if (bitmap == null) {
                Toast.makeText(
                    this@OriginalImageViewerActivity,
                    R.string.original_image_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            } else {
                displayedBitmap = bitmap
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun decodeOriginal(source: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

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
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageDecodePolicy.sampleSizeFor(
                    bounds.outWidth,
                    bounds.outHeight,
                    ImageDecodePolicy.MAX_DIMENSION,
                    ImageDecodePolicy.MAX_PIXEL_COUNT
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = FileInputStream(source).use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
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

    override fun onDestroy() {
        displayedBitmap?.takeUnless { it.isRecycled }?.recycle()
        displayedBitmap = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_IMAGE_TOKEN = "original_image_token"

        fun intent(context: Context, token: String): Intent =
            Intent(context, OriginalImageViewerActivity::class.java)
                .putExtra(EXTRA_IMAGE_TOKEN, token)
    }
}
