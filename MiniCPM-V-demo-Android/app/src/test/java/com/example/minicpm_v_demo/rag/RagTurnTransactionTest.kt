package com.example.minicpm_v_demo.rag

import com.example.minicpm_v_demo.ModelHistoryRole
import com.example.minicpm_v_demo.NativeCheckpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RagTurnTransactionTest {
    @Test
    fun commit_restoresOnce_thenAppendsStableUserAndAcceptedAnswer() = runBlocking {
        val engine = FakeEphemeralContextEngine()
        val transaction = RagTurnTransaction(engine, engine.beginEphemeralTurn())

        transaction.commit("original question", "grounded answer")
        transaction.commit("ignored", "ignored")

        assertEquals(1, engine.restoreCalls)
        assertEquals(0, engine.releaseCalls)
        assertEquals(
            listOf(
                ModelHistoryRole.USER to "original question",
                ModelHistoryRole.ASSISTANT to "grounded answer",
            ),
            engine.stableHistory,
        )
    }

    @Test
    fun rollbackAfterGenerationFailure_restoresOnce_andKeepsOriginalUser() = runBlocking {
        val engine = FakeEphemeralContextEngine()
        val transaction = RagTurnTransaction(engine, engine.beginEphemeralTurn())

        transaction.rollback(keepUserInHistory = true, originalUserText = "original question")

        assertEquals(1, engine.restoreCalls)
        assertEquals(listOf(ModelHistoryRole.USER to "original question"), engine.stableHistory)
    }

    @Test
    fun rollbackAfterCancellation_restoresOnce_andKeepsOriginalUser() = runBlocking {
        val engine = FakeEphemeralContextEngine()
        val transaction = RagTurnTransaction(engine, engine.beginEphemeralTurn())

        transaction.rollback(keepUserInHistory = true, originalUserText = "cancelled question")
        transaction.rollback(keepUserInHistory = true, originalUserText = "ignored")

        assertEquals(1, engine.restoreCalls)
        assertEquals(listOf(ModelHistoryRole.USER to "cancelled question"), engine.stableHistory)
    }

    @Test
    fun rollbackAfterContentRejection_restoresWithoutCommittingCandidate() = runBlocking {
        val engine = FakeEphemeralContextEngine()
        val transaction = RagTurnTransaction(engine, engine.beginEphemeralTurn())

        transaction.rollback(keepUserInHistory = true, originalUserText = "unsafe question")

        assertEquals(1, engine.restoreCalls)
        assertEquals(listOf(ModelHistoryRole.USER to "unsafe question"), engine.stableHistory)
    }

    @Test
    fun restoreFailure_releasesCheckpoint_once_andDoesNotAppendHistory() {
        val engine = FakeEphemeralContextEngine(failRestore = true)
        val transaction = runBlocking {
            RagTurnTransaction(engine, engine.beginEphemeralTurn())
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { transaction.commit("original", "answer") }
        }
        runBlocking { transaction.rollback(true, "ignored") }

        assertEquals(1, engine.restoreCalls)
        assertEquals(1, engine.releaseCalls)
        assertEquals(emptyList<Pair<ModelHistoryRole, String>>(), engine.stableHistory)
    }

    @Test
    fun pressureMatrix_closesEverySuccessfulAndCancelledTransactionExactlyOnce() = runBlocking {
        val engine = FakeEphemeralContextEngine()

        repeat(100) { index ->
            RagTurnTransaction(engine, engine.beginEphemeralTurn())
                .commit("question $index", "answer $index")
        }
        repeat(50) { index ->
            val transaction = RagTurnTransaction(engine, engine.beginEphemeralTurn())
            transaction.rollback(keepUserInHistory = false, originalUserText = "cancelled $index")
            transaction.rollback(keepUserInHistory = false, originalUserText = "ignored $index")
        }

        assertEquals(150, engine.restoreCalls)
        assertEquals(0, engine.releaseCalls)
        assertEquals(200, engine.stableHistory.size)
    }

    private class FakeEphemeralContextEngine(
        private val failRestore: Boolean = false,
    ) : EphemeralContextEngine {
        var restoreCalls = 0
        var releaseCalls = 0
        val stableHistory = mutableListOf<Pair<ModelHistoryRole, String>>()

        override suspend fun beginEphemeralTurn(): NativeCheckpoint = NativeCheckpoint(7L, 128L)

        override suspend fun restoreEphemeralTurn(checkpoint: NativeCheckpoint) {
            restoreCalls += 1
            if (failRestore) throw IllegalStateException("restore failed")
        }

        override suspend fun releaseEphemeralTurn(checkpoint: NativeCheckpoint) {
            releaseCalls += 1
        }

        override suspend fun appendStableHistory(role: ModelHistoryRole, text: String) {
            stableHistory += role to text
        }
    }
}
