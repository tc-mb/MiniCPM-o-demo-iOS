package com.example.minicpm_v_demo.rag.guard

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagGuardInstrumentedTest {
    @Test
    fun installedInt8ModelRunsBothHeadsWithStableCpuLatency() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            as MiniCPMApplication
        phase("before_manager_close")
        app.ragGuardModelManager.close()
        phase("after_manager_close")
        val pssBeforeKb = Debug.getPss()
        phase("before_model_open")
        val openStarted = SystemClock.elapsedRealtimeNanos()
        val classifier = app.ragGuardModelManager.openInstalled()
        val openMs = nanosToMs(SystemClock.elapsedRealtimeNanos() - openStarted)
        phase("after_model_open")
        assertNotNull("The pinned RAG guard model must be installed and valid", classifier)
        requireNotNull(classifier)

        val source = RetrievedChunk(
            chunkId = 1,
            displayName = "anonymous.txt",
            locator = "line 1",
            text = "The annual leave allowance is ten days.",
            score = 1f,
            documentId = "anonymous-document",
            tokenCount = 8,
        )
        val question = "How many days of annual leave are allowed?"
        val answer = "The annual leave allowance is ten days."

        repeat(WARMUP_RUNS) {
            phase("warmup_${it + 1}_answerability")
            classifier.classifyAnswerability(question, listOf(source))
            phase("warmup_${it + 1}_groundedness")
            classifier.classifyGroundedness(question, listOf(source), answer)
        }
        phase("warmup_complete")

        val answerabilityMs = ArrayList<Double>(MEASURED_RUNS)
        val groundednessMs = ArrayList<Double>(MEASURED_RUNS)
        var answerabilityLabel: Any? = null
        var groundednessLabel: Any? = null
        repeat(MEASURED_RUNS) {
            if (it % 5 == 0) phase("measured_${it + 1}")
            val answerabilityStarted = SystemClock.elapsedRealtimeNanos()
            val answerability = classifier.classifyAnswerability(question, listOf(source))
            answerabilityMs += nanosToMs(SystemClock.elapsedRealtimeNanos() - answerabilityStarted)
            val groundednessStarted = SystemClock.elapsedRealtimeNanos()
            val groundedness = classifier.classifyGroundedness(question, listOf(source), answer)
            groundednessMs += nanosToMs(SystemClock.elapsedRealtimeNanos() - groundednessStarted)

            if (answerabilityLabel == null) answerabilityLabel = answerability.label
            if (groundednessLabel == null) groundednessLabel = groundedness.label
            assertEquals(answerabilityLabel, answerability.label)
            assertEquals(groundednessLabel, groundedness.label)
            assertTrue(answerability.supportedProbability in 0f..1f)
            assertTrue(groundedness.groundedProbability in 0f..1f)
            assertEquals(CurrentRagGuardModel.PINNED.model.sha256, answerability.modelSha256)
            assertEquals(CurrentRagGuardModel.PINNED.model.sha256, groundedness.modelSha256)
        }

        val pssAfterKb = Debug.getPss()
        phase("measured_complete")
        sendResult(
            String.format(
                Locale.ROOT,
                "provider=CPU open_ms=%.3f answer_p50_ms=%.3f answer_p95_ms=%.3f " +
                    "ground_p50_ms=%.3f ground_p95_ms=%.3f pss_delta_kb=%d runs=%d",
                openMs,
                percentile(answerabilityMs, 0.50),
                percentile(answerabilityMs, 0.95),
                percentile(groundednessMs, 0.50),
                percentile(groundednessMs, 0.95),
                pssAfterKb - pssBeforeKb,
                MEASURED_RUNS,
            ),
        )
    }

    private fun sendResult(summary: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_RESULT,
            Bundle().apply { putString("rag_guard_benchmark", summary) },
        )
    }

    private fun phase(value: String) {
        Log.i(LOG_TAG, value)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            STATUS_PROGRESS,
            Bundle().apply { putString("rag_guard_phase", value) },
        )
    }

    private fun percentile(values: List<Double>, quantile: Double): Double {
        require(values.isNotEmpty() && quantile in 0.0..1.0)
        val sorted = values.sorted()
        val index = kotlin.math.ceil(quantile * sorted.size).toInt().coerceAtLeast(1) - 1
        return sorted[index]
    }

    private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0

    private companion object {
        const val WARMUP_RUNS = 5
        const val MEASURED_RUNS = 30
        const val STATUS_RESULT = 2
        const val STATUS_PROGRESS = 0
        const val LOG_TAG = "RagGuardTest"
    }
}
