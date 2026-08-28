package com.example.minicpm_v_demo.rag

import com.example.minicpm_v_demo.ModelHistoryRole
import com.example.minicpm_v_demo.NativeCheckpoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface EphemeralContextEngine {
    suspend fun beginEphemeralTurn(): NativeCheckpoint

    suspend fun restoreEphemeralTurn(checkpoint: NativeCheckpoint)

    suspend fun releaseEphemeralTurn(checkpoint: NativeCheckpoint)

    suspend fun appendStableHistory(role: ModelHistoryRole, text: String)
}

/**
 * Owns one native checkpoint while retrieval evidence is temporarily present.
 * Closing is idempotent so catch/finally paths cannot restore the same native handle twice.
 */
class RagTurnTransaction(
    private val engine: EphemeralContextEngine,
    private val checkpoint: NativeCheckpoint,
) {
    private var closed = false

    suspend fun commit(originalUserText: String, acceptedAnswer: String) {
        require(originalUserText.isNotBlank()) { "Stable user history must not be blank" }
        require(acceptedAnswer.isNotBlank()) { "Accepted answer must not be blank" }
        close {
            engine.appendStableHistory(ModelHistoryRole.USER, originalUserText)
            engine.appendStableHistory(ModelHistoryRole.ASSISTANT, acceptedAnswer)
        }
    }

    suspend fun rollback(keepUserInHistory: Boolean, originalUserText: String) {
        if (keepUserInHistory) {
            require(originalUserText.isNotBlank()) { "Stable user history must not be blank" }
        }
        close {
            if (keepUserInHistory) {
                engine.appendStableHistory(ModelHistoryRole.USER, originalUserText)
            }
        }
    }

    private suspend fun close(appendStableHistory: suspend () -> Unit) {
        withContext(NonCancellable) {
            if (closed) return@withContext
            closed = true
            try {
                engine.restoreEphemeralTurn(checkpoint)
            } catch (restoreFailure: Throwable) {
                runCatching { engine.releaseEphemeralTurn(checkpoint) }
                    .onFailure(restoreFailure::addSuppressed)
                throw restoreFailure
            }
            appendStableHistory()
        }
    }
}
