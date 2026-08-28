package com.example.minicpm_v_demo.rag

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.LlamaEngine
import com.example.minicpm_v_demo.LlamaState
import com.example.minicpm_v_demo.ModelHistoryRole
import com.example.minicpm_v_demo.rag.retrieval.RagPromptAssembler
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the production native prompt path without scoring model output.
 *
 * The fixture is synthetic, does not read user conversations, and restores a
 * native checkpoint after every probe so benchmark prompts never become stable
 * model history.
 */
@RunWith(AndroidJUnit4::class)
class RagEndToEndPerformanceInstrumentedTest {
    @Test
    fun plainAndAugmentedTtftAcrossHistoryDepths(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        keepDebugTargetForeground(context)
        val engine = readyEngine(context)
        val results = mutableListOf<HistoryResult>()

        HISTORY_TURNS.forEach { historyTurns ->
            seedSyntheticHistory(engine, historyTurns)
            if (historyTurns == 0) {
                measureFirstToken(engine, PLAIN_PROMPT, prepared = false)
                measureFirstToken(engine, RAG_PROMPT, prepared = true)
            }

            val plainSamples = mutableListOf<Long>()
            val ragSamples = mutableListOf<Long>()
            var peakPssKb = currentPssKb()
            repeat(MEASURED_RUNS) {
                plainSamples += measureFirstToken(engine, PLAIN_PROMPT, prepared = false)
                peakPssKb = maxOf(peakPssKb, currentPssKb())
                ragSamples += measureFirstToken(engine, RAG_PROMPT, prepared = true)
                peakPssKb = maxOf(peakPssKb, currentPssKb())
            }
            results += HistoryResult(historyTurns, plainSamples, ragSamples, peakPssKb)
        }

        assertEquals(HISTORY_TURNS.toList(), results.map(HistoryResult::historyTurns))
        writeAggregateEvidence(context, results)
        engine.clearContext()
        Unit
    }

    private suspend fun seedSyntheticHistory(engine: LlamaEngine, turns: Int) {
        engine.clearContext()
        repeat(turns) { index ->
            engine.replayHistoryMessage(ModelHistoryRole.USER, "历史问题 ${index + 1}：请确认收到。")
            engine.replayHistoryMessage(ModelHistoryRole.ASSISTANT, "已收到第 ${index + 1} 条。")
        }
    }

    private suspend fun measureFirstToken(
        engine: LlamaEngine,
        prompt: String,
        prepared: Boolean,
    ): Long {
        val checkpoint = engine.beginEphemeralTurn()
        return try {
            val started = SystemClock.elapsedRealtimeNanos()
            val token = withTimeout(TTFT_TIMEOUT_MS) {
                if (prepared) {
                    engine.sendPreparedPrompt(
                        modelPrompt = prompt,
                        originalUserTextForSafety = PLAIN_PROMPT,
                        predictLength = 1,
                    ).firstOrNull()
                } else {
                    engine.sendUserPrompt(prompt, predictLength = 1).firstOrNull()
                }
            }
            assertNotNull("Model completed without emitting a first token", token)
            (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLISECOND
        } finally {
            engine.restoreEphemeralTurn(checkpoint)
        }
    }

    private suspend fun readyEngine(context: Context): LlamaEngine {
        val engine = LlamaEngine.getInstance(context)
        val state = withTimeout(ENGINE_INIT_TIMEOUT_MS) {
            engine.state.first { current ->
                current is LlamaState.Initialized ||
                    current is LlamaState.ModelReady ||
                    current is LlamaState.Error
            }
        }
        check(state !is LlamaState.Error) { "Native initialization failed" }
        if (state is LlamaState.Initialized) {
            val model = File(LlamaEngine.modelPath(context))
            check(model.isFile) { "Production model is not installed" }
            val mmproj = LlamaEngine.mmprojPath(context)?.let(::File)?.takeIf(File::isFile)
            withTimeout(MODEL_LOAD_TIMEOUT_MS) {
                engine.loadModel(model.absolutePath, mmproj?.absolutePath)
            }
        }
        return engine
    }

    private fun currentPssKb(): Int = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

    private fun keepDebugTargetForeground(context: Context) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/.CheckpointTestHostActivity",
        ).close()
    }

    private fun writeAggregateEvidence(context: Context, results: List<HistoryResult>) {
        val output = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "test-evidence/rag-end-to-end-performance.json",
        )
        output.parentFile?.mkdirs()
        output.writeText(
            buildString {
                append("{\n  \"device\": \"")
                append(android.os.Build.MODEL.replace("\"", ""))
                append("\",\n  \"measuredRuns\": ")
                append(MEASURED_RUNS)
                append(",\n  \"histories\": [\n")
                results.forEachIndexed { index, result ->
                    append("    {\"turns\":")
                    append(result.historyTurns)
                    append(",\"plainTtftMs\":")
                    append(result.plainSamples)
                    append(",\"plainP50Ms\":")
                    append(percentile(result.plainSamples, 0.50))
                    append(",\"plainP95Ms\":")
                    append(percentile(result.plainSamples, 0.95))
                    append(",\"ragTtftMs\":")
                    append(result.ragSamples)
                    append(",\"ragP50Ms\":")
                    append(percentile(result.ragSamples, 0.50))
                    append(",\"ragP95Ms\":")
                    append(percentile(result.ragSamples, 0.95))
                    append(",\"peakPssKb\":")
                    append(result.peakPssKb)
                    append('}')
                    if (index != results.lastIndex) append(',')
                    append('\n')
                }
                append("  ]\n}\n")
            },
        )
    }

    private fun percentile(samples: List<Long>, fraction: Double): Long {
        val sorted = samples.sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private data class HistoryResult(
        val historyTurns: Int,
        val plainSamples: List<Long>,
        val ragSamples: List<Long>,
        val peakPssKb: Int,
    )

    private companion object {
        val HISTORY_TURNS = intArrayOf(0, 10, 30)
        const val MEASURED_RUNS = 5
        const val PLAIN_PROMPT = "请简短回复：收到。"
        val RAG_PROMPT = RagPromptAssembler.assemble(
            question = PLAIN_PROMPT,
            sources = listOf(
                RetrievedChunk(
                    chunkId = 1,
                    documentId = "synthetic-document",
                    displayName = "synthetic-note.txt",
                    locator = "line 1",
                    text = "合成资料说明：收到请求后应回复已收到。",
                    score = 1f,
                ),
            ),
        )
        const val ENGINE_INIT_TIMEOUT_MS = 30_000L
        const val MODEL_LOAD_TIMEOUT_MS = 180_000L
        const val TTFT_TIMEOUT_MS = 120_000L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
