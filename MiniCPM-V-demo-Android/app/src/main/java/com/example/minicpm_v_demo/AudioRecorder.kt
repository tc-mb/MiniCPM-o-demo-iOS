package com.example.minicpm_v_demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Lightweight audio recorder producing 16kHz mono 16-bit PCM WAV files,
 * suitable as VoxCPM2 reference audio input.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var outputFile: File? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun startRecording(targetFile: File): Boolean {
        if (isRecording) return false
        if (!hasPermission) return false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuffer, 4096)

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            outputFile = targetFile
            // Ensure parent directory exists
            targetFile.parentFile?.mkdirs()
            audioRecord?.startRecording()
            isRecording = true

            // Write recording in a background thread
            Thread {
                writeRecording()
            }.start()

            Log.i(TAG, "Recording started -> $targetFile")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            false
        }
    }

    private fun writeRecording() {
        val record = audioRecord ?: return
        val file = outputFile ?: return

        val buffer = ShortArray(record.bufferSizeInFrames)
        val rawFile = File(file.parent, "${file.nameWithoutExtension}.raw")

        try {
            FileOutputStream(rawFile).use { fos ->
                while (isRecording) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val byteBuf = ShortArray(read)
                        System.arraycopy(buffer, 0, byteBuf, 0, read)
                        for (sample in byteBuf) {
                            fos.write(sample.toInt() and 0xFF)
                            fos.write((sample.toInt() shr 8) and 0xFF)
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read error: $read")
                        break
                    }
                }
            }

            // Convert raw PCM to WAV
            convertRawToWav(rawFile, file)
            rawFile.delete()
            Log.i(TAG, "Recording saved: ${file.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "writeRecording failed", e)
        }
    }

    private fun convertRawToWav(rawFile: File, wavFile: File) {
        val rawSize = rawFile.length()
        val dataSize = rawSize
        val chunkSize = 36 + dataSize

        FileOutputStream(wavFile).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intTo4Bytes(chunkSize.toInt()))
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(intTo4Bytes(16)) // chunk size
            fos.write(shortTo2Bytes(1)) // PCM
            fos.write(shortTo2Bytes(1)) // mono
            fos.write(intTo4Bytes(SAMPLE_RATE))
            fos.write(intTo4Bytes(SAMPLE_RATE * 2)) // byte rate
            fos.write(shortTo2Bytes(2)) // block align
            fos.write(shortTo2Bytes(16)) // bits per sample

            // data chunk
            fos.write("data".toByteArray())
            fos.write(intTo4Bytes(dataSize.toInt()))

            // Copy raw data
            RandomAccessFile(rawFile, "r").use { raf ->
                val buf = ByteArray(4096)
                var bytesRead: Int
                while (raf.read(buf).also { bytesRead = it } > 0) {
                    fos.write(buf, 0, bytesRead)
                }
            }
        }
    }

    private fun intTo4Bytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        (v shr 8 and 0xFF).toByte(),
        (v shr 16 and 0xFF).toByte(),
        (v shr 24 and 0xFF).toByte()
    )

    private fun shortTo2Bytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        (v shr 8 and 0xFF).toByte()
    )

    fun stopRecording(): Long {
        isRecording = false
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        val size = outputFile?.length() ?: 0L
        Log.i(TAG, "Recording stopped, file size=$size")
        return size
    }

    fun getDurationMs(wavFile: File): Int {
        val dataSize = wavFile.length() - 44
        if (dataSize <= 0) return 0
        return ((dataSize * 1000) / (SAMPLE_RATE * 2)).toInt()
    }
}
