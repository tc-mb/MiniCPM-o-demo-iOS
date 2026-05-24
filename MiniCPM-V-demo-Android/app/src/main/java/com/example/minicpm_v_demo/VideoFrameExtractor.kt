package com.example.minicpm_v_demo

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Uniform-sampling video frame extractor that mirrors iOS demo's
 * `MBVideoFrameExtractor` so that the three platforms feed MiniCPM-V-4.6
 * with the exact same frame set for a given clip.
 *
 * The model is fed up to [MAX_FRAMES] still images per turn:
 *   - If the clip is short enough (`ceil(durationSeconds) <= MAX_FRAMES`)
 *     we sample at 1 fps - one frame per second, anchored to `i + 0.5s`
 *     so the first frame isn't a black SOF.
 *   - Otherwise we slice the duration into [MAX_FRAMES] equal segments
 *     and grab the midpoint of each, which keeps temporal coverage
 *     while honouring the model's KV budget.
 *
 * Each frame is compressed to JPEG (quality 50) and returned as raw
 * bytes ready for `LlamaEngine.prefillImage(...)`.  The first frame is
 * also returned as a Bitmap for the chat-cell thumbnail.
 *
 * @see MiniCPM-V-demo/Sources/Base/VideoFrameExtractor/MBVideoFrameExtractor.swift
 */
object VideoFrameExtractor {

    private const val TAG = "VideoFrameExtractor"

    /** Hard cap on frames per turn; matches iOS demo on V4.6. */
    const val MAX_FRAMES = 64

    /** JPEG quality used for each extracted frame; matches iOS (0.5). */
    private const val JPEG_QUALITY = 50

    data class Result(
        /** First extracted frame, used as the chat-cell thumbnail. */
        val thumbnail: Bitmap,
        /** Original video duration in milliseconds. */
        val durationMs: Long,
        /** Original video file size in bytes (best-effort). */
        val fileSizeBytes: Long,
        /** Original frame size before re-encoding (best-effort). */
        val width: Int,
        val height: Int,
        /** JPEG-encoded frames in display order. */
        val frames: List<ByteArray>
    ) {
        val frameCount: Int get() = frames.size
    }

    /**
     * Extracts a fixed-size, evenly-spaced set of JPEG frames from the
     * video referenced by [uri].  Caller MUST run this off the main
     * thread - frame extraction on long clips can easily take 5-10s on
     * a mid-range Android device.
     */
    fun extract(context: Context, uri: Uri): Result {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val durationSec = durationMs / 1000.0
            require(durationMs > 0) { "Video duration unknown or zero" }

            val timestampsSec = computeSampleTimestamps(durationSec)
            Log.i(TAG, "Sampling ${timestampsSec.size} frames from $durationMs ms video " +
                "(${width}x${height}), strategy=${if (timestampsSec.size <= MAX_FRAMES && durationSec <= MAX_FRAMES) "1fps" else "uniform"}")

            val frames = ArrayList<ByteArray>(timestampsSec.size)
            var thumbnail: Bitmap? = null

            for ((idx, tSec) in timestampsSec.withIndex()) {
                val timeUs = (tSec * 1_000_000L).roundToLong()
                val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp == null) {
                    Log.w(TAG, "Frame #$idx at ${tSec}s returned null; skipping")
                    continue
                }
                if (thumbnail == null) thumbnail = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
                frames.add(bos.toByteArray())
                if (bmp !== thumbnail) bmp.recycle()
            }

            require(frames.isNotEmpty()) { "No frames could be extracted" }
            require(thumbnail != null) { "Thumbnail extraction failed" }

            val fileSize = queryFileSize(context, uri)
            return Result(
                thumbnail = thumbnail,
                durationMs = durationMs,
                fileSizeBytes = fileSize,
                width = width,
                height = height,
                frames = frames
            )
        } finally {
            try { retriever.release() } catch (_: Throwable) { /* no-op */ }
        }
    }

    /**
     * Returns the wall-clock timestamps (seconds) at which frames
     * should be extracted, mirroring iOS [MBVideoFrameExtractor].
     */
    private fun computeSampleTimestamps(durationSec: Double): List<Double> {
        if (durationSec <= 0.0) return emptyList()
        val totalSecsCeil = ceil(durationSec).toInt()
        return if (totalSecsCeil <= MAX_FRAMES) {
            // 1 fps - one frame per integer second, anchored at i+0.5s
            // so the first sample isn't black at t=0.
            val n = max(1, totalSecsCeil)
            (0 until n).map { (it + 0.5).coerceAtMost(durationSec - 0.001) }
        } else {
            // Uniform sampling: midpoint of each of MAX_FRAMES equal
            // slices.  Matches iOS's "ceil(duration) > maxFrames" branch.
            val slice = durationSec / MAX_FRAMES
            (0 until MAX_FRAMES).map {
                ((it + 0.5) * slice).coerceAtMost(durationSec - 0.001)
            }
        }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) afd.length else -1L
            } ?: -1L
        } catch (e: Exception) {
            Log.w(TAG, "Could not query file size for $uri: ${e.message}")
            -1L
        }
    }

    /** Human-readable summary used in chat-cell perf log. */
    fun formatVideoInfo(context: Context, result: Result): String {
        val sec = result.durationMs / 1000.0
        val sizeStr = if (result.fileSizeBytes > 0) {
            " (${result.fileSizeBytes / 1024} KB)"
        } else ""
        val resStr = if (result.width > 0 && result.height > 0) {
            "${result.width}x${result.height}"
        } else context.getString(R.string.video_label)
        val durStr = String.format("%.1fs", sec)
        return context.getString(R.string.video_frame_info, resStr, sizeStr, durStr, result.frameCount)
    }
}
