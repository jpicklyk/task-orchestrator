package io.github.jpicklyk.mcptask.current.run

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.lifecycle.DecisionReads
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import java.util.UUID

/**
 * Persistence of decision requests (`decision_requests`). Every method joins the caller's unit; the writes belong to
 * `DecisionService` only. A database failure is thrown.
 */
interface DecisionRequestStore : DecisionReads {
    /** Inserts [decision] and returns it as stored. */
    suspend fun create(decision: DecisionRequest): DecisionRequest

    /**
     * The request that is NOT withdrawn with this (root, scope key, dedupe key), or null. The partial unique index
     * guarantees there is at most one; a withdrawn request never matches.
     */
    suspend fun findByKey(
        rootId: UUID,
        scopeKey: String,
        dedupeKey: String
    ): DecisionRequest?

    /**
     * Writes the mutable fields of [decision] (status, acknowledgement, answer) when the stored version is still
     * [expectedVersion], bumping it to `expectedVersion + 1`. Returns the request as stored, or null when it is gone or
     * its version moved.
     */
    suspend fun update(
        decision: DecisionRequest,
        expectedVersion: Long
    ): DecisionRequest?

    /** The number of requests of run [runId] still waiting for an answer (open or acknowledged). */
    suspend fun openCount(runId: UUID): Int
}

/**
 * What the run service needs to know about a run's decision requests: how many still wait for an answer when the run
 * closes (`run.ended` `openDecisions`). [NONE] answers 0 for every run (a run service built without decisions, in tests).
 */
fun interface RunDecisionPort {
    /** The number of decision requests of [runId] that are open or acknowledged. Joins the caller's unit. */
    suspend fun openCount(runId: UUID): Int

    companion object {
        /** No decision requests exist. */
        val NONE: RunDecisionPort = RunDecisionPort { 0 }

        /**
         * Counts over [provider]'s decision store. A provider that serves no decision store (its
         * `decisionRequestStore()` throws [UnsupportedOperationException], as the [RepositoryProvider] default does) has none.
         */
        fun over(provider: RunStoreProvider): RunDecisionPort =
            RunDecisionPort { runId ->
                val store =
                    try {
                        provider.decisionRequestStore()
                    } catch (e: UnsupportedOperationException) {
                        return@RunDecisionPort 0
                    }
                store.openCount(runId)
            }
    }
}
