package com.example.minicpm_v_demo

import android.util.Log
import java.io.File

object CpuFeatures {
    private const val TAG = "CpuFeatures"

    private val features: Set<String> by lazy { readFeatures() }

    val hasI8mm: Boolean get() = "i8mm" in features
    val hasBf16: Boolean get() = "bf16" in features
    val hasSve2: Boolean get() = "sve2" in features
    val hasDotprod: Boolean get() = "asimddp" in features
    val hasFp16: Boolean get() = "fphp" in features

    /**
     * Returns the best available ggml-cpu library name for the current CPU.
     * The returned string can be passed to [System.load] after prepending
     * the native library directory and "lib" prefix.
     */
    fun bestGgmlCpuVariant(): String? {
        if (hasI8mm && hasBf16) return "v86"
        return null
    }

    fun summary(): String = buildString {
        append("CPU features: ${features.joinToString()}\n")
        append("dotprod=$hasDotprod fp16=$hasFp16 i8mm=$hasI8mm bf16=$hasBf16 sve2=$hasSve2\n")
        append("Selected ggml-cpu variant: ${bestGgmlCpuVariant() ?: "baseline"}")
    }

    private fun readFeatures(): Set<String> = try {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .firstOrNull()
            ?.substringAfter(":")
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.toSet()
            ?: emptySet<String>().also { Log.w(TAG, "No Features line in /proc/cpuinfo") }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read /proc/cpuinfo", e)
        emptySet()
    }
}
