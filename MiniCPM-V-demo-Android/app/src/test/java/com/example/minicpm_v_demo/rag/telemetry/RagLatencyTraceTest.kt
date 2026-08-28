package com.example.minicpm_v_demo.rag.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RagLatencyTraceTest {
    @Test
    fun recordsCompletedPhaseDurationUsingMonotonicClock() {
        val clock = FakeMonotonicClock()
        val trace = RagLatencyTrace.start(runId = "run-1", clock = clock)

        trace.begin(RagPhase.ROUTE)
        clock.advanceMillis(4)
        trace.end(RagPhase.ROUTE)

        val snapshot = trace.snapshot()
        assertEquals(4L, snapshot.durationsMs.getValue(RagPhase.ROUTE))
        assertEquals("run-1", snapshot.runId)
    }

    @Test
    fun rejectsEndingTheSamePhaseTwice() {
        val trace = RagLatencyTrace.start("run-2", FakeMonotonicClock())
        trace.begin(RagPhase.ROUTE)
        trace.end(RagPhase.ROUTE)

        assertThrows(IllegalStateException::class.java) {
            trace.end(RagPhase.ROUTE)
        }
    }

    @Test
    fun rejectsBeginningAnotherPhaseBeforeTheCurrentPhaseEnds() {
        val trace = RagLatencyTrace.start("run-3", FakeMonotonicClock())
        trace.begin(RagPhase.ROUTE)

        assertThrows(IllegalStateException::class.java) {
            trace.begin(RagPhase.EMBED)
        }
    }

    @Test
    fun rejectsACompletedTraceMovingBackToAnEarlierPhase() {
        val trace = RagLatencyTrace.start("run-order", FakeMonotonicClock())
        trace.begin(RagPhase.DENSE)
        trace.end(RagPhase.DENSE)

        assertThrows(IllegalStateException::class.java) {
            trace.begin(RagPhase.EMBED)
        }
    }

    @Test
    fun rejectsAClockThatMovesBackwards() {
        val clock = FakeMonotonicClock(initialNanos = 5_000_000)
        val trace = RagLatencyTrace.start("run-4", clock)
        trace.begin(RagPhase.ROUTE)
        clock.setNanos(4_000_000)

        assertThrows(IllegalStateException::class.java) {
            trace.end(RagPhase.ROUTE)
        }
    }

    @Test
    fun snapshotContainsMetricsButNoPromptOrDocumentText() {
        val trace = RagLatencyTrace.start("run-safe", FakeMonotonicClock())
        trace.recordCandidateCount(7)
        trace.recordEvidenceTokenCount(320)

        val snapshot = trace.snapshot()
        assertEquals(7, snapshot.candidateCount)
        assertEquals(320, snapshot.evidenceTokenCount)
        assertFalse(snapshot.toString().contains("query", ignoreCase = true))
        assertFalse(snapshot.toString().contains("document", ignoreCase = true))
    }

    @Test
    fun logFormatterUsesOnlyHashedRunIdEnumsAndNumericMetrics() {
        val rawRunId = "private-run-id"
        val clock = FakeMonotonicClock()
        val trace = RagLatencyTrace.start(rawRunId, clock)
        trace.begin(RagPhase.EMBED)
        clock.advanceMillis(12)
        trace.end(RagPhase.EMBED)
        trace.recordCandidateCount(3)
        trace.recordEvidenceTokenCount(128)

        val line = RagLatencyLogFormatter.format(
            snapshot = trace.snapshot(),
            result = RagTraceResult.AUGMENTED,
        )

        assertFalse(line.contains(rawRunId))
        assertTrue(line.matches(Regex("rag_trace run=[0-9a-f]{12} result=AUGMENTED .*")))
        assertTrue(line.contains("EMBED:12"))
        assertTrue(line.contains("candidates=3"))
        assertTrue(line.contains("evidenceTokens=128"))
    }

    private class FakeMonotonicClock(
        initialNanos: Long = 0,
    ) : MonotonicClock {
        private var nowNanos = initialNanos

        override fun nowNanos(): Long = nowNanos

        fun advanceMillis(milliseconds: Long) {
            nowNanos += milliseconds * 1_000_000
        }

        fun setNanos(value: Long) {
            nowNanos = value
        }
    }
}
