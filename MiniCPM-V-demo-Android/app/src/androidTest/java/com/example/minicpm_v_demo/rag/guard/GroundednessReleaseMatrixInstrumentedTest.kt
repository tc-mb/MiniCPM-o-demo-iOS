package com.example.minicpm_v_demo.rag.guard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.minicpm_v_demo.MiniCPMApplication
import com.example.minicpm_v_demo.rag.retrieval.RetrievedChunk
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroundednessReleaseMatrixInstrumentedTest {
    @Test
    fun correctEvidencePassesAndWrongAmountDateOrUnsupportedClaimCannotPass() = runBlocking {
        keepDebugTargetForeground()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context.applicationContext as MiniCPMApplication
        val classifier = requireNotNull(app.ragGuardModelManager.openInstalled()) {
            "Pinned RAG Guard model must be installed"
        }
        val question = "合同约定的金额、付款日期和负责人是什么？"
        val sources = listOf(
            RetrievedChunk(
                chunkId = 1,
                documentId = "synthetic-groundedness",
                displayName = "synthetic-contract.txt",
                locator = "第3条",
                text = "合同总金额为100元，付款日期为2026年8月1日，负责人为李明。",
                score = 0.99f,
                tokenCount = 24,
            ),
        )
        val cases = listOf(
            Case("correct", "合同金额为100元，付款日期是2026年8月1日，负责人是李明。", true),
            Case("wrong_amount", "合同金额为999元，付款日期是2026年8月1日，负责人是李明。", false),
            Case("wrong_date", "合同金额为100元，付款日期是2027年1月1日，负责人是李明。", false),
            Case("unsupported", "合同已经由董事会一致批准，并且可以自动续期。", false),
        )
        val results = cases.map { case ->
            val verdict = classifier.classifyGroundedness(question, sources, case.answer)
            Result(case, verdict, isAccepted(verdict))
        }
        val outputDirectory = requireNotNull(context.getExternalFilesDir("benchmarks"))
        File(outputDirectory, OUTPUT_FILE_NAME).writeText(renderJson(results), Charsets.UTF_8)

        results.forEach { result ->
            assertEquals(CurrentRagGuardModel.PINNED.model.sha256, result.verdict.modelSha256)
            assertTrue(result.verdict.groundedProbability.isFinite())
            if (result.case.shouldPass) assertTrue(result.accepted) else assertFalse(result.accepted)
        }
    }

    private fun isAccepted(verdict: GroundednessVerdict): Boolean =
        verdict.label == GroundednessLabel.GROUNDED &&
            verdict.groundedProbability >= ExperimentalGroundednessCalibration.profile.groundedProbabilityThreshold

    private fun keepDebugTargetForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/.CheckpointTestHostActivity",
        ).close()
    }

    private fun renderJson(results: List<Result>): String = buildString {
        append("{\n  \"threshold\": ")
            .append(ExperimentalGroundednessCalibration.profile.groundedProbabilityThreshold)
            .append(",\n  \"results\": [\n")
        results.forEachIndexed { index, result ->
            append("    {\"case\":\"").append(result.case.name)
                .append("\",\"expectedPass\":").append(result.case.shouldPass)
                .append(",\"label\":\"").append(result.verdict.label.name)
                .append("\",\"groundedProbability\":").append(result.verdict.groundedProbability)
                .append(",\"accepted\":").append(result.accepted).append('}')
            if (index != results.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n}\n")
    }

    private data class Case(val name: String, val answer: String, val shouldPass: Boolean)
    private data class Result(val case: Case, val verdict: GroundednessVerdict, val accepted: Boolean)

    private companion object {
        const val OUTPUT_FILE_NAME = "groundedness-release-matrix.json"
    }
}
