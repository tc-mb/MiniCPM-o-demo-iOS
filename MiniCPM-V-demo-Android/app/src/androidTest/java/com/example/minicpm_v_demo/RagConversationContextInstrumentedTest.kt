package com.example.minicpm_v_demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.minicpm_v_demo.rag.RagTurnTransaction
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RagConversationContextInstrumentedTest {
    @Test
    fun augmentedEvidenceIsAbsentAfterStableTurnCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = readyEngine(context)
        val originalQuestion = "What is the internal project code?"
        val acceptedAnswer = "The grounded answer was accepted."
        val secretEvidence = "RAG_ONLY_SECRET_COBALT_731"

        engine.clearContext()
        val clean = engine.nativeContextDebugSnapshot()
        val transaction = RagTurnTransaction(engine, engine.beginEphemeralTurn())
        withTimeout(120_000) {
            engine.sendPreparedPrompt(
                modelPrompt = "Evidence: $secretEvidence\nQuestion: $originalQuestion",
                originalUserTextForSafety = originalQuestion,
                predictLength = 8,
            ).toList()
        }
        val temporary = engine.nativeContextDebugSnapshot()
        assertNotEquals(clean.chatHistoryDigest, temporary.chatHistoryDigest)

        transaction.commit(originalQuestion, acceptedAnswer)
        val committed = engine.nativeContextDebugSnapshot()
        val committedCheckpoint = engine.beginEphemeralTurn()
        val nextTokenAfterRag = withTimeout(120_000) {
            engine.sendUserPrompt("Reply with one word: ready.", predictLength = 8).first()
        }
        engine.restoreEphemeralTurn(committedCheckpoint)

        engine.clearContext()
        engine.appendStableHistory(ModelHistoryRole.USER, originalQuestion)
        engine.appendStableHistory(ModelHistoryRole.ASSISTANT, acceptedAnswer)
        val rebuiltWithoutEvidence = engine.nativeContextDebugSnapshot()
        val rebuiltCheckpoint = engine.beginEphemeralTurn()
        val nextTokenWithoutEvidence = withTimeout(120_000) {
            engine.sendUserPrompt("Reply with one word: ready.", predictLength = 8).first()
        }
        engine.restoreEphemeralTurn(rebuiltCheckpoint)

        assertEquals(rebuiltWithoutEvidence, committed)
        assertEquals(nextTokenWithoutEvidence, nextTokenAfterRag)
    }

    private suspend fun readyEngine(context: Context): LlamaEngine {
        val engine = LlamaEngine.getInstance(context)
        val initializedState = withTimeout(30_000) {
            engine.state.first { state: LlamaState ->
                state is LlamaState.Initialized ||
                    state is LlamaState.ModelReady ||
                    state is LlamaState.Error
            }
        }
        check(initializedState !is LlamaState.Error) { "Native initialization failed" }
        if (initializedState is LlamaState.Initialized) {
            val model = File(LlamaEngine.modelPath(context))
            check(model.isFile) { "Production model is not installed: ${model.absolutePath}" }
            val mmproj = LlamaEngine.mmprojPath(context)?.let(::File)?.takeIf(File::isFile)
            withTimeout(180_000) {
                engine.loadModel(model.absolutePath, mmproj?.absolutePath)
            }
        }
        return engine
    }
}
