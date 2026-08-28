package com.example.minicpm_v_demo.rag.embed

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E5ExecutionProviderBenchmarkInstrumentedTest {
    @Test
    fun benchmarkCpuNnapiAndNnapiFp16WithoutSilentFallback() {
        keepDebugTargetForeground()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelDirectory = EmbeddingModelManager(context).modelDirectory()
        assertTrue("Pinned E5 model must be installed", modelDirectory.isDirectory)
        val results = mutableListOf<ProviderResult>()
        var cpuVector: FloatArray? = null

        E5ExecutionProfile.entries.forEach { profile ->
            val result = benchmarkProfile(context, modelDirectory, profile, cpuVector)
            results += result
            if (profile == E5ExecutionProfile.CPU && result.supported) {
                cpuVector = result.referenceVector
            }
        }

        val outputDirectory = requireNotNull(context.getExternalFilesDir("benchmarks"))
        File(outputDirectory, OUTPUT_FILE_NAME).writeText(renderJson(results), Charsets.UTF_8)

        val cpu = results.single { it.profile == E5ExecutionProfile.CPU }
        assertTrue("CPU E5 provider failed", cpu.supported)
        val cpuP95 = requireNotNull(cpu.p95Ms)
        assertTrue("CPU E5 P95 was $cpuP95 ms", cpuP95 < MAXIMUM_E5_P95_MS)
        results.filter { it.supported && it.profile != E5ExecutionProfile.CPU }.forEach { result ->
            assertNotNull(result.cosineToCpu)
            assertTrue(
                "${result.profile} cosine to CPU was ${result.cosineToCpu}",
                requireNotNull(result.cosineToCpu) >= MINIMUM_PROVIDER_COSINE,
            )
        }
    }

    private fun benchmarkProfile(
        context: Context,
        modelDirectory: File,
        profile: E5ExecutionProfile,
        cpuVector: FloatArray?,
    ): ProviderResult {
        val pssBeforeKb = Debug.getPss().toLong()
        val temperatureBeforeC = batteryTemperatureC(context)
        val openStarted = SystemClock.elapsedRealtimeNanos()
        val embedder = try {
            E5Embedder.open(modelDirectory, E5ModelSpec.PINNED, profile)
        } catch (error: Throwable) {
            return ProviderResult.unsupported(
                profile = profile,
                openMs = elapsedMillis(openStarted),
                failureType = error::class.java.simpleName,
                temperatureBeforeC = temperatureBeforeC,
                temperatureAfterC = batteryTemperatureC(context),
            )
        }
        val openMs = elapsedMillis(openStarted)
        return embedder.use { opened ->
            var reference = FloatArray(0)
            val times = mutableListOf<Double>()
            try {
                repeat(WARMUP_RUNS) { iteration ->
                    reference = opened.embed(listOf(QUERIES[iteration % QUERIES.size]), E5InputKind.QUERY).single()
                }
                repeat(MEASURED_RUNS) { iteration ->
                    val started = SystemClock.elapsedRealtimeNanos()
                    reference = opened.embed(listOf(QUERIES[iteration % QUERIES.size]), E5InputKind.QUERY).single()
                    times += elapsedMillis(started)
                }
                val norm = l2Norm(reference)
                check(norm in 0.999f..1.001f) { "Invalid E5 output norm" }
                ProviderResult(
                    profile = profile,
                    supported = true,
                    failureType = null,
                    openMs = openMs,
                    p50Ms = percentile(times, 0.50),
                    p95Ms = percentile(times, 0.95),
                    pssDeltaKb = (Debug.getPss().toLong() - pssBeforeKb).coerceAtLeast(0),
                    cosineToCpu = cpuVector?.let { E5Embedder.cosine(it, reference) },
                    outputNorm = norm,
                    temperatureBeforeC = temperatureBeforeC,
                    temperatureAfterC = batteryTemperatureC(context),
                    referenceVector = reference,
                )
            } catch (error: Throwable) {
                ProviderResult.unsupported(
                    profile = profile,
                    openMs = openMs,
                    failureType = error::class.java.simpleName,
                    temperatureBeforeC = temperatureBeforeC,
                    temperatureAfterC = batteryTemperatureC(context),
                )
            }
        }
    }

    private fun keepDebugTargetForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/.CheckpointTestHostActivity",
        ).close()
    }

    private fun batteryTemperatureC(context: Context): Double? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return tenths.takeIf { it != Int.MIN_VALUE }?.div(10.0)
    }

    private fun l2Norm(values: FloatArray): Float =
        sqrt(values.sumOf { value -> value.toDouble() * value.toDouble() }).toFloat()

    private fun elapsedMillis(startNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

    private fun percentile(values: List<Double>, quantile: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * quantile).toInt()]
    }

    private fun renderJson(results: List<ProviderResult>): String = buildString {
        append("{\n")
        append("  \"device\": \"").append(Build.MODEL).append("\",\n")
        append("  \"socModel\": \"").append(Build.SOC_MODEL).append("\",\n")
        append("  \"androidApi\": ").append(Build.VERSION.SDK_INT).append(",\n")
        append("  \"warmupRuns\": ").append(WARMUP_RUNS).append(",\n")
        append("  \"measuredRuns\": ").append(MEASURED_RUNS).append(",\n")
        append("  \"results\": [\n")
        results.forEachIndexed { index, result ->
            append("    ").append(result.toJson())
            if (index != results.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n}\n")
    }

    private data class ProviderResult(
        val profile: E5ExecutionProfile,
        val supported: Boolean,
        val failureType: String?,
        val openMs: Double,
        val p50Ms: Double?,
        val p95Ms: Double?,
        val pssDeltaKb: Long?,
        val cosineToCpu: Float?,
        val outputNorm: Float?,
        val temperatureBeforeC: Double?,
        val temperatureAfterC: Double?,
        val referenceVector: FloatArray,
    ) {
        fun toJson(): String = listOf(
            "\"profile\":\"${profile.name}\"",
            "\"supported\":$supported",
            "\"failureType\":${failureType?.let { "\"$it\"" } ?: "null"}",
            "\"openMs\":$openMs",
            "\"p50Ms\":${p50Ms ?: "null"}",
            "\"p95Ms\":${p95Ms ?: "null"}",
            "\"pssDeltaKb\":${pssDeltaKb ?: "null"}",
            "\"cosineToCpu\":${cosineToCpu ?: "null"}",
            "\"outputNorm\":${outputNorm ?: "null"}",
            "\"temperatureBeforeC\":${temperatureBeforeC ?: "null"}",
            "\"temperatureAfterC\":${temperatureAfterC ?: "null"}",
        ).joinToString(prefix = "{", postfix = "}")

        companion object {
            fun unsupported(
                profile: E5ExecutionProfile,
                openMs: Double,
                failureType: String,
                temperatureBeforeC: Double?,
                temperatureAfterC: Double?,
            ) = ProviderResult(
                profile = profile,
                supported = false,
                failureType = failureType,
                openMs = openMs,
                p50Ms = null,
                p95Ms = null,
                pssDeltaKb = null,
                cosineToCpu = null,
                outputNorm = null,
                temperatureBeforeC = temperatureBeforeC,
                temperatureAfterC = temperatureAfterC,
                referenceVector = FloatArray(0),
            )
        }
    }

    private companion object {
        const val WARMUP_RUNS = 5
        const val MEASURED_RUNS = 30
        const val MAXIMUM_E5_P95_MS = 1_200.0
        const val MINIMUM_PROVIDER_COSINE = 0.995f
        const val OUTPUT_FILE_NAME = "e5-execution-provider-benchmark.json"
        val QUERIES = listOf(
            "请根据项目计划总结下一阶段工作",
            "What are the payment terms in the uploaded contract?",
            "会议纪要里谁负责完成风险复核？",
        )
    }
}
