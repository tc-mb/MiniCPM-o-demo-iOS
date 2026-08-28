package com.example.minicpm_v_demo.rag.telemetry

import java.security.MessageDigest

fun interface MonotonicClock {
    fun nowNanos(): Long
}

enum class RagPhase {
    ROUTE,
    EMBED,
    LEXICAL,
    DENSE,
    FUSION,
    REDUCE,
    CHECKPOINT_SAVE,
    PREFILL,
    TTFT,
    CHECKPOINT_RESTORE,
}

data class RagLatencySnapshot(
    val runId: String,
    val durationsMs: Map<RagPhase, Long>,
    val candidateCount: Int,
    val evidenceTokenCount: Int,
)

enum class RagTraceResult {
    PASS_THROUGH,
    AUGMENTED,
    LOCAL_REPLY,
    FAILED,
    CANCELLED,
}

object RagLatencyLogFormatter {
    fun format(snapshot: RagLatencySnapshot, result: RagTraceResult): String {
        val phases = snapshot.durationsMs.entries.joinToString(",") { (phase, durationMs) ->
            "${phase.name}:$durationMs"
        }
        return buildString {
            append("rag_trace run=")
            append(hashRunId(snapshot.runId))
            append(" result=")
            append(result.name)
            append(" phases=")
            append(phases)
            append(" candidates=")
            append(snapshot.candidateCount)
            append(" evidenceTokens=")
            append(snapshot.evidenceTokenCount)
        }
    }

    private fun hashRunId(runId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(runId.toByteArray(Charsets.UTF_8))
        .take(HASH_PREFIX_BYTES)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private const val HASH_PREFIX_BYTES = 6
}

class RagLatencyTrace private constructor(
    private val runId: String,
    private val clock: MonotonicClock,
) {
    private val completedDurationsMs = linkedMapOf<RagPhase, Long>()
    private var activePhase: RagPhase? = null
    private var activePhaseStartedNanos: Long = 0
    private var lastCompletedPhaseOrdinal: Int = -1
    private var candidateCount: Int = 0
    private var evidenceTokenCount: Int = 0

    @Synchronized
    fun begin(phase: RagPhase) {
        check(activePhase == null) {
            "Cannot begin $phase while $activePhase is active"
        }
        check(phase !in completedDurationsMs) {
            "Phase $phase has already completed"
        }
        check(phase.ordinal > lastCompletedPhaseOrdinal) {
            "Phase $phase cannot follow a later completed phase"
        }
        activePhase = phase
        activePhaseStartedNanos = clock.nowNanos()
    }

    @Synchronized
    fun end(phase: RagPhase) {
        check(activePhase == phase) {
            "Cannot end $phase because the active phase is $activePhase"
        }
        val elapsedNanos = clock.nowNanos() - activePhaseStartedNanos
        check(elapsedNanos >= 0) {
            "Monotonic clock moved backwards while measuring $phase"
        }
        completedDurationsMs[phase] = elapsedNanos / NANOS_PER_MILLISECOND
        lastCompletedPhaseOrdinal = phase.ordinal
        activePhase = null
        activePhaseStartedNanos = 0
    }

    @Synchronized
    fun recordCandidateCount(count: Int) {
        require(count >= 0) { "Candidate count must not be negative" }
        candidateCount = count
    }

    @Synchronized
    fun recordEvidenceTokenCount(count: Int) {
        require(count >= 0) { "Evidence token count must not be negative" }
        evidenceTokenCount = count
    }

    @Synchronized
    fun snapshot(): RagLatencySnapshot = RagLatencySnapshot(
        runId = runId,
        durationsMs = completedDurationsMs.toMap(),
        candidateCount = candidateCount,
        evidenceTokenCount = evidenceTokenCount,
    )

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun start(
            runId: String,
            clock: MonotonicClock = MonotonicClock(System::nanoTime),
        ): RagLatencyTrace {
            require(runId.isNotBlank()) { "runId must not be blank" }
            return RagLatencyTrace(runId = runId, clock = clock)
        }
    }
}
